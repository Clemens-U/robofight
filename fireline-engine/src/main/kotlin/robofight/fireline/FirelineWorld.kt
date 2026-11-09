package robofight.fireline

/**
 * The authoritative FIRELINE world: state + the fixed tick order
 * (FIRELINE-DESIGN.md §8). One [tickOnce] call is one world tick — the same
 * unit the app's `STEP` control advances (§11.5), and the same path the headless
 * runner uses (§7.1: the headless construction path is the authority).
 *
 * The 16-step order is normative: changing it is a [FIRELINE_ENGINE_VERSION]
 * bump. It is written out in full from the start so the ordering itself is
 * reviewable from day one; a model that a later PLAN §8.3 step owns stays inert
 * and its stub names the step that fills it:
 * ```text
 *  1 if finished return        9 refill           13 protected asset check
 *  2 tick++                   10 exposure         14 end conditions (§7.4)
 *  3 publish tick zero page   11 heat rules       15 shield duration--
 *  4 clear events/heated      12 spread (tick%5)  16 publish log/snapshot
 *  5 VM step
 *  6 physical completion      (the VM fires the deferred side effect on the op's
 *  7 contact interactions      final tick, so 5 and 6 share the same call)
 *  8 water packets
 * ```
 *
 * No Arena dependency: the CPU is reached only through `robofight.vm.Vm` /
 * `robofight.vm.Env` from `:rf8-core`.
 */

enum class CivilianStatus { WAITING, RESCUED, LOST }

/** A civilian in the world (§3.4): stable mission-local ID, HP, status. */
class Civilian(val id: Int, val x: Int, val y: Int, val startHp: Int, val required: Boolean) {
    var hp: Int = startHp
        private set
    var status: CivilianStatus = CivilianStatus.WAITING
        private set

    val onGrid: Boolean get() = status == CivilianStatus.WAITING

    fun damage(d: Int) {
        if (!onGrid) return
        hp = (hp - d).coerceAtLeast(0)
        if (hp == 0) status = CivilianStatus.LOST
    }

    fun rescue() {
        if (status == CivilianStatus.WAITING) status = CivilianStatus.RESCUED
    }

    override fun toString(): String =
        "civ#$id($x,$y,hp=$hp,$status${if (required) ",required" else ""})"
}

/** Selected burning cell + its sensor view (§6.1). */
data class FireTarget(val x: Int, val y: Int, val intensity: Int, val dist: Int)

class FirelineWorld(val descriptor: MissionDescriptor) {
    val layout: Layout = descriptor.layout

    /** Terrain is never mutated in v1 (no destructible terrain, §2 non-goals). */
    private val terrain: IntArray = layout.terrain.copyOf()

    /** Dynamic per-cell fire intensity 0..100 (§3.3) — world state, not terrain. */
    private val fire: IntArray = layout.fire.copyOf()

    val civilians: MutableList<Civilian> =
        layout.civilians.map { Civilian(it.id, it.x, it.y, it.hp, it.required) }.toMutableList()

    val rover: Rover = Rover(this, descriptor.startingWater)

    /** Suppressant packets in flight (§3.6); bounded list, max life [PACKET_LIFE]. */
    private val packets: MutableList<WaterPacket> = ArrayList()

    /** Separate RNG streams (§4.3): sensor draws vs environmental spread. */
    val sensorRng: LcgStream = LcgStream.forSensor(descriptor.seed)
    val spreadRng: LcgStream = LcgStream.forSpread(descriptor.seed)

    var tick: Int = 0
        private set
    var finished: Boolean = false
        private set
    var status: RunStatus? = null
        private set
    var failureReason: FailureReason? = null
        private set
    var lastOp: String = "(loaded)"
        private set
    var lastEvents: List<String> = emptyList()
        private set

    /** Observer hook for the app's live monitor (§8 step 16). */
    var onTick: ((FirelineWorld) -> Unit)? = null

    private val events: MutableList<String> = ArrayList()
    private var roverMovedThisTick = false
    private var vmFaultThisTick = false

    /** Cells whose fire reached intensity 100 (§3.2 protected-asset rule). */
    private val failedAssets: MutableSet<Int> = HashSet()

    init {
        descriptor.requireValid()
        rover.place(layout.roverX, layout.roverY, layout.roverDir)
        rover.vm.load(descriptor.firmware)
    }

    // ---- cell access ----
    fun terrainAt(x: Int, y: Int): Int = terrain[cellIndex(x, y)]
    fun fireAt(x: Int, y: Int): Int = fire[cellIndex(x, y)]
    internal fun setFire(x: Int, y: Int, value: Int) {
        fire[cellIndex(x, y)] = value.coerceIn(0, FIRE_MAX)
    }

    /** Row-major raw state (canonical hash input, §13.2). */
    fun terrainRowMajor(): IntArray = terrain.copyOf()
    fun fireRowMajor(): IntArray = fire.copyOf()

    internal fun addEvent(text: String) { events.add(text) }
    internal fun noteDryShot() { addEvent("WATER EMPTY") }

    // ================= authority: one world tick =================
    fun tickOnce() {
        if (finished) return                                                 // 1

        tick++                                                               // 2
        rover.syncSysPage(tick)                                              // 3
        events.clear()                                                       // 4
        rover.heatedThisTick = false
        roverMovedThisTick = false
        vmFaultThisTick = false

        rover.vm.step(rover)                                                 // 5 (+ 6)
        lastOp = rover.vm.lastOp
        if (rover.vm.fault) vmFaultThisTick = true

        resolveContactInteractions()                                         // 7
        resolveWaterPackets()                                                // 8
        applyRefill()                                                        // 9
        applyExposureDamage()                                                // 10
        applyHeatRules()                                                     // 11
        if (tick % FIRE_SPREAD_PERIOD == 0) runFireSpread()                  // 12
        resolveProtectedAssets()                                             // 13
        evaluateEndConditions()                                              // 14

        if (rover.shieldTicksRemaining > 0) rover.shieldTicksRemaining--     // 15

        lastEvents = events.toList()                                         // 16
        onTick?.invoke(this)
    }

    // ---- step 7: contact interactions (§8.3 step 5) ----
    private fun resolveContactInteractions() {
        // §3.4: when the rover finishes a successful MOVE on a WAITING
        // civilian's cell, rescue happens automatically in this phase; the
        // civilian becomes RESCUED and leaves the grid. Exit/refill occupancy
        // is resolved from the rover's current cell by later steps.
        if (!roverMovedThisTick) return
        for (c in civilians) {
            if (c.status != CivilianStatus.WAITING) continue
            if (c.x != rover.x || c.y != rover.y) continue
            c.rescue()
            addEvent("CIVILIAN #${c.id} RESCUED at ${c.x},${c.y}")
        }
    }

    internal fun noteRoverMoved() { roverMovedThisTick = true }

    // ---- step 8: water packets (§8.3 step 2) ----
    private fun resolveWaterPackets() {
        val kept = ArrayList<WaterPacket>()
        for (p in packets) {
            if (p.process(tick, this)) kept.add(p)
        }
        packets.clear()
        packets.addAll(kept)
    }

    /** §3.6: created on the SHOOT completion tick in the cell in front of the
     *  rover, when that cell is in bounds and not blocked by a wall. Walls and
     *  the boundary consume a packet; civilians, exits, refills and the rover
     *  do not. */
    internal fun spawnWaterPacket(shooter: Rover) {
        val x = shooter.x + dirDx(shooter.facing)
        val y = shooter.y + dirDy(shooter.facing)
        if (!inBounds(x, y)) {
            addEvent("PACKET BLOCKED BOUNDARY")
            return
        }
        if (layout.wallBetween(shooter.x, shooter.y, x, y)) {
            addEvent("PACKET BLOCKED EDGE ${shooter.x},${shooter.y} -> $x,$y")
            return
        }
        if (terrainAt(x, y) == TERRAIN_WALL) {
            addEvent("PACKET BLOCKED WALL $x,$y")
            return
        }
        packets.add(WaterPacket(x, y, shooter.facing, PACKET_LIFE, spawnTick = tick))
        addEvent("PACKET -> $x,$y")
    }

    /** Test/diagnostic injection so packet life can be exercised directly. */
    internal fun injectPacket(packet: WaterPacket) { packets.add(packet) }

    /** A packet hitting this cell removes [PACKET_FIRE_REDUCE] and is consumed. */
    internal fun hitFireWithPacket(x: Int, y: Int, packet: WaterPacket): Boolean {
        val intensity = fireAt(x, y)
        if (intensity <= 0) return false
        setFire(x, y, intensity - PACKET_FIRE_REDUCE)
        addEvent("FIRE -${PACKET_FIRE_REDUCE} at $x,$y -> ${fireAt(x, y)}")
        return true
    }

    fun packetCount(): Int = packets.size
    fun packetsSnapshot(): List<WaterPacket> = packets.toList()
    fun hasWaterPacketAt(x: Int, y: Int): Boolean = packets.any { it.x == x && it.y == y }

    // ---- step 9: refill (§8.3 step 2) ----
    private fun applyRefill() {
        // §3.2: a refill cell restores 10 water after each resolution tick
        // occupied, capped at 100.
        if (terrainAt(rover.x, rover.y) != TERRAIN_REFILL) return
        val before = rover.water
        rover.water = (rover.water + WATER_REFILL).coerceAtMost(WATER_MAX)
        if (rover.water > before) addEvent("REFILL +${rover.water - before}")
    }

    // ---- step 10: fire exposure damage (§8.3 step 3) ----
    private fun applyExposureDamage() {
        // §4.5: after rover/water actions, before the five-tick spread step.
        // Uses the fire state AFTER water resolution, so a packet that
        // extinguishes a cell this tick prevents exposure damage on it.
        val roverFire = fireAt(rover.x, rover.y)
        if (roverFire > 0 && rover.alive) {
            if (rover.shielded) {
                addEvent("SHIELD BLOCKS FIRE ${roverFire} AT ${rover.x},${rover.y}")
            } else {
                val dmg = fireExposureDamage(roverFire)
                rover.applyDamage(dmg)
                addEvent("ROVER FIRE DAMAGE -$dmg (fire $roverFire)")
            }
        }
        // §3.4: civilians have no shield and always take exposure damage.
        for (c in civilians) {
            if (!c.onGrid) continue
            val f = fireAt(c.x, c.y)
            if (f <= 0) continue
            val dmg = fireExposureDamage(f)
            c.damage(dmg)
            if (c.status == CivilianStatus.LOST) {
                addEvent("CIVILIAN #${c.id} LOST BY FIRE")
            } else {
                addEvent("CIVILIAN #${c.id} FIRE DAMAGE -$dmg (hp ${c.hp})")
            }
        }
    }

    // ---- step 11: heat cooling + overload (§8.3 step 2) ----
    private fun applyHeatRules() {
        if (!rover.alive) return
        // Shared cooling gate (RF8-PLATFORM-SPEC.md §6): cool by 2 only when no
        // heat action completed this tick AND no multi-tick physical op is still
        // in flight. A final MOVE tick or a dry SHOOT completion may cool.
        if (!rover.heatedThisTick && !rover.vm.inProgress) {
            rover.heat = (rover.heat - HEAT_COOL).coerceAtLeast(0)
        }
        // Overload burns 2 HP/sec at 10 ticks/sec, accumulated fractionally in
        // exact milli-HP and applied in whole HP. The shield does NOT block it.
        if (rover.heat > HEAT_DAMAGE_THRESHOLD) {
            rover.heatOverloadMilli += HEAT_OVERLOAD_PER_TICK_MILLI
            val whole = rover.heatOverloadMilli / 1000
            if (whole > 0) {
                rover.heatOverloadMilli -= whole * 1000
                rover.applyDamage(whole)
                addEvent("ROVER OVERLOAD -$whole")
            }
        }
    }

    // ---- step 12: growth + spread (§4.1-§4.4, §8.3 step 7) ----
    private fun runFireSpread() {
        // §4.1: the snapshot is the set of burning cells at the START of this
        // step, traversed row-major. A cell ignited by this step does not grow
        // or spread until a later step.
        val snapshot = ArrayList<Int>()
        for (i in 0 until CELLS) if (fire[i] > 0) snapshot.add(i)

        val bag = spreadDirectionBag(descriptor.windDir, descriptor.windForce)
        for (i in snapshot) {
            val x = indexX(i)
            val y = indexY(i)

            // §4.2 growth before attempting spread, clamped to 100.
            val grown = fire[i] + fireGrowthFor(terrainAt(x, y))
            setFire(x, y, grown)

            // §4.4: exactly two spread bytes per snapshot cell, even when every
            // neighbour is blocked, so geometry cannot shift the RNG stream.
            val directionByte = spreadRng.nextByte()
            val chanceByte = spreadRng.nextByte()

            val chosen = bag[directionByte % bag.size]
            val nx = x + dirDx(chosen)
            val ny = y + dirDy(chosen)
            if (!inBounds(nx, ny)) continue
            if (layout.wallBetween(x, y, nx, ny)) continue
            val target = terrainAt(nx, ny)
            val threshold = spreadThresholdFor(target, chosen, descriptor.windDir, descriptor.windForce)
            if (threshold <= 0) continue                    // wall / refill / off-grid
            if (!terrainBurnable(target)) continue
            val j = cellIndex(nx, ny)
            if (fire[j] > 0) continue                       // already burning: unchanged
            if (chanceByte < threshold) {
                fire[j] = IGNITION_INTENSITY
                addEvent(
                    "FIRE IGNITES $nx,$ny ${terrainName(target)} from $x,$y " +
                        "dir=${TextGrid.dirName(chosen)} chance=$chanceByte<$threshold",
                )
            }
        }
    }

    // ---- step 13: protected assets (§8.3 step 6 semantics, §3.2) ----
    private fun resolveProtectedAssets() {
        // §3.2: a protected asset fails when its fire intensity reaches 100.
        // Failure is recorded regardless of objectives; only a configured
        // PROTECT_ASSETS objective turns it into a mission failure (§7.4).
        for ((x, y) in layout.protectedCells()) {
            val i = cellIndex(x, y)
            if (fire[i] >= FIRE_MAX && failedAssets.add(i)) {
                addEvent("PROTECTED ASSET FAILED $x,$y")
            }
        }
    }

    // ---- step 14: end conditions + §7.4 precedence (§8.3 step 6) ----
    private fun evaluateEndConditions() {
        if (finished) return
        val m = descriptor.objectives
        when {
            vmFaultThisTick -> finishWith(RunStatus.FAILURE, FailureReason.FIRMWARE_FAULT)
            rover.hp <= 0 -> finishWith(RunStatus.FAILURE, FailureReason.ROVER_DESTROYED)
            Objectives.has(m, Objectives.RESCUE_ALL) && requiredLost() > 0 ->
                finishWith(RunStatus.FAILURE, FailureReason.CIVILIAN_LOST)
            Objectives.has(m, Objectives.PROTECT_ASSETS) && assetsLost() > 0 ->
                finishWith(RunStatus.FAILURE, FailureReason.ASSET_LOST)
            objectivesSatisfied() -> finishWith(RunStatus.SUCCESS, null)
            // Hard timeLimit, or the §13.4 safety cap when a mission sets none.
            tick >= (descriptor.timeLimit ?: MAX_TICKS) ->
                finishWith(RunStatus.FAILURE, FailureReason.TIME_EXPIRED)
        }
    }

    private fun finishWith(outcome: RunStatus, reason: FailureReason?) {
        finished = true
        status = outcome
        failureReason = reason
        addEvent(if (outcome == RunStatus.SUCCESS) "MISSION SUCCESS" else "MISSION FAILURE $reason")
    }

    /**
     * §3.3 derived smoke — derived from fire, never simulated:
     * `min(255, LOCAL_FIRE + sum(W/N/E/S neighbour intensities) / 2)`.
     * Integer division truncates; off-grid neighbours (and walls, which never
     * burn) contribute zero. Informational only: smoke does not hide targets,
     * block packets, block movement, or stop burning (§3.3, §6.1).
     */
    fun smokeAt(x: Int, y: Int): Int {
        var neighbours = 0
        for ((dx, dy) in NEIGHBOUR_OFFSETS) {
            val nx = x + dx
            val ny = y + dy
            if (inBounds(nx, ny)) neighbours += fireAt(nx, ny)
        }
        return (fireAt(x, y) + neighbours / 2).coerceAtMost(SMOKE_MAX)
    }

    // ---- statistics ----
    fun roverOnExit(): Boolean = terrainAt(rover.x, rover.y) == TERRAIN_EXIT
    fun fireCellsBurning(): Int = fire.count { it > 0 }
    fun fireIntensitySum(): Int = fire.sum()

    // ---- civilian statistics (§3.4/§6.3) ----
    fun civiliansWaiting(): Int = civilians.count { it.status == CivilianStatus.WAITING }
    fun civiliansRescued(): Int = civilians.count { it.status == CivilianStatus.RESCUED }
    fun civiliansLost(): Int = civilians.count { it.status == CivilianStatus.LOST }
    fun requiredWaiting(): Int = civilians.count { it.required && it.status == CivilianStatus.WAITING }
    fun requiredLost(): Int = civilians.count { it.required && it.status == CivilianStatus.LOST }
    fun requiredRescued(): Int = civilians.count { it.required && it.status == CivilianStatus.RESCUED }
    fun rescuedCount(): Int = civilians.count { it.status == CivilianStatus.RESCUED }
    fun waitingCount(): Int = civilians.count { it.status == CivilianStatus.WAITING }
    fun lostCount(): Int = civilians.count { it.status == CivilianStatus.LOST }
    fun assetsIntact(): Int = layout.protectedCells().size - failedAssets.size
    fun requiredTotal(): Int = civilians.count { it.required }

    // ---- objective state (§7.3) ----
    fun fireAllOut(): Boolean = fireCellsBurning() == 0
    fun allRequiredRescued(): Boolean = requiredWaiting() == 0 && requiredLost() == 0
    fun assetsIntactObjective(): Boolean = assetsLost() == 0

    /**
     * §7.3: every configured objective must hold at the same tick. REACH_EXIT
     * combined with other objectives therefore completes only when the rover is
     * on the exit while everything else is also true.
     */
    fun objectivesSatisfied(): Boolean {
        val m = descriptor.objectives
        if (Objectives.has(m, Objectives.EXTINGUISH_ALL) && !fireAllOut()) return false
        if (Objectives.has(m, Objectives.RESCUE_ALL) && !allRequiredRescued()) return false
        if (Objectives.has(m, Objectives.REACH_EXIT) && !roverOnExit()) return false
        if (Objectives.has(m, Objectives.SURVIVE_UNTIL) && !(survivalElapsed() && rover.alive && assetsIntactObjective())) return false
        if (Objectives.has(m, Objectives.PROTECT_ASSETS) && !assetsIntactObjective()) return false
        return true
    }

    /** §6.3 MISSION port bit field. */
    fun missionBits(): Int {
        var bits = 0
        if (allRequiredRescued()) bits = bits or MISSION_BIT_CIVILIANS_RESCUED
        if (fireAllOut()) bits = bits or MISSION_BIT_FIRE_OUT
        if (roverOnExit()) bits = bits or MISSION_BIT_ON_EXIT
        if (descriptor.surviveUntil != null && survivalElapsed()) bits = bits or MISSION_BIT_SURVIVED
        if (requiredLost() > 0) bits = bits or MISSION_BIT_CIVILIAN_LOST
        if (assetsLost() > 0) bits = bits or MISSION_BIT_ASSET_LOST
        if (objectivesSatisfied()) bits = bits or MISSION_BIT_OBJECTIVES_MET
        if (finished) bits = bits or MISSION_BIT_FINISHED
        return bits
    }

    fun assetsLost(): Int = failedAssets.size
    fun survivalElapsed(): Boolean = descriptor.surviveUntil != null && tick >= descriptor.surviveUntil

    // ================= target selection (§6.1) =================
    /** Selected fire: smallest Manhattan distance, then highest intensity, then row-major. */
    fun selectedFire(): FireTarget? {
        var best: FireTarget? = null
        for (y in 0 until GRID) for (x in 0 until GRID) {
            val i = fire[cellIndex(x, y)]
            if (i <= 0) continue
            val t = FireTarget(x, y, i, manhattan(rover.x, rover.y, x, y))
            val b = best
            if (b == null ||
                t.dist < b.dist ||
                (t.dist == b.dist && t.intensity > b.intensity) ||
                (t.dist == b.dist && t.intensity == b.intensity && (t.y < b.y || (t.y == b.y && t.x < b.x)))
            ) best = t
        }
        return best
    }

    /** Selected civilian: nearest required WAITING, then lowest HP, then row-major, then stable id. */
    fun selectedCivilian(): Civilian? =
        civilians.filter { it.required && it.status == CivilianStatus.WAITING }
            .sortedWith(compareBy(
                { manhattan(rover.x, rover.y, it.x, it.y) },
                { it.hp },
                { it.y },
                { it.x },
                { it.id },
            ))
            .firstOrNull()

    /** Selected exit: nearest exit cell, row-major tie-break. */
    fun selectedExit(): Pair<Int, Int>? {
        val exits = layout.exitCells()
        if (exits.isEmpty()) return null
        return exits.minWith(compareBy(
            { manhattan(rover.x, rover.y, it.first, it.second) },
            { it.second },
            { it.first },
        ))
    }

    internal fun angleTo(x: Int, y: Int): Int = turnDeltaTo(rover.facing, rover.x, rover.y, x, y)

    /** §6.2 `AHEAD`: 1 for boundary/wall/protected asset ahead; civilians and fire do not block. */
    fun aheadBlocked(): Int {
        val nx = rover.x + dirDx(rover.facing)
        val ny = rover.y + dirDy(rover.facing)
        if (!inBounds(nx, ny)) return 1
        if (layout.wallBetween(rover.x, rover.y, nx, ny)) return 1
        return if (terrainPassable(terrainAt(nx, ny))) 0 else 1
    }

    // ================= port bus (§6.2/§6.3) =================
    /**
     * `IN <port>` for the FIRELINE profile. Reserved ports 15/31 and every
     * unknown number read 0 (RF8-PLATFORM-SPEC.md §4.2); no port read here
     * touches the spread RNG stream. A peripheral due at a later §8.3 step
     * answers its documented no-target value until that step lands.
     */
    fun sensor(port: Int): Int = when (port) {
        FirelinePorts.HP -> rover.hp
        FirelinePorts.DIR -> rover.facing
        FirelinePorts.RX -> rover.x
        FirelinePorts.RY -> rover.y
        FirelinePorts.DIST -> selectedFire()?.dist ?: NO_TARGET_DIST
        FirelinePorts.ANGLE -> selectedFire()?.let { angleTo(it.x, it.y) } ?: NO_TARGET_ANGLE
        FirelinePorts.FIRE -> 0                                       // reserved in ISA v1
        FirelinePorts.SHIELD -> if (rover.shieldTicksRemaining > 0) 1 else 0
        FirelinePorts.HEAT -> rover.heat
        FirelinePorts.ENEMY_HP -> selectedFire()?.intensity ?: NO_TARGET_ENEMY_HP
        FirelinePorts.RAND -> sensorRng.nextByte()                    // sensor stream only
        FirelinePorts.AHEAD -> aheadBlocked()
        FirelinePorts.PROFILE_PORT -> FirelinePorts.PROFILE
        FirelinePorts.PROFILE_REV_PORT -> FirelinePorts.PROFILE_REV
        FirelinePorts.CAPS_PORT -> FirelinePorts.CAPS
        FirelinePorts.FIRE_DIST -> selectedFire()?.dist ?: NO_TARGET_DIST
        FirelinePorts.FIRE_ANGLE -> selectedFire()?.let { angleTo(it.x, it.y) } ?: NO_TARGET_ANGLE
        FirelinePorts.FIRE_LEVEL -> selectedFire()?.intensity ?: NO_TARGET_LEVEL
        FirelinePorts.CIV_DIST -> selectedCivilian()?.let { manhattan(rover.x, rover.y, it.x, it.y) }
            ?: NO_TARGET_DIST
        FirelinePorts.CIV_ANGLE -> selectedCivilian()?.let { angleTo(it.x, it.y) } ?: NO_TARGET_ANGLE
        FirelinePorts.CIV_COUNT -> requiredWaiting()
        FirelinePorts.WATER -> rover.water
        FirelinePorts.LOCAL_FIRE -> fireAt(rover.x, rover.y)
        FirelinePorts.SMOKE -> smokeAt(rover.x, rover.y)
        FirelinePorts.WIND_DIR -> descriptor.windDir
        FirelinePorts.WIND_FORCE -> descriptor.windForce
        FirelinePorts.EXIT_DIST -> selectedExit()?.let { manhattan(rover.x, rover.y, it.first, it.second) }
            ?: NO_TARGET_DIST
        FirelinePorts.EXIT_ANGLE -> selectedExit()?.let { angleTo(it.first, it.second) } ?: NO_TARGET_ANGLE
        FirelinePorts.TILE -> terrainAt(rover.x, rover.y)
        FirelinePorts.MISSION -> missionBits()
        else -> 0                                                     // 15, 31 and any unknown port
    }

    // ================= result + canonical final-state hash (§7.5, §13.2) =================
    /** Civilians ordered by their stable mission-local ID (§13.2 canonical order). */
    fun civiliansOrdered(): List<Civilian> = civilians.sortedBy { it.id }

    /** Packets in spawn order (the list order water resolution maintains). */
    fun packetsOrdered(): List<WaterPacket> = packets.toList()

    /**
     * Canonical, ordered final-state text (§13.2): engine version, tick, rover
     * state, row-major terrain + fire arrays, ordered civilians, ordered water
     * packets, objective state and BOTH RNG stream states — built from explicit
     * fields only, never from JVM object hash codes.
     */
    fun canonicalState(): String = buildString {
        append("engine=").append(FIRELINE_ENGINE_VERSION).append('\n')
        append("tick=").append(tick).append('\n')
        append("rover=").append(rover.x).append(',').append(rover.y).append(',').append(rover.facing)
            .append(',').append(rover.hp).append(',').append(rover.heat).append(',').append(rover.water)
            .append(',').append(rover.shieldTicksRemaining).append(',').append(rover.heatOverloadMilli)
            .append(',').append(rover.shots).append(',').append(rover.shotsDry).append(',').append(rover.waterUsed)
            .append(',').append(rover.shieldsRaised).append(',').append(if (rover.destroyed) 1 else 0)
            .append('\n')
        append("terrain=").append(terrain.joinToString(",")).append('\n')
        append("verticalEdges=").append(layout.verticalEdgesRowMajor().joinToString("") { if (it) "1" else "0" }).append('\n')
        append("horizontalEdges=").append(layout.horizontalEdgesRowMajor().joinToString("") { if (it) "1" else "0" }).append('\n')
        append("fire=").append(fire.joinToString(",")).append('\n')
        append("civilians=")
        append(civiliansOrdered().joinToString(";") {
            "${it.id}:${it.x},${it.y},${it.hp},${it.status},${if (it.required) 1 else 0}"
        }).append('\n')
        append("packets=")
        append(packetsOrdered().joinToString(";") {
            "${it.spawnTick}:${it.x},${it.y},${it.dir},${it.ticksLeft}"
        }).append('\n')
        append("objective=").append(descriptor.objectives)
            .append(',').append(descriptor.timeLimit ?: -1)
            .append(',').append(descriptor.surviveUntil ?: -1)
            .append(',').append(missionBits())
            .append(',').append(if (finished) 1 else 0)
            .append(',').append(status?.name ?: "UNFINISHED")
            .append(',').append(failureReason?.name ?: "-")
            .append(',').append(failedAssets.sorted().joinToString("_")).append('\n')
        append("rng=").append(sensorRng.state).append(',').append(spreadRng.state).append('\n')
    }

    /**
     * FNV-1a 64-bit over [canonicalState] — a fixed, documented fingerprint that
     * is identical on every JVM (§13.2 forbids JVM object hash codes).
     */
    fun finalStateHash(): String {
        var h = FNV_OFFSET_BASIS
        for (c in canonicalState()) h = (h xor c.code.toLong()) * FNV_PRIME
        return h.toULong().toString(16).padStart(16, '0')
    }

    /**
     * §7.5 result of the current state. Called after the run ends it carries the
     * §7.4 status; called mid-run it reports FAILURE with no reason, which is a
     * diagnostic of an unfinished world, not an outcome.
     */
    fun snapshot(): FirelineResult = FirelineResult(
        status = status ?: RunStatus.FAILURE,
        failureReason = failureReason,
        engineVersion = FIRELINE_ENGINE_VERSION,
        missionId = descriptor.missionId,
        seed = descriptor.seed,
        tick = tick,
        roverHp = rover.hp,
        roverHeat = rover.heat,
        waterRemaining = rover.water,
        waterUsed = rover.waterUsed,
        civiliansRescued = civiliansRescued(),
        civiliansWaiting = civiliansWaiting(),
        civiliansLost = civiliansLost(),
        burningCells = fireCellsBurning(),
        fireIntensitySum = fireIntensitySum(),
        protectedAssetsIntact = assetsIntact(),
        protectedAssetsLost = assetsLost(),
        firmwareBytes = descriptor.firmware.size,
        firmwareInstructions = descriptor.firmware.size / robofight.isa.ISA.INSN_BYTES,
        finalStateHash = finalStateHash(),
        lastEvents = lastEvents,
    )

    // ================= headless helpers =================
    /** Advance until finished or the tick guard is reached (result lands at §8.3 step 9). */
    fun runUntilFinished(maxTicks: Int = MAX_TICKS) {
        var guard = 0
        while (!finished && tick < maxTicks && guard < maxTicks) {
            tickOnce()
            guard++
        }
    }

    fun render(): String = TextGrid.render(this)
}
