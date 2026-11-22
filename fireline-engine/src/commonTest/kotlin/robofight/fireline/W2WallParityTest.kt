package robofight.fireline

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * E2 wall determinism gate (`PLAN-EDGE-WALLS.md` §7 step E2, §12): the four
 * wall-carrying fixture layouts of `docs/web/fixtures/parity_matrix.tsv` must
 * reproduce their recorded probe tick fingerprint, final tick, status, failure
 * reason and final fingerprint when the engine recomputes them from the pinned
 * canonical mission text, on `jvm` and on `wasmJs` from the same generated
 * [PARITY_FIXTURE_ROWS] and [PARITY_WALL_LAYOUT_TEXTS].
 *
 * Nothing here is hand-typed: every expectation is a fixture column, and every
 * layout is the engine's own canonical text. The `wall-open-lane` row is the
 * control of `wall-lane-move` with one edge wall removed, so the wall is the
 * only difference between the two fingerprints.
 */
class W2WallParityTest {

    private fun blockedLaneRow(): ParityFixtureRow =
        assertNotNull(
            PARITY_FIXTURE_ROWS.firstOrNull { it.scenario == "wall-lane-move" },
            "docs/web/fixtures/parity_matrix.tsv carries no wall-lane-move row",
        )

    private fun openLaneRow(): ParityFixtureRow =
        assertNotNull(
            PARITY_FIXTURE_ROWS.firstOrNull { it.scenario == "wall-open-lane" },
            "docs/web/fixtures/parity_matrix.tsv carries no wall-open-lane row",
        )

    /** Line index and column of a character offset inside a canonical layout text. */
    private fun positionOf(text: String, offset: Int): Pair<Int, Int> {
        var line = 0
        var column = 0
        for (i in 0 until offset) {
            if (text[i] == '\n') { line++; column = 0 } else column++
        }
        return line to column
    }

    @Test
    fun wallFixtureRowsArePinnedFromTheGenerator() {
        val rows = parityWallRows()
        assertEquals(PARITY_EXPECTED_WALL_ROWS, rows.size, "E2 wall rows in docs/web/fixtures/parity_matrix.tsv")
        assertEquals(PARITY_WALL_SCENARIOS, rows.map { it.scenario }, "wall scenario set (W2ParityFixtures.kt PARITY_WALL_SCENARIOS)")
        for (row in rows) {
            assertTrue(row.wallCount > 0, "${row.scenario}: wallCount column of parity_matrix.tsv must be positive")
            assertEquals(row.wallCount, parityWallGlyphCount(parityWallLayoutText(row)), "${row.scenario}: wall glyphs counted in the pinned layout text (W2ParityFixtures.kt PARITY_WALL_LAYOUT_TEXTS)")
            assertEquals(row.wallCount, parityMissionOf(row).layout.edgeCount(), "${row.scenario}: Layout.edgeCount of the reparsed pinned layout")
            assertTrue(row.roundtrip, "${row.scenario}: roundtrip column of parity_matrix.tsv")
            assertEquals(PARITY_PROBE_TICK, row.probeTick, "${row.scenario}: probe_tick column of parity_matrix.tsv")
            assertEquals(FIRELINE_ENGINE_VERSION, PARITY_ENGINE_VERSION, "${row.scenario}: fixture engine version vs this build")
        }
    }

    /**
     * The point of E2: the engine recomputes the recorded wall fingerprints from
     * the pinned canonical layout text on this target. A row that reproduces on
     * `jvm` and not on `wasmJs` is a container defect, not a fixture defect.
     */
    @Test
    fun everyWallRowReproducesItsRecordedFingerprintOnThisTarget() {
        for (row in parityWallRows()) {
            val mission = parityMissionOf(row)
            val descriptor = mission.descriptorForSource(paritySourceOf(row))
            val result = FirelineRun.runWithGuard(mission, descriptor)
            assertEquals(row.finalTick, result.tick, "${row.scenario}: final_tick (parity_matrix.tsv row ${row.scenario})")
            assertEquals(RunStatus.valueOf(row.status), result.status, "${row.scenario}: status (parity_matrix.tsv row ${row.scenario})")
            assertEquals(
                row.failureReason.let { if (it == "-") null else it }?.let { FailureReason.valueOf(it) },
                result.failureReason,
                "${row.scenario}: failure_reason (parity_matrix.tsv row ${row.scenario})",
            )
            assertEquals(row.finalHash, result.finalStateHash, "${row.scenario}: final_hash (parity_matrix.tsv row ${row.scenario})")

            val world = FirelineWorld(descriptor)
            repeat(row.probeTick) { world.tickOnce() }
            assertEquals(row.probeHash, world.finalStateHash(), "${row.scenario}: probe_hash at tick ${row.probeTick} (parity_matrix.tsv row ${row.scenario})")
        }
    }

    /**
     * A wall layout survives `fromCanonicalText(toCanonicalText(...))` byte
     * identically and still produces the same fingerprint, and the engine's own
     * pinned text is the canonical text of the layout it parses.
     */
    @Test
    fun wallLayoutCanonicalTextRoundTripsWithTheSameFingerprint() {
        for (row in parityWallRows()) {
            val pinned = parityWallLayoutText(row)
            val layout = Layout.fromCanonicalText(pinned)
            val canonical = layout.toCanonicalText()
            assertEquals(pinned, canonical, "${row.scenario}: pinned layout text is not the canonical text the engine emits (W2ParityFixtures.kt PARITY_WALL_LAYOUT_TEXTS)")
            val reparsed = Layout.fromCanonicalText(canonical)
            assertEquals(canonical, reparsed.toCanonicalText(), "${row.scenario}: canonical text round trip is not stable")
            assertTrue(layout.wallVertRowMajor().contentEquals(reparsed.wallVertRowMajor()), "${row.scenario}: vertical edge arrays differ after the round trip")
            assertTrue(layout.wallHorizRowMajor().contentEquals(reparsed.wallHorizRowMajor()), "${row.scenario}: horizontal edge arrays differ after the round trip")
            assertEquals(row.wallCount, reparsed.edgeCount(), "${row.scenario}: edgeCount after the round trip (parity_matrix.tsv row ${row.scenario})")

            val mission = parityMissionOf(row)
            val source = paritySourceOf(row)
            val asPinned = FirelineRun.runWithGuard(mission, mission.descriptorForSource(source)).finalStateHash
            val reparsedMission = Mission(
                id = mission.id,
                name = mission.name,
                briefing = mission.briefing,
                layout = reparsed,
                seed = mission.seed,
                objectives = mission.objectives,
                timeLimit = mission.timeLimit,
                surviveUntil = mission.surviveUntil,
                startingWater = mission.startingWater,
                windDir = mission.windDir,
                windForce = mission.windForce,
                referenceSource = mission.referenceSource,
                failingSources = emptyList(),
                maxTicks = mission.maxTicks,
            ).requireValid()
            val asReparsed = FirelineRun.runWithGuard(reparsedMission, reparsedMission.descriptorForSource(source)).finalStateHash
            assertEquals(asPinned, asReparsed, "${row.scenario}: fingerprint changed across the canonical text round trip")
            assertEquals(row.finalHash, asReparsed, "${row.scenario}: round-tripped fingerprint vs parity_matrix.tsv row ${row.scenario}")
        }
    }

    /**
     * `wall-open-lane` is `wall-lane-move` with the lane gate wall removed: the
     * two pinned layout texts differ in exactly one character, the `#` at
     * `WALL_VERT` row 2 char 5, and everything else about the two rows is equal.
     * The recorded fingerprints, probe fingerprints and statuses all differ.
     */
    @Test
    fun theRemovedWallIsTheOnlyDifferenceBetweenTheBlockedAndOpenLaneRows() {
        val blocked = blockedLaneRow()
        val open = openLaneRow()
        val blockedText = parityWallLayoutText(blocked)
        val openText = parityWallLayoutText(open)
        assertEquals(blockedText.length, openText.length, "layout texts differ in length, so they differ in more than one wall")
        val differing = blockedText.indices.filter { blockedText[it] != openText[it] }
        assertEquals(1, differing.size, "differing characters between the wall-lane-move and wall-open-lane layout texts")
        val offset = differing.first()
        assertNotEquals(EDGE_GLYPH_OPEN, blockedText[offset], "wall-lane-move must carry a wall glyph at the differing edge")
        assertEquals(EDGE_GLYPH_OPEN, openText[offset], "wall-open-lane must be open at that edge")
        val wallVertStart = blockedText.indexOf(SECTION_WALL_VERT)
        assertTrue(offset > wallVertStart, "the differing glyph is not inside the WALL_VERT section")
        val (line, column) = positionOf(blockedText, offset)
        val sectionLine = positionOf(blockedText, wallVertStart).first
        assertEquals(2, line - sectionLine - 1, "WALL_VERT row of the differing wall glyph")
        assertEquals(5, column, "WALL_VERT column of the differing wall glyph")
        assertEquals(blocked.seed, open.seed, "same seed")
        assertEquals(blocked.objectives, open.objectives, "same objectives")
        assertEquals(blocked.timeLimit, open.timeLimit, "same time limit")
        assertEquals(blocked.startingWater, open.startingWater, "same starting water")
        assertEquals(blocked.windDir, open.windDir, "same wind direction")
        assertEquals(blocked.windForce, open.windForce, "same wind force")
        assertEquals(blocked.maxTicks, open.maxTicks, "same tick guard")
        assertEquals(paritySourceOf(blocked), paritySourceOf(open), "same firmware source")
        assertEquals(blocked.firmwareLabel, open.firmwareLabel, "same firmware label")
        assertEquals(1, blocked.wallCount - open.wallCount, "exactly one wall fewer in the open control (parity_matrix.tsv wall_count)")
        assertNotEquals(blocked.finalHash, open.finalHash, "final_hash must move when the wall is removed")
        assertNotEquals(blocked.probeHash, open.probeHash, "probe_hash must move when the wall is removed")
        assertEquals(RunStatus.FAILURE, RunStatus.valueOf(blocked.status), "wall-lane-move status (parity_matrix.tsv row wall-lane-move)")
        assertEquals(RunStatus.SUCCESS, RunStatus.valueOf(open.status), "wall-open-lane status (parity_matrix.tsv row wall-open-lane)")
    }

    /** The wall gates are visible in the pinned golden traces, not only in state. */
    @Test
    fun wallEventsAppearInThePinnedGoldenTraces() {
        val sealed = assertNotNull(
            PARITY_GOLDEN_TRACES["wall-nozzle-seal"],
            "no pinned trace for wall-nozzle-seal (W2ParityFixtures.kt PARITY_GOLDEN_TRACES)",
        )
        assertTrue(sealed.any { it.contains("PACKET BLOCKED EDGE") }, "wall-nozzle-seal trace must carry the packet spawn edge gate (parity_traces.txt row wall-nozzle-seal)")
        val suppress = assertNotNull(
            PARITY_GOLDEN_TRACES["wall-lane-suppress"],
            "no pinned trace for wall-lane-suppress (W2ParityFixtures.kt PARITY_GOLDEN_TRACES)",
        )
        assertTrue(suppress.any { it.contains("PACKET SPENT EDGE") }, "wall-lane-suppress trace must carry the in-flight edge gate (parity_traces.txt row wall-lane-suppress)")
        val blocked = assertNotNull(PARITY_GOLDEN_TRACES["wall-lane-move"], "no pinned trace for wall-lane-move")
        val open = assertNotNull(PARITY_GOLDEN_TRACES["wall-open-lane"], "no pinned trace for wall-open-lane")
        assertEquals(blocked, open, "the blocked lane and its open control run the same instructions through tick 12")
        assertNotEquals(blockedLaneRow().probeHash, openLaneRow().probeHash, "the two lane rows diverge in state, not in instruction trace")
    }
}
