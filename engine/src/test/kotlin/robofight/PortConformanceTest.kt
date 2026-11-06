package robofight

import robofight.assembler.assemble
import robofight.challenge.ChallengeFormat
import robofight.challenge.ChallengeFormat.ParseResult
import robofight.disasm.Disasm
import robofight.isa.ISA
import robofight.isa.MachineProfile
import robofight.isa.PortAccess
import robofight.isa.PortCatalog
import robofight.vm.Vm
import robofight.world.*
import java.io.File

/**
 * Port-catalog conformance suite (RF8-PLATFORM-SPEC.md §9 +
 * PLAN-RF8-MULTIAPP.md §6 Phase 2 required tests).
 *
 * Proves the shared, data-driven port catalog and numeric-port support:
 *  - ISA v1 opcodes frozen (§9.1 pinned snapshot) — Phase 2 changes none.
 *  - Numeric ports 0, 63, 64, 255 assemble; -1 and 256 fail (§9.3).
 *  - Every official symbolic port assembles to its allocated number (§9.4).
 *  - Port names and numbers are globally unique (§9.5).
 *  - Unknown IN returns zero and sets Z; unknown OUT is side-effect free
 *    (§9.6); unknown ports consume no RNG and do not alter heat (§9.7).
 *  - Unknown opcodes still fault and are rejected by pinned-code decode
 *    scans (§9.8); a pinned block containing `IN 200` passes the scan.
 *  - Arena reports PROFILE/PROFILE_REV/CAPS = 0 (§9.9; §5.2).
 *  - MANUAL.md §8.3 cheat sheet is in parity with the catalog (no second
 *    hand-written mapping, §4.3).
 *  - Assemble → disassemble → re-assemble round-trip for every named port,
 *    with a safe numeric fallback for unnamed numbers.
 *
 * Uses the shared check()/passed/failed counters of Tests.kt (same pattern
 * as RegistryGateTest). Run standalone as `robofight.PortConformanceTestKt`
 * or via the EngineTests JUnit facade.
 */

/** ISA v1 opcode snapshot (opcode:name:kind, ascending), pinned 2026-11-06.
 *  RF8-PLATFORM-SPEC.md §1.2/§3: these numbers/kinds are frozen for ISA v1;
 *  any change here is an ISA revision, not a feature. */
private const val ISA_V1_SNAPSHOT =
    "00:MOV A,#n:1;01:MOV A,[a]:3;02:MOV A,X:2;03:MOV A,Y:2;04:MOV A,(X):6;" +
    "05:MOV [a],A:3;06:MOV (X),A:6;07:MOV X,#n:1;08:MOV X,A:2;09:MOV X,Y:2;" +
    "0A:MOV Y,#n:1;0B:MOV Y,A:2;0C:MOV Y,X:2;0D:XCH A,X:0;0E:XCH A,Y:0;" +
    "0F:XCH A,(X):6;10:NOP:0;20:ADD  #n:1;21:ADD  X:2;22:ADD  Y:2;" +
    "23:ADD  (X):6;24:ADD  [a]:3;30:SUB  #n:1;31:SUB  X:2;32:SUB  Y:2;" +
    "33:SUB  (X):6;34:SUB  [a]:3;40:MUL  #n:1;41:MUL  X:2;42:MUL  Y:2;" +
    "43:MUL  (X):6;44:MUL  [a]:3;50:DIV  #n:1;51:DIV  X:2;52:DIV  Y:2;" +
    "53:DIV  (X):6;54:DIV  [a]:3;60:INC A:0;61:INC X:0;62:INC Y:0;" +
    "63:DEC A:0;64:DEC X:0;65:DEC Y:0;66:NEG A:0;67:NOT A:0;68:TST A:0;" +
    "69:TST X:0;6A:TST Y:0;70:AND  #n:1;71:AND  X:2;72:AND  Y:2;" +
    "73:AND  (X):6;74:AND  [a]:3;75:OR   #n:1;76:OR   X:2;77:OR   Y:2;" +
    "78:OR   (X):6;79:OR   [a]:3;7A:XOR  #n:1;7B:XOR  X:2;7C:XOR  Y:2;" +
    "7D:XOR  (X):6;7E:XOR  [a]:3;82:CMP  #n:1;83:CMP  X:2;84:CMP  Y:2;" +
    "85:CMP  (X):6;86:CMP  [a]:3;90:JMP:3;91:JZ:4;92:JNZ:4;93:JC:4;" +
    "94:JNC:4;95:JN:4;96:JNN:4;97:JG:4;98:JGE:4;99:JL:4;9A:JLE:4;9B:JE:4;" +
    "9C:JNE:4;A0:PUSH #n:1;A1:PUSH A:0;A2:PUSH X:0;A3:PUSH Y:0;A4:POP A:0;" +
    "A5:POP X:0;A6:POP Y:0;A7:CALL:3;A8:RET:0;B0:SHOOT:0;B1:TURN L:0;" +
    "B2:TURN R:0;B3:TURN #n:1;B4:MOVE:0;B5:SHIELD:0;B6:WAIT:0;B7:IN:5;B8:OUT:5"

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
    println("== port catalog conformance (RF8-PLATFORM-SPEC §4-5, §9) ==")

    // ---- §9.1: ISA v1 opcode table frozen (numbers + operand kinds) ----
    run {
        val snap = (0..255).filter { ISA.defined(it) }
            .joinToString(";") { "%02X:%s:%d".format(it, ISA.name(it), ISA.kind(it)) }
        check("ISA v1 opcode snapshot pinned (99 opcodes, numbers/kinds unchanged)",
            snap == ISA_V1_SNAPSHOT, "drifted opcode table")
        // Every defined opcode must also EXECUTE without an "undefined opcode"
        // fault (the VM dispatch and the ISA table must agree). 0x10 NOP was
        // defined but faulted (dispatch only covered 0x00..0x0F) — found and
        // fixed during Phase 2; this check keeps the two tables locked.
        var definedButFaulted = mutableListOf<Int>()
        val w = 0xB6.toByte()  // trailing WAIT so probes never fall off the end
        for (op in 0..255) {
            if (!ISA.defined(op)) continue
            val probe = when (ISA.kind(op)) {
                ISA.NONE -> byteArrayOf(op.toByte(), 0, 0, w, 0, 0)     // ends with WAIT
                ISA.IMM -> byteArrayOf(op.toByte(), 5, 0, w, 0, 0)
                ISA.REG -> byteArrayOf(op.toByte(), 0, 0, w, 0, 0)
                ISA.PORT -> byteArrayOf(op.toByte(), 0, 0, w, 0, 0)
                ISA.IND -> byteArrayOf(0x07, 0x20, 0, op.toByte(), 0, 0, w, 0, 0)  // MOV X,#32 then op
                ISA.ABS -> byteArrayOf(op.toByte(), 0x00, 0x01, w, 0, 0)           // [0x0100]/target in region
                ISA.REL -> byteArrayOf(op.toByte(), 0xFB.toByte(), 0, w, 0, 0)     // jump back to self
                else -> null
            } ?: continue
            val vm = Vm()
            vm.load(probe)
            vm.step(NullEnv)
            if (vm.fault && vm.lastOp.contains("undefined opcode"))
                definedButFaulted.add(op)
        }
        check("VM dispatch executes every defined opcode (no false 'undefined' fault)",
            definedButFaulted.isEmpty(), "faulted despite ISA-defined: ${definedButFaulted.map { "0x" + it.toString(16) }}")
        check("NOP executes as a 1-tick no-op (0x10)", run {
            val vm = Vm()
            vm.load(assemble("MOV A,#7\nNOP\nWAIT").code)
            vm.step(NullEnv); vm.step(NullEnv)
            vm.a == 7 && !vm.fault && vm.lastOp == "NOP" && ISA.cost(0x10) == 1
        })
    }

    // ---- catalog integrity: globally unique names + numbers (§9.5) ----
    run {
        val problems = PortCatalog.checkIntegrity()
        check("port catalog integrity (unique names/numbers, official range)",
            problems.isEmpty(), problems.toString())
        check("base ports 0..11 keep their shipped numbers",
            PortCatalog.baseNameToNumber() == mapOf(
                "HP" to 0, "DIR" to 1, "RX" to 2, "RY" to 3, "DIST" to 4,
                "ANGLE" to 5, "FIRE" to 6, "SHIELD" to 7, "HEAT" to 8,
                "ENEMY_HP" to 9, "RAND" to 10, "AHEAD" to 11,
            ), PortCatalog.baseNameToNumber().toString())
        check("discovery + FIRELINE allocation present (PROFILE/CAPS + 16..30)",
            PortCatalog.namedPorts().size == 30 &&
                PortCatalog.definition(12)?.name == "PROFILE" &&
                PortCatalog.definition(14)?.profile == MachineProfile.SHARED &&
                PortCatalog.definition(16)?.profile == MachineProfile.FIRELINE &&
                PortCatalog.definition(30)?.name == "MISSION" &&
                PortCatalog.definition(15) == null &&   // reserved: no symbol
                PortCatalog.definition(31) == null &&
                PortCatalog.definition(1)?.access == PortAccess.READ_WRITE,
            "named=${PortCatalog.namedPorts().size}")
    }

    // ---- §9.3: numeric port operand range 0..255 ----
    run {
        for (n in listOf(0, 63, 64, 255)) {
            val r = assemble("IN $n")
            check("numeric port IN $n assembles", r.ok && hexOf(r.code) == "b7${n.toString(16).padStart(2, '0')}00",
                if (r.ok) hexOf(r.code) else r.errors.toString())
        }
        for (n in listOf(15, 31)) {  // reserved numbers are still legal operands
            val r = assemble("IN $n")
            check("reserved numeric port IN $n assembles", r.ok && hexOf(r.code) == "b7${n.toString(16).padStart(2, '0')}00",
                if (r.ok) hexOf(r.code) else r.errors.toString())
        }
        check("OUT 31,A assembles (reserved number, write ignored at runtime)",
            assemble("OUT 31,A").let { it.ok && hexOf(it.code) == "b81f00" })
        for (bad in listOf("-1", "256")) {
            val r = assemble("IN $bad")
            check("port $bad is an assembler error", !r.ok, "accepted: ${hexOf(r.code)}")
        }
        check("unknown SYMBOL 'BOROUGH' stays an assembler error",
            !assemble("IN BOROUGH").ok)
    }

    // ---- §9.4 + round-trip: every official symbol assembles, disassembles,
    //      and re-assembles to identical bytes ----
    run {
        var roundTrips = 0
        var caseOk = true
        for (p in PortCatalog.namedPorts()) {
            val r = assemble("IN ${p.name}")
            if (!r.ok || hexOf(r.code) != "b7${p.number.toString(16).padStart(2, '0')}00") {
                check("IN ${p.name} assembles to port ${p.number}", false,
                    if (r.ok) hexOf(r.code) else r.errors.toString()); continue
            }
            val insn = Disasm.disassemble(r.code).first()
            if (insn.text != "IN ${p.name}") {
                check("IN ${p.name} disassembles symbolically", false, "text=${insn.text}"); continue
            }
            val re = assemble(insn.text)
            if (!re.ok || !re.code.contentEquals(r.code)) {
                check("IN ${p.name} re-assembles from disassembly", false, hexOf(re.code)); continue
            }
            roundTrips++
            // case-insensitive symbol lookup (§4.3)
            if (!assemble("IN ${p.name.lowercase()}").ok) caseOk = false
        }
        check("all 30 official symbols assemble/disassemble/re-assemble",
            roundTrips == PortCatalog.namedPorts().size, "round-tripped: $roundTrips")
        check("port symbol lookup is case-insensitive", caseOk)
        // OUT operand renders the symbol too
        val outInsn = Disasm.disassemble(assemble("OUT DIR,A").code).first()
        check("OUT DIR,A operand renders 'DIR'", outInsn.operand == "DIR", "operand=${outInsn.operand}")
        // unnamed numbers fall back to the plain numeric form, which stays
        // re-assemblable (IN 200 -> "IN 200" -> same bytes)
        val num = assemble("IN 200").code
        val numText = Disasm.disassemble(num).first().text
        check("unnamed port 200 disassembles to 'IN 200' and re-assembles",
            numText == "IN 200" && assemble(numText).code.contentEquals(num), "text=$numText")
    }

    // ---- §9.6: unknown IN reads zero and sets Z (pure VM, NullEnv) ----
    run {
        val vm = Vm()
        vm.load(assemble("MOV A,#9\nIN 200").code)
        vm.step(NullEnv); vm.step(NullEnv)
        check("IN 200 on NullEnv: A=0, Z flag set, no fault",
            vm.a == 0 && vm.flags and Vm.FLAG_Z != 0 && !vm.fault,
            "a=${vm.a} flags=${vm.flags} fault=${vm.fault}")
    }

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

    // ---- §9.8: pinned-code decode scans — IN 200 passes, undefined fails ----
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
