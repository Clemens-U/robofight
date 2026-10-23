package robofight

import robofight.assembler.assemble
import robofight.challenge.ChallengeFormat
import robofight.challenge.ChallengeFormat.FightDescriptor
import robofight.challenge.ChallengeFormat.ParseResult
import robofight.challenge.ChallengeRun
import robofight.world.Arena
import robofight.world.Cell
import robofight.world.GRID
import robofight.world.Layout
import robofight.world.Presets

/**
 * Registry gate proof (PLAN-PRO §6 P1, CHALLENGE-CODES.md A1 two-app gate).
 *
 * Proves the compile-time flavor gate has an engine-side twin:
 *  (a) a valid code with Pro layout id 0x10 while UNREGISTERED parses as
 *      ParseResult.Ok with lock == E_PRO_ARENA (importable, RUN-locked),
 *  (b) after Arena.registerPro(stub 0x10) the SAME code parses Ok with
 *      lock == null and ChallengeRun.buildWorld runs it with the two
 *      preset bots — registry presence IS the gate,
 *  (c) registerPro rejects ids outside 0x10..0x1F and duplicates.
 *
 * Self-contained payload helpers (mirror CodecTests.kt's rawCode/makePayload
 * style). Uses the shared check()/passed/failed counters of Tests.kt so the
 * EngineTests JUnit shim's `failed == 0` assertion covers this suite too.
 * Registered LAST in the shim: it mutates the Arena registry.
 * Run standalone as `robofight.RegistryGateTestKt`.
 */
fun main() {
    println("== Registry gate tests (P1 two-app gate) ==")
    testLockedWhileUnregistered()
    testPlayableAfterRegisterPro()
    testRegisterProBoundaries()
    testGeneratorMechanics()
    println("\n== RESULT: $passed passed, $failed failed ==")
    if (failed > 0) kotlin.system.exitProcess(1)
}

// ---- payload helpers (self-contained; same v1 §2–§8 wire format) ----

private fun gateRawCode(payload: ByteArray): String =
    ChallengeFormat.PREFIX + ChallengeFormat.base32Encode(payload)

/** Structurally complete v1 payload (CRC included) with the given layout/slots/fw. */
private fun gatePayload(layout: Int, slots: Int, fwA: ByteArray, fwB: ByteArray): ByteArray {
    val out = ArrayList<Byte>()
    out.add(1); out.add(1); out.add(layout.toByte())
    out.add(slots.toByte()); out.add(0)                       // fmt, eng, layout, slots, flags
    out.add(0x78); out.add(0x56); out.add(0x34); out.add(0x12) // seed 0x12345678 LE
    out.add(3); out.add(0); out.add(0)                        // mode TIMED-200, spawn SEPARATE, hp 100/100
    out.add((fwA.size and 0xFF).toByte()); out.add(((fwA.size shr 8) and 0xFF).toByte())
    for (b in fwA) out.add(b)
    out.add((fwB.size and 0xFF).toByte()); out.add(((fwB.size shr 8) and 0xFF).toByte())
    for (b in fwB) out.add(b)
    val crc = ChallengeFormat.crc16(out.toByteArray())
    out.add((crc and 0xFF).toByte()); out.add(((crc shr 8) and 0xFF).toByte())
    return out.toByteArray()
}

/** An OPEN-like stub layout (empty terrain) for exercising registerPro. */
private fun stubLayout(id: Int, name: String): Layout = Layout(
    id = id, name = name,
    blocks = BooleanArray(GRID * GRID),
    vertEdge = BooleanArray((GRID - 1) * GRID),
    horizEdge = BooleanArray(GRID * (GRID - 1)),
    separateA = Cell(1, 1), separateB = Cell(18, 18),
    clashA = Cell(9, 10), clashB = Cell(10, 10),
)

// =====================================================================
//  (a) Pro id 0x10 while unregistered: importable, RUN-locked
// =====================================================================

private fun testLockedWhileUnregistered() {
    println("\n[a] 0x10 unregistered -> Ok with ARENA-NEEDS-PRO lock")
    check("sanity: 0x10 not in registry before registration", Arena.byId(0x10) == null)
    val r = ChallengeFormat.parse(gateRawCode(gatePayload(0x10, 0b11, ByteArray(0), ByteArray(0))))
    check("parse is Ok (importable)", r is ParseResult.Ok, "got ${r::class.simpleName}")
    if (r is ParseResult.Ok) {
        check("lock == E_PRO_ARENA", r.lock == ChallengeFormat.E_PRO_ARENA, "got '${r.lock}'")
        check("no warning", r.warning == null, "got '${r.warning}'")
        check("descriptor carries layoutId 0x10", r.desc.layoutId == 0x10)
    }
}

// =====================================================================
//  (b) Arena.registerPro(0x10): same code becomes playable end-to-end
// =====================================================================

private fun testPlayableAfterRegisterPro() {
    println("\n[b] registerPro(PRO-STUB 0x10) -> Ok without lock, fight builds")
    Arena.registerPro(stubLayout(0x10, "PRO-STUB"))
    check("registry now contains 0x10", Arena.byId(0x10)?.name == "PRO-STUB")
    check("all() grew to ${Arena.all().size}", Arena.all().size == 6)

    // Same code, pinned slots carrying the HUNTER/TURTLE preset firmware.
    val hunter = assemble(Presets.HUNTER)
    val turtle = assemble(Presets.TURTLE)
    check("preset firmware assembles", hunter.ok && turtle.ok)
    val r = ChallengeFormat.parse(gateRawCode(gatePayload(0x10, 0b00, hunter.code, turtle.code)))
    check("parse is Ok", r is ParseResult.Ok, "got ${r::class.simpleName}")
    if (r !is ParseResult.Ok) return
    check("lock == null after registration", r.lock == null, "got '${r.lock}'")

    val world = ChallengeRun.buildWorld(r.desc, null, null)
    check("buildWorld yields exactly two preset bots", world.bots.size == 2, "got ${world.bots.size}")
    check("bots named A/B on PRO-STUB", world.bots[0].name == "A" && world.bots[1].name == "B")
    val outcome = ChallengeRun.run(r.desc, null, null)
    check("fight resolves (< 2,000 ticks)", outcome.ticks in 1..2_000, "ticks=${outcome.ticks}")
    println("  -> HUNTER vs TURTLE on PRO-STUB 0x10: $outcome")
}

// =====================================================================
//  (c) registerPro hard gate: id range + duplicates
// =====================================================================

private fun testRegisterProBoundaries() {
    println("\n[c] registerPro rejects out-of-range and duplicate ids")
    fun throwsIA(f: () -> Unit): String? =
        try { f(); null } catch (e: IllegalArgumentException) { e.message }

    val e5 = throwsIA { Arena.registerPro(stubLayout(0x05, "BAD-05")) }
    check("id 0x05 rejected (IllegalArgumentException)", e5 != null, "no exception")
    val e20 = throwsIA { Arena.registerPro(stubLayout(0x20, "BAD-20")) }
    check("id 0x20 rejected (IllegalArgumentException)", e20 != null, "no exception")
    val eDup = throwsIA { Arena.registerPro(stubLayout(0x10, "DUP-10")) }
    check("duplicate id 0x10 rejected (IllegalArgumentException)", eDup != null, "no exception")
    val eFree = throwsIA { Arena.registerPro(stubLayout(0x00, "BAD-00")) }
    check("free id 0x00 rejected (range+duplicate)", eFree != null, "no exception")
    check("registry unchanged by rejections (still 6)", Arena.all().size == 6, "got ${Arena.all().size}")
    check("free five intact", listOf(0x00, 0x01, 0x02, 0x03, 0x04).all { Arena.byId(it) != null })
    if (e5 != null) println("  -> 0x05: $e5")
    if (e20 != null) println("  -> 0x20: $e20")
}

// =====================================================================
//  (d) seed-derived family entries (P2 machinery, CHALLENGE-CODES §7.4):
//      a registered Layout carrying a generator is played as f(id, seed);
//      fixed entries (0x10 stub above, the free five) are seed-invariant.
//      The generator here is a test-local stand-in for the Pro pack's real
//      generators (which live in the app's pro flavor source set).
// =====================================================================

/** Test-local seed-derived family: a point-symmetric block pair whose x-offset is seed-derived. */
private fun genFamilyLayout(seed: Int): Layout {
    val b = BooleanArray(GRID * GRID)
    val k = (seed and 7)                       // 0..7
    b[3 * GRID + (4 + k)] = true              // (4+k, 3)
    b[16 * GRID + (15 - k)] = true            // (15-k, 16) — point mirror
    return Layout(
        id = 0x11, name = "GEN-STUB",
        blocks = b,
        vertEdge = BooleanArray((GRID - 1) * GRID),
        horizEdge = BooleanArray(GRID * (GRID - 1)),
        separateA = Cell(1, 1), separateB = Cell(18, 18),
        clashA = Cell(9, 10), clashB = Cell(10, 10),
    )
}

private fun testGeneratorMechanics() {
    println("\n[d] generator family: played layout = f(id, seed); fixed layouts seed-invariant")
    val preview = genFamilyLayout(0).copy(generator = { genFamilyLayout(it) })
    Arena.registerPro(preview)
    check("family 0x11 registered with generator", Arena.byId(0x11)?.generator != null)
    check("registry grew to 7", Arena.all().size == 7, "got ${Arena.all().size}")

    // playableLayout: generator applied per seed
    val a1 = Arena.playableLayout(0x11, 5)!!
    val a2 = Arena.playableLayout(0x11, 5)!!
    val b1 = Arena.playableLayout(0x11, 6)!!
    check("same seed -> identical geometry (pure function)",
        a1.blocks.contentEquals(a2.blocks) && a1.clashA == a2.clashA)
    check("different seed -> different geometry", !a1.blocks.contentEquals(b1.blocks))
    check("seed 5 block at (9,3)", a1.isBlock(9, 3) && a1.isBlock(10, 16))
    check("seed 6 block at (10,3)", b1.isBlock(10, 3))
    check("family preview entry stays the registry instance", Arena.byId(0x11)!!.isBlock(4, 3))

    // Fixed entries are seed-invariant — the SAME object, whatever the seed.
    check("fixed 0x10 stub ignores seed", Arena.playableLayout(0x10, 1234) === Arena.byId(0x10))
    check("free arenas ignore seed", Arena.playableLayout(0x04, -99) === Arena.byId(0x04))
    check("unregistered 0x12 resolves null (locked preview unchanged)", Arena.playableLayout(0x12, 0) == null)

    // End-to-end: a v1 code on the family id fights on the SEEDED geometry.
    val hunter = assemble(Presets.HUNTER)
    val r = ChallengeFormat.parse(gateRawCode(gatePayload(0x11, 0b10, hunter.code, ByteArray(0))))
    check("family code parses Ok", r is ParseResult.Ok, "got ${r::class.simpleName}")
    if (r is ParseResult.Ok) {
        val seedA = r.desc.copy(seed = 0x0000_0005)
        val seedB = r.desc.copy(seed = 0x0000_0006)
        val wa = ChallengeRun.buildWorld(seedA, null, openBotStub())
        val wb = ChallengeRun.buildWorld(seedB, null, openBotStub())
        check("buildWorld(seed=5) plays f(id,5)", wa.layout.isBlock(9, 3) && wa.layout.isBlock(10, 16))
        check("buildWorld(seed=6) plays f(id,6)", wb.layout.isBlock(10, 3) && wb.layout.isBlock(9, 16))
        check("seeded layouts are distinct worlds", !wa.layout.blocks.contentEquals(wb.layout.blocks))
    }
}

private fun openBotStub(): robofight.world.Bot =
    robofight.world.Bot.fromBinary("B", 'B', 4, 0, 0, 0, assemble("loop: WAIT\nJMP loop\n").code)
