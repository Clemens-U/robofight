package robofight.fireline

import robofight.assembler.assemble

/**
 * Composable mission objectives (FIRELINE-DESIGN.md §7.3).
 *
 * At least one objective is required. `REACH_EXIT` may be combined with the
 * others; when combined, reaching the exit early does NOT complete the mission —
 * the rover must occupy the exit at the tick when every other configured
 * condition is also true.
 */
object Objectives {
    const val EXTINGUISH_ALL = 1 shl 0
    const val RESCUE_ALL = 1 shl 1
    const val REACH_EXIT = 1 shl 2
    const val SURVIVE_UNTIL = 1 shl 3
    const val PROTECT_ASSETS = 1 shl 4

    val ALL_FLAGS = intArrayOf(EXTINGUISH_ALL, RESCUE_ALL, REACH_EXIT, SURVIVE_UNTIL, PROTECT_ASSETS)

    fun has(mask: Int, flag: Int): Boolean = mask and flag != 0

    /** Unknown objective bits are a descriptor error, not a silently ignored mask. */
    fun unknownBits(mask: Int): Int = mask and ALL_FLAGS.fold(0) { acc, f -> acc or f }.inv()

    fun describe(mask: Int): String =
        ALL_FLAGS.filter { has(mask, it) }.joinToString(" | ") { name(it) }

    fun name(flag: Int): String = when (flag) {
        EXTINGUISH_ALL -> "EXTINGUISH_ALL"
        RESCUE_ALL -> "RESCUE_ALL"
        REACH_EXIT -> "REACH_EXIT"
        SURVIVE_UNTIL -> "SURVIVE_UNTIL"
        PROTECT_ASSETS -> "PROTECT_ASSETS"
        else -> "UNKNOWN($flag)"
    }
}

/**
 * Immutable mission descriptor — the deterministic tuple of
 * FIRELINE-DESIGN.md §7.1/§7.2: engine version, mission id, layout definition,
 * objective mask and rule values, seed, firmware binary.
 *
 * The §7.2 sketch is the "immutable equivalent" of this class; the layout is
 * carried explicitly because §7.1 lists the layout definition as part of the
 * deterministic tuple and the hash must cover it.
 */
class MissionDescriptor(
    val engineVersion: Int = FIRELINE_ENGINE_VERSION,
    val missionId: Int,
    val seed: Int,
    val objectives: Int,
    val timeLimit: Int? = null,
    val surviveUntil: Int? = null,
    val startingWater: Int = DEFAULT_STARTING_WATER,
    val windDir: Int = DIR_W,
    val windForce: Int = 0,
    val firmware: ByteArray,
    val layout: Layout,
) {
    /** Validate every range before constructing the world (§7.2). */
    fun validate(): List<String> {
        val problems = ArrayList<String>(layout.validate())
        if (engineVersion != FIRELINE_ENGINE_VERSION)
            problems.add("descriptor engine version $engineVersion != engine $FIRELINE_ENGINE_VERSION")
        if (missionId < 0) problems.add("mission id $missionId must be >= 0")
        if (objectives == 0) problems.add("at least one objective is required (§7.3)")
        val unknown = Objectives.unknownBits(objectives)
        if (unknown != 0) problems.add("unknown objective bits 0x${unknown.toString(16)}")
        if (timeLimit != null && timeLimit <= 0) problems.add("timeLimit $timeLimit must be > 0")
        if (Objectives.has(objectives, Objectives.SURVIVE_UNTIL) && surviveUntil == null)
            problems.add("SURVIVE_UNTIL objective requires a surviveUntil tick")
        if (surviveUntil != null && surviveUntil <= 0) problems.add("surviveUntil $surviveUntil must be > 0")
        if (Objectives.has(objectives, Objectives.REACH_EXIT) && layout.exitCells().isEmpty())
            problems.add("REACH_EXIT objective requires an exit cell in the layout")
        if (startingWater !in 0..WATER_MAX) problems.add("startingWater $startingWater outside 0..$WATER_MAX")
        if (windDir !in 0..3) problems.add("windDir $windDir outside 0..3")
        if (windForce !in 0..3) problems.add("windForce $windForce outside 0..3")
        if (Objectives.has(objectives, Objectives.PROTECT_ASSETS) && layout.protectedCells().isEmpty())
            problems.add("PROTECT_ASSETS objective but the layout has no protected asset")
        if (firmware.isEmpty()) problems.add("firmware is empty")
        if (firmware.size % 3 != 0) problems.add("firmware image must be a multiple of 3 bytes (got ${firmware.size})")
        if (firmware.size > robofight.isa.ISA.PROG_END - robofight.isa.ISA.PROG_BASE + 1)
            problems.add("firmware ${firmware.size} bytes exceeds the RF-8 program limit")
        return problems
    }

    fun requireValid(): MissionDescriptor {
        val problems = validate()
        if (problems.isNotEmpty()) throw IllegalArgumentException("invalid mission descriptor:\n" + problems.joinToString("\n"))
        return this
    }

    override fun equals(other: Any?): Boolean = other is MissionDescriptor &&
        other.engineVersion == engineVersion && other.missionId == missionId && other.seed == seed &&
        other.objectives == objectives && other.timeLimit == timeLimit && other.surviveUntil == surviveUntil &&
        other.startingWater == startingWater && other.windDir == windDir && other.windForce == windForce &&
        other.firmware.contentEquals(firmware) && other.layout === layout

    override fun hashCode(): Int {
        var h = engineVersion
        h = 31 * h + missionId; h = 31 * h + seed; h = 31 * h + objectives
        h = 31 * h + (timeLimit ?: 0); h = 31 * h + (surviveUntil ?: 0)
        h = 31 * h + startingWater; h = 31 * h + windDir; h = 31 * h + windForce
        h = 31 * h + firmware.contentHashCode()
        return h
    }

    override fun toString(): String =
        "MissionDescriptor(v$engineVersion, mission=$missionId, seed=$seed, obj=0x${objectives.toString(16)}, " +
            "time=$timeLimit, survive=$surviveUntil, water=$startingWater, wind=${windDir}x$windForce, fw=${firmware.size}B)"
}

/**
 * An authored mission (FIRELINE-DESIGN.md §9): immutable ID/name/briefing, the
 * layout it owns, its rules, plus the reference firmware that must succeed and
 * the documented intentionally failing firmware.
 */
class Mission(
    val id: Int,
    val name: String,
    val briefing: String,
    val layout: Layout,
    val seed: Int,
    val objectives: Int,
    val timeLimit: Int? = null,
    val surviveUntil: Int? = null,
    val startingWater: Int = DEFAULT_STARTING_WATER,
    val windDir: Int = DIR_W,
    val windForce: Int = 0,
    val referenceSource: String,
    /** Documented failing firmware variants: (label, source, expected failure reason). */
    val failingSources: List<Triple<String, String, FailureReason>> = emptyList(),
    /** Headless maximum-tick guard for this mission (§9). */
    val maxTicks: Int = MAX_TICKS,
) {
    fun validate(): List<String> {
        val problems = ArrayList<String>()
        if (id <= 0) problems.add("mission id $id must be > 0")
        if (name.isBlank()) problems.add("mission name must not be blank")
        if (briefing.isBlank()) problems.add("mission '$name' has no briefing")
        if (referenceSource.isBlank()) problems.add("mission '$name' has no reference firmware")
        problems.addAll(descriptorForSource(referenceSource).validate().map { "reference firmware: $it" })
        for ((label, src, _) in failingSources) {
            val r = assemble(src)
            if (!r.ok) problems.add("failing firmware '$label' does not assemble: ${r.errors.joinToString { it.msg }}")
        }
        return problems
    }

    fun requireValid(): Mission {
        val problems = validate()
        if (problems.isNotEmpty()) throw IllegalArgumentException("invalid mission $id '$name':\n" + problems.joinToString("\n"))
        return this
    }

    /** Assemble a source string into this mission's descriptor (convenience path). */
    fun descriptorForSource(source: String): MissionDescriptor {
        val r = assemble(source)
        check(r.ok) { "firmware failed to assemble:\n" + r.errors.joinToString("\n") { "line ${it.line}: ${it.msg}" } }
        return descriptor(r.code)
    }

    /** The headless authority path: descriptor built from this mission's rules + a firmware image. */
    fun descriptor(firmware: ByteArray): MissionDescriptor =
        MissionDescriptor(
            engineVersion = FIRELINE_ENGINE_VERSION,
            missionId = id,
            seed = seed,
            objectives = objectives,
            timeLimit = timeLimit,
            surviveUntil = surviveUntil,
            startingWater = startingWater,
            windDir = windDir,
            windForce = windForce,
            firmware = firmware,
            layout = layout,
        )

    override fun toString(): String = "%02d / $name".format(id)
}
