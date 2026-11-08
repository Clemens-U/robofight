package robofight.fireline

/**
 * A suppressant packet in flight (FIRELINE-DESIGN.md §3.6).
 *
 * Lifecycle, exactly as the design doc states:
 *  - created in the cell in front of the rover on the SHOOT completion tick;
 *  - on its creation tick it checks ONLY its spawn cell (it does not also
 *    advance a second cell that tick);
 *  - on later ticks it advances one cell during water resolution (step 8);
 *  - a wall or the grid boundary consumes it;
 *  - the first burning cell it contacts loses [PACKET_FIRE_REDUCE] intensity
 *    and the packet is consumed (intensity floors at 0);
 *  - maximum life is [PACKET_LIFE] water-resolution steps, counting the tick it
 *    was created on, so the packet list stays bounded (§13.4).
 *
 * Civilians, exits, refill cells and the rover do not consume a packet.
 */
class WaterPacket(
    var x: Int,
    var y: Int,
    val dir: Int,
    var ticksLeft: Int = PACKET_LIFE,
    val spawnTick: Int,
) {
    /**
     * One water-resolution step. Returns true when the packet survives this
     * tick and must be kept for the next one.
     */
    fun process(tick: Int, world: FirelineWorld): Boolean {
        if (tick == spawnTick) {
            // creation tick: check the spawn cell only, no advance
            if (world.hitFireWithPacket(x, y, this)) return false
        } else {
            val nx = x + dirDx(dir)
            val ny = y + dirDy(dir)
            if (!inBounds(nx, ny)) {
                world.addEvent("PACKET SPENT BOUNDARY $x,$y")
                return false
            }
            if (world.layout.wallBetween(x, y, nx, ny)) {
                world.addEvent("PACKET SPENT EDGE $x,$y -> $nx,$ny")
                return false
            }
            if (world.terrainAt(nx, ny) == TERRAIN_WALL) {
                world.addEvent("PACKET SPENT WALL $nx,$ny")
                return false
            }
            x = nx
            y = ny
            if (world.hitFireWithPacket(x, y, this)) return false
        }
        ticksLeft--
        if (ticksLeft <= 0) {
            world.addEvent("PACKET EXPIRED $x,$y")
            return false
        }
        return true
    }

    override fun toString(): String = "packet($x,$y,dir=${TextGrid.dirName(dir)},life=$ticksLeft,tick=$spawnTick)"
}
