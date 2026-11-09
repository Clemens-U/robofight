package robofight

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import robofight.assembler.assemble
import robofight.disasm.Disasm
import robofight.isa.ISA
import robofight.isa.MachineProfile
import robofight.isa.PortAccess
import robofight.isa.PortCatalog
import robofight.vm.Env
import robofight.vm.Vm

/**
 * RF-8 core conformance suite (RF8-PLATFORM-SPEC.md §9 + PLAN-RF8-MULTIAPP.md
 * §6/§7): the machine-layer half of the former engine PortConformanceTest,
 * moved to :rf8-core in Phase 3 with identical assertions.
 *
 * Plain JUnit 5 tests — deliberately NOT the engine's check()/main() facade
 * pattern: :rf8-core must not depend on engine test classes, and the
 * exitProcess-on-fail idiom masks JUnit crashes (Gradle Test Executor dies
 * before the failing check is printed).
 *
 * Covered here (core-only):
 *  - ISA v1 opcode snapshot pinned (§9.1) + VM dispatch executes every
 *    defined opcode + NOP 0x10 is a 1-tick no-op.
 *  - Catalog integrity: unique names/numbers, base ports 0..11, discovery +
 *    FIRELINE allocation, reserved 15/31 have no symbol (§9.5).
 *  - Numeric ports 0/63/64/255 and reserved 15/31 assemble; -1/256 fail;
 *    unknown SYMBOLS fail (§9.3).
 *  - All 30 official symbols assemble/disassemble/re-assemble round-trip,
 *    case-insensitive lookup, numeric fallback for unnamed ports (§9.4).
 *  - Unknown IN on the null Env reads 0, sets Z, no fault (§9.6); the VM
 *    still faults on an undefined opcode (§9.8 runtime half).
 *
 * Arena-world behavior (World IN/OUT/RNG/heat purity, PROFILE reads), the
 * ChallengeFormat decode-scan payload tests and MANUAL.md §8.3 parity stay in
 * the engine's trimmed PortConformanceTest.kt.
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

/** Local null Env for pure-VM tests (all ports read 0, no side effects).
 *  Defined here — :rf8-core tests must not reach into engine test classes. */
private object NullEnv : Env {
    override fun portIn(port: Int) = 0
    override fun portOut(port: Int, value: Int) {}
    override fun heatLocked(op: Int) = false
    override fun shoot() {}
    override fun move() {}
    override fun shield() {}
    override fun turn(delta: Int) {}
}

private fun hexOf(b: ByteArray): String =
    b.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

private fun opcodeSnapshot(): String =
    (0..255).filter { ISA.defined(it) }
        .joinToString(";") { "%02X:%s:%d".format(it, ISA.name(it), ISA.kind(it)) }

class Rf8CoreConformanceTest {

    // ---- §9.1: ISA v1 opcode table frozen (numbers + operand kinds) ----
    @Test
    fun isaV1OpcodeSnapshotPinned() {
        val snap = opcodeSnapshot()
        assertTrue(
            snap == ISA_V1_SNAPSHOT,
            "drifted opcode table (99 opcodes, numbers/kinds must not change):\n$snap",
        )
    }

    @Test
    fun vmDispatchExecutesEveryDefinedOpcode() {
        // Every defined opcode must also EXECUTE without an "undefined opcode"
        // fault (the VM dispatch and the ISA table must agree). 0x10 NOP was
        // defined but faulted (dispatch only covered 0x00..0x0F) — found and
        // fixed during Phase 2; this check keeps the two tables locked.
        val definedButFaulted = mutableListOf<Int>()
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
        assertTrue(
            definedButFaulted.isEmpty(),
            "faulted despite ISA-defined: ${definedButFaulted.map { "0x" + it.toString(16) }}",
        )
    }

    @Test
    fun nopIsOneTickNoOp() {
        val vm = Vm()
        vm.load(assemble("MOV A,#7\nNOP\nWAIT").code)
        vm.step(NullEnv); vm.step(NullEnv)
        assertEquals(7, vm.a)
        assertTrue(!vm.fault)
        assertEquals("NOP", vm.lastOp)
        assertEquals(1, ISA.cost(0x10))
    }

    // ---- catalog integrity: globally unique names + numbers (§9.5) ----
    @Test
    fun portCatalogIntegrity() {
        val problems = PortCatalog.checkIntegrity()
        assertTrue(problems.isEmpty(), problems.toString())
    }

    @Test
    fun basePortsKeepShippedNumbers() {
        assertEquals(
            mapOf(
                "HP" to 0, "DIR" to 1, "RX" to 2, "RY" to 3, "DIST" to 4,
                "ANGLE" to 5, "FIRE" to 6, "SHIELD" to 7, "HEAT" to 8,
                "ENEMY_HP" to 9, "RAND" to 10, "AHEAD" to 11,
            ),
            PortCatalog.baseNameToNumber(),
        )
    }

    @Test
    fun discoveryAndFirelineAllocationPresent() {
        val detail = "named=${PortCatalog.namedPorts().size}"
        assertTrue(PortCatalog.namedPorts().size == 30, detail)
        assertEquals("PROFILE", PortCatalog.definition(12)?.name, detail)
        assertEquals(MachineProfile.SHARED, PortCatalog.definition(14)?.profile, detail)
        assertEquals(MachineProfile.FIRELINE, PortCatalog.definition(16)?.profile, detail)
        assertEquals("MISSION", PortCatalog.definition(30)?.name, detail)
        // reserved numbers: no symbol allocated
        assertNull(PortCatalog.definition(15), detail)
        assertNull(PortCatalog.definition(31), detail)
        assertEquals(PortAccess.READ_WRITE, PortCatalog.definition(1)?.access, detail)
    }

    // ---- §9.3: numeric port operand range 0..255 ----
    @Test
    fun numericPortsInLegalRangeAssemble() {
        for (n in listOf(0, 63, 64, 255)) {
            val r = assemble("IN $n")
            assertTrue(r.ok, "IN $n rejected: ${r.errors}")
            assertEquals("b7${n.toString(16).padStart(2, '0')}00", hexOf(r.code))
        }
        for (n in listOf(15, 31)) {  // reserved numbers are still legal operands
            val r = assemble("IN $n")
            assertTrue(r.ok, "IN $n rejected: ${r.errors}")
            assertEquals("b7${n.toString(16).padStart(2, '0')}00", hexOf(r.code))
        }
        val out = assemble("OUT 31,A")  // reserved number, write ignored at runtime
        assertTrue(out.ok, "OUT 31,A rejected: ${out.errors}")
        assertEquals("b81f00", hexOf(out.code))
    }

    @Test
    fun illegalPortOperandsAreAssemblerErrors() {
        for (bad in listOf("-1", "256")) {
            val r = assemble("IN $bad")
            assertTrue(!r.ok, "port $bad accepted: ${hexOf(r.code)}")
        }
        assertTrue(!assemble("IN BOROUGH").ok, "unknown symbol 'BOROUGH' accepted")
    }

    // ---- §9.4 + round-trip: every official symbol assembles, disassembles,
    //      and re-assembles to identical bytes ----
    @Test
    fun allOfficialSymbolsRoundTrip() {
        val ports = PortCatalog.namedPorts()
        for (p in ports) {
            val r = assemble("IN ${p.name}")
            assertTrue(r.ok, "IN ${p.name} rejected: ${r.errors}")
            assertEquals("b7${p.number.toString(16).padStart(2, '0')}00", hexOf(r.code),
                "IN ${p.name} bytes")
            val insn = Disasm.disassemble(r.code).first()
            assertEquals("IN ${p.name}", insn.text, "IN ${p.name} disassembly")
            val re = assemble(insn.text)
            assertTrue(re.ok, "IN ${p.name} re-assembly rejected: ${re.errors}")
            assertTrue(re.code.contentEquals(r.code),
                "IN ${p.name} re-assemble bytes: ${hexOf(re.code)}")
        }
    }

    @Test
    fun portSymbolLookupIsCaseInsensitive() {
        for (p in PortCatalog.namedPorts()) {
            assertTrue(assemble("IN ${p.name.lowercase()}").ok, "IN ${p.name.lowercase()} rejected")
        }
    }

    @Test
    fun outOperandRendersSymbol() {
        val outInsn = Disasm.disassemble(assemble("OUT DIR,A").code).first()
        assertEquals("DIR", outInsn.operand, "operand=${outInsn.operand}")
    }

    @Test
    fun unnamedNumericPortFallsBackToPlainNumber() {
        // unnamed numbers fall back to the plain numeric form, which stays
        // re-assemblable (IN 200 -> "IN 200" -> same bytes)
        val num = assemble("IN 200").code
        val numText = Disasm.disassemble(num).first().text
        assertEquals("IN 200", numText)
        assertTrue(assemble(numText).code.contentEquals(num), "text=$numText")
    }

    // ---- §9.6: unknown IN reads zero and sets Z (pure VM, NullEnv) ----
    @Test
    fun unknownInReadsZeroSetsZNoFault() {
        val vm = Vm()
        vm.load(assemble("MOV A,#9\nIN 200").code)
        vm.step(NullEnv); vm.step(NullEnv)
        assertEquals(0, vm.a)
        assertTrue(vm.flags and Vm.FLAG_Z != 0, "flags=${vm.flags}")
        assertTrue(!vm.fault, "fault=${vm.fault}")
    }

    // ---- §9.8 (runtime half): the VM still faults on an undefined opcode
    //      at runtime (no silent no-op). The pinned-code decode-scan half
    //      needs ChallengeFormat and stays in the engine suite. ----
    @Test
    fun undefinedOpcodeFaultsVm() {
        val vm = Vm()
        vm.load(byteArrayOf(0xFF.toByte(), 0, 0))
        vm.step(NullEnv)
        assertTrue(vm.fault, "fault=${vm.fault}")
    }

    // sanity: snapshot helper itself is exercised by the pinned test above
    @Test
    fun snapshotHasNinetyNineOpcodes() {
        assertNotNull(opcodeSnapshot())
        assertEquals(99, (0..255).count { ISA.defined(it) })
    }
}
