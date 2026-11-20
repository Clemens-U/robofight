package robofight.fireline

import robofight.assembler.assemble
import robofight.disasm.Disasm

/*
 * W1 spike entry point (docs/FIRELINE-WEB.md §9): the hello-wasm page.
 *
 * Purpose is narrow and deliberate: prove that the REAL engine, compiled to
 * wasmJs, assembles RF-8 firmware and reproduces the recorded acceptance
 * anchors in the browser container. The anchors are the committed ones
 * (docs/web/fixtures/snapshots.json + the Milestone 7 record), never invented
 * numbers, so a failure here is a determinism failure, not a demo bug.
 *
 * This is wasm-only spike plumbing: it lives in wasmJsMain, so it is absent
 * from the jvm target the Android apps consume. It is replaced by the real
 * :fireline-web shell in W3.
 */

private const val ANCHOR_TICK = 12
private const val EXPECTED_TICK12_HASH = "113c566635ef3e13"
private const val EXPECTED_FINAL_HASH = "47e28d84c5d61220"
private const val EXPECTED_NEVER_HASH = "a64c17a9d85a6f75"

private val results = ArrayList<String>()

private fun check(name: String, ok: Boolean, actual: String) {
    results.add((if (ok) "OK   " else "FAIL ") + name.padEnd(26) + actual)
    if (!ok) results.add("   ^ ANCHOR MISMATCH on the wasm target")
}

fun main() {
    println("OPCODE FIRELINE // W1 wasm spike")
    println("engine anchors: mission 02 CORRIDOR, reference firmware")

    val mission = Missions.CORRIDOR
    val asm = assemble(mission.referenceSource)
    check("assembler", asm.ok,
        "errors=${asm.errors.size} bytes=${asm.code.size} insns=${asm.code.size / 3}")

    // Disassembler exercises the replaced hex formatting (hexPad) on wasm.
    val listing = Disasm.listing(asm.code)
    val firstLine = listing.lines().firstOrNull().orEmpty()
    check("disasm listing", firstLine.matches(Regex("^[0-9A-F]{4}  .+")), firstLine)

    // Mid-run anchor: exactly ANCHOR_TICK world ticks (the app's STEP).
    val world = FirelineWorld(mission.descriptor(asm.code))
    repeat(ANCHOR_TICK) { world.tickOnce() }
    check("tick", world.tick == ANCHOR_TICK, "tick=${world.tick}")
    check("rover at tick 12", world.rover.x == 2 && world.rover.y == 7,
        "x=${world.rover.x} y=${world.rover.y} dir=${world.rover.facing}")
    check("last op", world.lastOp == "MOVE (1/2)", "lastOp=${world.lastOp}")
    check("ports", world.sensor(FirelinePorts.FIRE_DIST) == 10 &&
        world.sensor(FirelinePorts.FIRE_LEVEL) == 35,
        "FIRE_DIST=${world.sensor(FirelinePorts.FIRE_DIST)} " +
            "FIRE_LEVEL=${world.sensor(FirelinePorts.FIRE_LEVEL)}")
    check("mid-run fingerprint", world.finalStateHash() == EXPECTED_TICK12_HASH,
        "hash=${world.finalStateHash()}")

    // Final anchors: runner path and step-by-step path must agree on wasm too.
    val runner = FirelineRun.runWithGuard(mission, mission.descriptorForSource(mission.referenceSource))
    val stepped = FirelineRun.runStepByStep(mission.descriptorForSource(mission.referenceSource))
    check("final tick/status", runner.tick == 102 && runner.status == RunStatus.SUCCESS,
        "tick=${runner.tick} status=${runner.status}")
    check("final fingerprint", runner.finalStateHash == EXPECTED_FINAL_HASH,
        "hash=${runner.finalStateHash}")
    check("runner == stepByStep", runner.finalStateHash == stepped.finalStateHash &&
        runner.tick == stepped.tick, "hash=${stepped.finalStateHash}")

    // Failing firmware anchor (mission 01 `never shoots`): TIME_EXPIRED at tick 60.
    val failing = Missions.NOZZLE_CHECK.failingSources[0].second
    val never = FirelineRun.runWithGuard(
        Missions.NOZZLE_CHECK, Missions.NOZZLE_CHECK.descriptorForSource(failing))
    check("failure anchor", never.tick == 60 && never.finalStateHash == EXPECTED_NEVER_HASH,
        "tick=${never.tick} reason=${never.failureReason} hash=${never.finalStateHash}")

    println()
    results.forEach { println(it) }
    val failed = results.any { it.contains("FAIL") || it.contains("ANCHOR MISMATCH") }
    println()
    println(if (failed) "W1 WASM RESULT: ANCHOR FAILURE" else "W1 WASM RESULT: ALL ANCHORS OK")
}
