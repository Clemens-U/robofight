package robofight.fireline.app.mission

import robofight.assembler.assemble as rf8Assemble
import robofight.disasm.Disasm
import robofight.fireline.FailureReason
import robofight.fireline.FirelineResult
import robofight.fireline.FirelinePorts
import robofight.fireline.FirelineWorld
import robofight.fireline.HEAT_DAMAGE_THRESHOLD
import robofight.fireline.HEAT_MAX
import robofight.fireline.Mission
import robofight.fireline.MissionDescriptor
import robofight.fireline.RunStatus
import robofight.fireline.TextGrid
import robofight.fireline.WATER_MAX
import robofight.fireline.MAX_HP
import robofight.vm.Vm

/** Outcome of one STEP press. */
enum class StepOutcome { ADVANCED, FINISHED, GUARD_STOPPED, ALREADY_FINISHED }

/**
 * The app-side world session (PLAN 9.5: STEP is the SAME authority as the
 * headless runner). One [step] call is exactly one `FirelineWorld.tickOnce()`,
 * including the ticks a multi-tick VM instruction still has in flight - the
 * VM owns that state, the session never reimplements or fast-forwards it.
 *
 * The tick guard mirrors `FirelineRun.runWithGuard`: stepping stops when the
 * world is finished or `mission.maxTicks` is reached, never past it. RESET
 * builds a fresh world from the same mission descriptor, which was built from
 * the mission tuple plus the chosen firmware source, so nothing leaks across
 * resets (FIRELINE-DESIGN 12.1: the tuple is the authority, not world state).
 */
class MissionSession(val mission: Mission, val firmwareName: String, val firmwareSource: String) {

    val descriptor: MissionDescriptor = mission.descriptorForSource(firmwareSource)

    /** Re-assemble only for the source-line map (display); the VM image comes
     *  from [descriptor], which assembled the same source first. */
    private val asm = rf8Assemble(firmwareSource)
    private val sourceLines: List<String> = firmwareSource.lines()
    private val disasm: List<Disasm.Insn> = Disasm.disassemble(descriptor.firmware)

    private var world = FirelineWorld(descriptor)

    /** Per-tick log lines in the golden-trace shape `NNN <op> | <events ...>`. */
    val eventLog: MutableList<String> = ArrayList()
    private val recentEvents = ArrayDeque<String>()

    var guardReached = false
        private set

    val finished: Boolean get() = world.finished
    /** Read-only handle for the grid view; only [step] and [reset] mutate it. */
    val currentWorld: FirelineWorld get() = world
    val status: RunStatus? get() = world.status
    val failureReason: FailureReason? get() = world.failureReason
    val tick: Int get() = world.tick

    /** Advance exactly one world tick (multi-tick ops consume one STEP each). */
    fun step(): StepOutcome {
        if (world.finished) return StepOutcome.ALREADY_FINISHED
        if (world.tick >= mission.maxTicks) {
            guardReached = true
            return StepOutcome.GUARD_STOPPED
        }
        world.tickOnce()
        for (event in world.lastEvents) {
            recentEvents.addFirst("%03d %s".format(world.tick, event))
            if (recentEvents.size > 4) recentEvents.removeLast()
        }
        eventLog.add("%03d %s | %s".format(world.tick, world.lastOp, world.lastEvents.joinToString(" · ")))
        return if (world.finished) StepOutcome.FINISHED else StepOutcome.ADVANCED
    }

    /** Fresh world from the same descriptor; log and guard cleared, nothing carried over. */
    fun reset() {
        world = FirelineWorld(descriptor)
        eventLog.clear()
        recentEvents.clear()
        guardReached = false
    }

    /** Rolling, newest-first event tail; quiet ticks retain the previous events. */
    fun currentEvents(maxLines: Int): List<String> = recentEvents.take(maxLines)

    fun fullLogNewestFirst(): List<String> = eventLog.asReversed()

    /** Mid-run snapshot is diagnostic; after FINISHED it carries the §7.4 status. */
    fun result(): FirelineResult = world.snapshot()

    fun summary(): RunSummary =
        RunSummary.from(world, firmwareName, eventLog.takeLast(EVENT_TAIL).asReversed())

    /** All displayed telemetry read from the engine's own accessors, once per tick. */
    fun telemetry(): TelemetrySnapshot {
        val rover = world.rover
        val vm = rover.vm
        val monitor = vm.monitorInsn
        val knownInsn = monitor in disasm.indices
        val instructionText = if (knownInsn) disasm[monitor].text else "(no instruction)"
        val instructionLine = if (monitor in asm.lineOf.indices) asm.lineOf[monitor] + 1 else 0
        val context = if (instructionLine > 0) {
            val from = (instructionLine - CONTEXT_HALF).coerceAtLeast(1)
            val to = (instructionLine + CONTEXT_HALF).coerceAtMost(sourceLines.size)
            (from..to).map { it to sourceLines[it - 1] }
        } else {
            emptyList()
        }
        return TelemetrySnapshot(
            tick = world.tick,
            timeLimit = descriptor.timeLimit ?: mission.maxTicks,
            fireDist = world.sensor(FirelinePorts.FIRE_DIST),
            fireLevel = world.sensor(FirelinePorts.FIRE_LEVEL),
            civCount = world.sensor(FirelinePorts.CIV_COUNT),
            water = rover.water,
            hp = rover.hp,
            heat = rover.heat,
            shieldTicks = rover.shieldTicksRemaining,
            windLabel = "${TextGrid.dirName(descriptor.windDir)} x${descriptor.windForce}",
            missionBitsHex = "$%02X".format(world.missionBits()),
            a = vm.a,
            x = vm.x,
            y = vm.y,
            spHex = "$%03X".format(vm.sp),
            pcHex = "$%03X".format(vm.pc),
            flagC = vm.flags and Vm.FLAG_C != 0,
            flagZ = vm.flags and Vm.FLAG_Z != 0,
            flagN = vm.flags and Vm.FLAG_N != 0,
            flagV = vm.flags and Vm.FLAG_V != 0,
            lastOp = world.lastOp,
            executing = vm.inProgress,
            instructionText = instructionText,
            instructionLine = instructionLine,
            contextLines = context,
        )
    }

    companion object {
        /** Event tail persisted with a result (12.1 plain data, bounded). */
        const val EVENT_TAIL = 24

        /** RUN pacing: ~3 ticks per second; the world, never the UI, decides state. */
        const val RUN_TICK_MS = 300L

        /** Source context lines shown per side of the current instruction. */
        const val CONTEXT_HALF = 2

        /** Constants echoed into the UI as engine values, never retyped numbers. */
        const val HEAT_THRESHOLD = HEAT_DAMAGE_THRESHOLD
        const val WATER_LIMIT = WATER_MAX
        const val HP_LIMIT = MAX_HP
        const val HEAT_LIMIT = HEAT_MAX
    }
}

/**
 * One tick's readout, assembled only from public engine accessors
 * (`world.sensor`, `Rover`, `Vm`, `world.missionBits`). The MISSION surface
 * formats these; it does not compute any value itself.
 */
data class TelemetrySnapshot(
    val tick: Int,
    val timeLimit: Int,
    val fireDist: Int,
    val fireLevel: Int,
    val civCount: Int,
    val water: Int,
    val hp: Int,
    val heat: Int,
    val shieldTicks: Int,
    val windLabel: String,
    val missionBitsHex: String,
    val a: Int,
    val x: Int,
    val y: Int,
    val spHex: String,
    val pcHex: String,
    val flagC: Boolean,
    val flagZ: Boolean,
    val flagN: Boolean,
    val flagV: Boolean,
    val lastOp: String,
    /** True while a multi-tick instruction is still in flight (Vm.inProgress). */
    val executing: Boolean,
    val instructionText: String,
    val instructionLine: Int,
    val contextLines: List<Pair<Int, String>>,
)
