package robofight.fireline.web

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import robofight.fireline.Missions
import robofight.fireline.RunStatus
import robofight.fireline.WebInputLimits
import robofight.fireline.session.StepOutcome

/**
 * Gate G5 (file interop with `:fireline-app`) plus the G4 cross-container anchor,
 * measured on wasmJs against the bytes the Android app actually keeps.
 *
 * Where the fixture bytes come from (no hand-typed firmware here):
 *  - `PINNED_PERMALINKS["2-reference"]` was generated from the live authored
 *    engine sources (`PermalinkSources.kt` -> `permalink_sources.tsv` ->
 *    `permalink_fixture_gen.py`), and its `firmware_name` is exactly the store
 *    name `:fireline-app` writes for mission 02 (`CORRIDOR-REF.ASM`).
 *  - `BROWSER_SHARE_LINK` is the link the LIVE browser console put in its own
 *    address bar after COPY, during the W3 gate run on this branch (see
 *    docs/WEB-W3-RECORD.md, G5 section). The same run had imported the file pulled
 *    out of the installed app's own store (`fireline.db`, row 1), and Python's
 *    independent `gzip` module decompressed this link to those exact store bytes:
 *    375 characters, md5 `e80d319c4361b40d7be27971433065fe` on both sides.
 *
 * So each assertion below compares the wasm container against data that came from
 * the Android container, and the fingerprint it must reach is the committed matrix
 * anchor (`parity_matrix.json` row `2-reference`, engine 2): tick 102,
 * `d3ea47119fe3b101`.
 */
class W3InteropTest {

    private val model = ConsoleModel()
    private val reference = PINNED_PERMALINKS.first { it.scenario == "2-reference" }

    companion object {
        /** Fixture envelope alphabet only (see `W3PermalinkTest.decode64`). */
        private const val BASE64_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

        /** Captured live from the browser console's address bar (COPY button). */
        private const val BROWSER_SHARE_LINK: String =
            "FLW1-H4sIAAAAAAAAAwCSAW3-AQAAAAIAAAAAABBDT1JSSURPUi1SRUYuQVNNICAgICAgIFRVUk4gICBSCiAgICAgICBNT1ZFCiAgICAgICBNT1ZFCiAgICAgICBNT1ZFCiAgICAgICBNT1ZFCiAgICAgICBNT1ZFCiAgICAgICBNT1ZFCiAgICAgICBNT1ZFCiAgICAgICBNT1ZFCiAgICAgICBUVVJOICAgTAp3YWxrOiAgSU4gICAgIEZJUkVfRElTVAogICAgICAgSlogICAgIGZpbgogICAgICAgQ01QICAgICMxCiAgICAgICBKTEUgICAgZXh0CiAgICAgICBNT1ZFCiAgICAgICBKTVAgICAgd2FsawpleHQ6ICAgSU4gICAgIEZJUkVfTEVWRUwKICAgICAgIEpaICAgICBmaW4KICAgICAgIFNIT09UCiAgICAgICBKTVAgICAgZXh0CmZpbjogICBNT1ZFCiAgICAgICBNT1ZFCiAgICAgICBNT1ZFCiAgICAgICBXQUlUCiAgICAgICBKTVAgICAgZmluAQAA___r_JBzkgEAAA"

        /** `fireline.db` row 1: the exact store name the Android app uses. */
        private const val ANDROID_STORE_NAME: String = "CORRIDOR-REF.ASM"

        /** `parity_matrix.json` row `2-reference` (engine 2): the final anchor. */
        private const val MATRIX_FINAL_HASH: String = "d3ea47119fe3b101"

        /** `snapshots.json` `02-ref-t30` equivalent: the mid-run fingerprint the
         *  browser readout showed at tick 30 (`ConsoleModelTest` pins the same). */
        private const val TICK30_HASH: String = "0d7fa932"
    }

    private fun decode64(text: String): String {
        val out = ArrayList<Int>()
        var acc = 0
        var bits = 0
        for (ch in text) {
            if (ch == '=') break
            val v = BASE64_ALPHABET.indexOf(ch.toString())
            if (v < 0) throw IllegalArgumentException("fixture base64 has an unexpected character")
            acc = (acc shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.add((acc shr bits) and 0xFF)
            }
        }
        return out.map { it.toByte() }.toByteArray().decodeToString()
    }

    /**
     * G5, app -> web: the file name the Android store uses survives the import
     * naming rule (`Main.importFirmwareFile`), the imported text passes the web
     * input gate unchanged, and the wasm run of those bytes reaches the committed
     * matrix anchor (G4: tick 30 readout, tick 102 finish, `$d3ea4711`).
     */
    @Test
    fun androidStoreBytesImportThroughTheWebGateAndReachTheMatrixAnchor() {
        val storeSource = decode64(reference.sourceBase64)
        assertEquals(375, storeSource.length, "imported length is the length the Android store reports")
        assertEquals(ANDROID_STORE_NAME, reference.firmwareName, "fixture name is the Android store name")

        // Same normalization `Main.importFirmwareFile` applies to a picked file name.
        val fileName = ANDROID_STORE_NAME
        val normalized = WebLibrary.normalizeName(fileName.substringBeforeLast('.')) ?: WebLibrary.normalizeName(fileName)
        assertEquals(ANDROID_STORE_NAME, normalized, "import normalization returns the Android store name form")

        assertEquals(emptyList(), WebInputLimits.sourceProblems(storeSource), "imported bytes pass the web input gate")

        val draft = model.draftFirmware(2, normalized!!, storeSource)
        assertIs<ConsoleModel.DraftResult.Accepted>(draft, "the Android firmware assembles identically on wasm")
        val run = draft.run
        assertEquals(ANDROID_STORE_NAME, run.firmwareName, "session carries the imported Android store name")

        repeat(30) { assertEquals(StepOutcome.ADVANCED, run.step(), "step to tick 30") }
        val t = run.telemetry()
        assertEquals(30, run.tick, "G4 tick 30")
        assertEquals("MOVE (1/2)", run.lastEvent(), "G4 last: MOVE (1/2) at tick 30")
        assertEquals(6, t.a, "G4 A 6 at tick 30")
        assertEquals("$12D", t.pcHex, "G4 PC at tick 30")
        assertEquals(6, t.fireDist, "G4 FIRE_DIST 6 at tick 30")
        assertEquals(55, t.fireLevel, "G4 FIRE_LEVEL 55 at tick 30")
        assertEquals("\$$TICK30_HASH", run.fingerprintHex(), "mid-run fingerprint at tick 30")

        var guard = 0
        while (run.status() == null && guard < 400) {
            run.step()
            guard++
        }
        assertEquals(102, run.tick, "G4 mission 02 finishes at tick 102")
        assertEquals(RunStatus.SUCCESS, run.status(), "G4 mission 02 finishes successfully")
        assertEquals(MATRIX_FINAL_HASH, run.fingerprint(), "G4/G5 fingerprint equals the committed matrix anchor")
        val shortHash = MATRIX_FINAL_HASH.substring(0, 8)
        assertEquals("\$$shortHash", run.fingerprintHex(), "status line fingerprint")
    }

    /**
     * G5, web -> app: the link the browser console emitted for that run is a valid
     * recipe - it decodes to the same bytes, re-encodes byte-for-byte to the pinned
     * fixture link, and opening it reproduces the same run. `:fireline-app` reads the
     * same store bytes, so both containers describe one firmware.
     */
    @Test
    fun browserShareLinkDecodesToTheAndroidStoreBytesAndReEncodesByteForByte() {
        val decoded = Permalink.decode(BROWSER_SHARE_LINK, WebLibrary.authoredMissionIds(Missions.ALL))
        assertIs<Permalink.Decoded.Ok>(decoded, "the browser's own link is a valid recipe")
        assertEquals(2, decoded.missionId, "mission 02")
        assertEquals(Permalink.AUTHORED_SEED, decoded.seedOverride, "authored seed (no override)")
        assertEquals(ANDROID_STORE_NAME, decoded.firmwareName, "store name survives the link")
        assertEquals(decode64(reference.sourceBase64), decoded.firmwareSource, "the link carries the Android store bytes")
        assertEquals(reference.link, BROWSER_SHARE_LINK, "browser link equals the pinned generator link")
        val mission = Missions.ALL.first { it.id == 2 }
        assertEquals(
            BROWSER_SHARE_LINK,
            Permalink.encode(mission, decoded.firmwareName, decoded.firmwareSource, decoded.seedOverride),
            "re-encoding the decoded recipe gives the same string (a recipe, not a label)",
        )

        val reopened = model.openMission(2, decoded.firmwareSource, decoded.firmwareName)
        var guard = 0
        while (reopened.status() == null && guard < 400) {
            reopened.step()
            guard++
        }
        assertEquals(102, reopened.tick, "opening the link reaches tick 102")
        assertEquals(MATRIX_FINAL_HASH, reopened.fingerprint(), "opening the link reaches the matrix anchor")
    }

    /**
     * G6, web-side limits on the import path: a picked file that breaks an engine
     * ceiling is refused with the engine's own reason text, before any session or
     * store write (the browser control for the same gate the wasm tests assert).
     */
    @Test
    fun oversizedAndHostileImportsAreRefusedWithEngineReasons() {
        val lines = buildString { repeat(1201) { append("WAIT\n") } }
        val tooLong = model.draftFirmware(2, "TOO-BIG", lines)
        assertIs<ConsoleModel.DraftResult.Rejected>(tooLong, "1201 lines breach the engine line ceiling")
        assertTrue(tooLong.problems.isNotEmpty(), "a reason is reported")
        assertTrue(tooLong.problems.any { it.contains("1200") }, "the reason names the engine limit: ${tooLong.problems}")

        val bytes = buildString { repeat(900) { append("REM MARKER1234567890\n") } }
        val tooWide = model.draftFirmware(2, "TOO-WIDE-LINE", "")
        assertIs<ConsoleModel.DraftResult.Rejected>(tooWide, "an empty source is refused too")
        val tooManyBytes = model.draftFirmware(2, "TOO-MANY-BYTES", bytes)
        assertIs<ConsoleModel.DraftResult.Rejected>(tooManyBytes, "an over-wide line breaches the byte ceiling")
        assertTrue(tooManyBytes.problems.any { it.contains("8192") }, "the reason names the byte ceiling: ${tooManyBytes.problems}")

        val nonAscii = model.draftFirmware(2, "UMLAUT", "REM ä\nWAIT\n")
        assertIs<ConsoleModel.DraftResult.Rejected>(nonAscii, "non-ASCII is refused (Android store contract)")
        assertTrue(nonAscii.problems.any { it.contains("ASCII") }, "the reason says why: ${nonAscii.problems}")

        assertNull(WebLibrary.normalizeName(""), "an empty file name is not a store name")
    }
}
