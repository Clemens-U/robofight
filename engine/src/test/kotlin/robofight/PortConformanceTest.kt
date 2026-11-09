package robofight

import robofight.assembler.assemble
import robofight.challenge.ChallengeFormat
import robofight.challenge.ChallengeFormat.ParseResult
import robofight.isa.PortCatalog
import robofight.vm.Vm
import robofight.world.*
import java.io.File

/**
 * Port-catalog conformance suite — Arena-world half (RF8-PLATFORM-SPEC.md §9
 * + PLAN-RF8-MULTIAPP.md §6 Phase 2 required tests).
 *
 * Phase 3 (§7) split the original suite in two: the machine-layer checks
 * (ISA v1 pinned snapshot, VM dispatch coverage, NOP timing, catalog
 * integrity/allocation, numeric-port range assembly, symbol round-trip,
 * case-insensitive lookup, numeric fallback, NullEnv IN 200, undefined-
 * opcode VM fault) moved to :rf8-core as plain JUnit 5 tests
 * (rf8-core/src/test/kotlin/robofight/Rf8CoreConformanceTest.kt).
 *
 * What stays here — everything that needs the Arena world or the challenge
 * codec:
 *  - Unknown ports in the ARENA environment: `IN 200` reads 0 fault-free,
 *    consumes no RNG and leaves heat untouched (§9.6/§9.7); `OUT 200,A` is
 *    side-effect free except for normal execution.
 *  - Arena reports PROFILE/PROFILE_REV/CAPS = 0, also through real firmware
 *    (§9.9; §5.2).
 *  - Pinned-code decode scans (§9.8): a block containing `IN 200` passes, a
 *    block with an undefined opcode is rejected with E_OPCODES.
 *  - MANUAL.md §8.3 cheat sheet is in parity with the catalog (no second
 *    hand-written mapping, §4.3).
 *
 * Uses the shared check()/passed/failed counters of Tests.kt (same pattern
 * as RegistryGateTest). Run standalone as `robofight.PortConformanceTestKt`
 * or via the EngineTests JUnit facade.
 */

private fun hexOf(b: ByteArray): String =
    b.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

/** Locate the repo root (tests run with cwd = repo root or engine/). */
private fun findRepoRoot(): File? {
    var dir: File? = File("").absoluteFile
    for (hop in 0 until 5) {
        val d = dir ?: return null
        if (File(d, "settings.gradle.kts").isFile && File(d, "MANUAL.md").isFile) return d
        dir = d.parentFile
    }
    return null
}

/** Structurally complete payload (mirrors RegistryGateTest), stamped with
 *  the CURRENT engine version, layout 0x00 (OPEN), slots per mode. */
private fun scanPayload(fwA: ByteArray, slots: Int): String {
    val out = ArrayList<Byte>()
    out.add(1); out.add(ChallengeFormat.ENGINE_VERSION.toByte()); out.add(0)
    out.add(slots.toByte()); out.add(0)                         // fmt, eng, layout, slots, flags
    out.add(0x78); out.add(0x56); out.add(0x34); out.add(0x12)  // seed 0x12345678 LE
    out.add(3); out.add(0); out.add(0)                          // mode TIMED-200, spawn SEPARATE, hp 100/100
    out.add((fwA.size and 0xFF).toByte()); out.add(((fwA.size shr 8) and 0xFF).toByte())
    for (b in fwA) out.add(b)
    out.add(0); out.add(0)                                      // fwB len 0 (slot B open)
    val crc = ChallengeFormat.crc16(out.toByteArray())
    out.add((crc and 0xFF).toByte()); out.add(((crc shr 8) and 0xFF).toByte())
    return ChallengeFormat.PREFIX + ChallengeFormat.base32Encode(out.toByteArray())
}

/** A two-bot world: subject bot + a far-away looping decoy (a lone bot
 *  would auto-end the world after tick 1). Returns (world, subject). */
private fun worldWithSubject(firmware: String): Pair<World, Bot> {
    val w = World(Arena.OPEN)
    val subject = Bot("P", 'P', 1, 2, 2, DIR_E, firmware)
    val decoy = Bot("D", 'D', 2, 18, 18, DIR_W, "loop: JMP loop")
    w.add(subject); w.add(decoy)
    return w to subject
}

fun main() {
    println("== port catalog conformance, Arena half (RF8-PLATFORM-SPEC §5, §9.6-§9.9) ==")

    // ---- §9.6/§9.7 + §9.9: Arena environment behavior for unknown and
    //      discovery ports ----
    run {
        // IN 200 reads 0 in Arena without fault; then IN RAND must still read
        // the SAME first seeded draw as a world that never touched port 200
        // (unknown ports consume no RNG), with heat untouched.
        val (w1, b1) = worldWithSubject("IN 200\nIN RAND\nloop: JMP loop")
        w1.tickOnce()
        val aAfterUnknown = b1.vm.a
        val heatAfterUnknown = b1.heat
        val faultAfterUnknown = b1.vm.fault
        w1.tickOnce()
        val randAfterUnknown = b1.vm.a

        val (w2, b2) = worldWithSubject("NOP\nIN RAND\nloop: JMP loop")
        w2.tickOnce(); w2.tickOnce()
        val randReference = b2.vm.a

        check("IN 200 in Arena: A=0, no fault, heat untouched",
            aAfterUnknown == 0 && !faultAfterUnknown && heatAfterUnknown == 0,
            "a=$aAfterUnknown fault=$faultAfterUnknown heat=$heatAfterUnknown")
        check("unknown IN consumes no RNG (RAND draw unchanged)",
            randAfterUnknown == randReference,
            "after-200=$randAfterUnknown reference=$randReference")

        // Discovery ports in Arena read 0 (Arena IS compatibility profile 0).
        check("Arena PROFILE/PROFILE_REV/CAPS read 0",
            b1.portIn(12) == 0 && b1.portIn(13) == 0 && b1.portIn(14) == 0,
            "got ${b1.portIn(12)}/${b1.portIn(13)}/${b1.portIn(14)}")
        // ...including through real firmware.
        val (w3, b3) = worldWithSubject("IN PROFILE\nIN CAPS\nloop: JMP loop")
        w3.tickOnce()
        val profileA = b3.vm.a
        w3.tickOnce()
        check("IN PROFILE / IN CAPS execute fault-free and read 0 in Arena",
            profileA == 0 && b3.vm.a == 0 && !b3.vm.fault,
            "profile=$profileA caps=${b3.vm.a}")

        // OUT 200,A changes no world or VM state except normal execution.
        val (w4, b4) = worldWithSubject("MOV A,#42\nOUT 200,A\nloop: JMP loop")
        w4.tickOnce()
        val facingBefore = b4.facing; val heatBefore = b4.heat
        val projBefore = w4.projectiles.size; val aBefore = b4.vm.a
        w4.tickOnce()
        check("OUT 200,A: facing/heat/projectiles/A unchanged, no fault",
            b4.facing == facingBefore && b4.heat == heatBefore &&
                w4.projectiles.size == projBefore && b4.vm.a == aBefore &&
                !b4.vm.fault && b4.vm.lastOp == "OUT port200",
            "facing=${b4.facing}/$facingBefore heat=${b4.heat} proj=${w4.projectiles.size} a=${b4.vm.a}/$aBefore lastOp=${b4.vm.lastOp}")
    }

    // ---- §9.8: pinned-code decode scans — IN 200 passes, undefined fails.
    //      (The pure-VM undefined-opcode fault also lives in :rf8-core.) ----
    run {
        val withUnknownPort = scanPayload(assemble("IN 200\nNOP").code, 0b10)  // A pinned, B open
        val r1 = ChallengeFormat.parse(withUnknownPort)
        check("pinned block containing IN 200 passes the decode scan (Ok, unlocked)",
            r1 is ParseResult.Ok && (r1 as ParseResult.Ok).lock == null,
            "got ${r1::class.simpleName} ${(r1 as? ParseResult.Err)?.message}")

        val withUnknownOpcode = scanPayload(byteArrayOf(0xFF.toByte(), 0, 0, 0xB6.toByte(), 0, 0), 0b10)
        val r2 = ChallengeFormat.parse(withUnknownOpcode)
        check("pinned block with undefined opcode 0xFF is rejected",
            r2 is ParseResult.Err && (r2 as ParseResult.Err).message == ChallengeFormat.E_OPCODES,
            "got ${r2::class.simpleName} ${(r2 as? ParseResult.Err)?.message}")

        // The VM still faults on an undefined opcode at runtime (no silent no-op).
        val vm = Vm()
        vm.load(byteArrayOf(0xFF.toByte(), 0, 0))
        vm.step(NullEnv)
        check("undefined opcode faults the VM (not a no-op)", vm.fault, "fault=${vm.fault}")
    }

    // ---- §4.3: MANUAL.md §8.3 cheat-sheet table ↔ catalog parity ----
    run {
        val root = findRepoRoot()
        check("repo root found for MANUAL.md parity check", root != null)
        if (root != null) {
            val lines = File(root, "MANUAL.md").readLines()
            val start = lines.indexOfFirst { it.startsWith("### 8.3 I/O ports") }
            val end = lines.indexOfFirst { it.startsWith("### 8.4 ") }
            check("MANUAL.md §8.3 section found", start >= 0 && end > start, "start=$start end=$end")
            if (start >= 0 && end > start) {
                val rowRe = Regex("""^\|\s*`([A-Z][A-Z0-9_]*)`\s*\|\s*(\d+)\s*\|""")
                val manual = LinkedHashMap<String, Int>()
                for (line in lines.subList(start, end)) {
                    val m = rowRe.find(line) ?: continue
                    manual[m.groupValues[1]] = m.groupValues[2].toInt()
                }
                val catalog = PortCatalog.namedPorts().associate { it.name to it.number }
                val missing = catalog.filter { (name, num) -> manual[name] != num }
                val extra = manual.filter { (name, num) -> catalog[name] != num }
                check("MANUAL §8.3 lists every official port with its catalog number",
                    missing.isEmpty(), "missing/mismatched: $missing")
                check("MANUAL §8.3 lists no port outside the catalog",
                    extra.isEmpty() && manual.isNotEmpty(), "extra: $extra")
            }
        }
    }

    println("\n== RESULT: $passed passed, $failed failed ==")
    if (failed > 0) kotlin.system.exitProcess(1)
}
