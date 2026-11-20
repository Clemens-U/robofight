package robofight.fireline

import robofight.isa.hexPad

/**
 * Input limits for any container that takes firmware from a human being, i.e.
 * the browser path (docs/FIRELINE-WEB.md §4 gate G6, FIRELINE-DESIGN.md §13.4):
 * every ceiling is checked BEFORE a program is assembled or a world is built,
 * so a hostile or accidental paste can never allocate a mission.
 *
 * `:fireline-app` validates the same document shape for its store
 * (`robofight.fireline.app.files.FirmwareSource`) and delegates its numeric
 * ceilings here, so Android and web share one limit set. The engine's own
 * [MAX_TICKS] cap stays the outer guard; a web request may ask for fewer ticks
 * but never for more than the cap.
 */
object WebInputLimits {

    /** Same document ceiling as the Android store: 256 instructions / 768 program
     *  bytes is what the assembler accepts, 1200 lines covers labels and comments. */
    const val MAX_SOURCE_LINES = 1200

    /** Character ceiling for a whole source document (UTF-8 encoded). */
    const val MAX_SOURCE_BYTES = 8192

    /** Tick ceiling a caller may request; above it the request is refused. */
    const val MAX_REQUEST_TICKS = MAX_TICKS

    /** Reject reasons for a source document. Empty list means acceptable. */
    fun sourceProblems(source: String): List<String> {
        val problems = ArrayList<String>()
        if (source.isBlank()) problems.add("source is empty")
        if (source.contains('\r')) problems.add("source contains carriage return characters")
        for (ch in source) {
            if (ch.code > 126) {
                problems.add("source contains non-ASCII character (U+${hexPad(ch.code, 4)}), sources are ASCII")
                break
            }
        }
        for (ch in source) {
            if (ch.code < 32 && ch != '\n' && ch != '\t') {
                problems.add("source contains control character U+00${hexPad(ch.code, 2)}")
                break
            }
        }
        val lines = source.split('\n').size
        if (lines > MAX_SOURCE_LINES) problems.add("source has $lines lines, limit is $MAX_SOURCE_LINES")
        val bytes = source.encodeToByteArray().size
        if (bytes > MAX_SOURCE_BYTES) problems.add("source is $bytes bytes, limit is $MAX_SOURCE_BYTES")
        return problems
    }

    /** Reject reasons for a requested tick budget. */
    fun tickProblems(requestedMaxTicks: Int): List<String> {
        val problems = ArrayList<String>()
        if (requestedMaxTicks <= 0) problems.add("requested tick budget $requestedMaxTicks, needs at least 1")
        if (requestedMaxTicks > MAX_REQUEST_TICKS) {
            problems.add("requested tick budget $requestedMaxTicks exceeds the engine cap $MAX_REQUEST_TICKS")
        }
        return problems
    }

    /**
     * Check a whole run request (mission + pasted firmware + tick budget) and,
     * only if every limit holds, hand back the assembled descriptor and the tick
     * budget the run may use. Nothing is assembled or allocated for a rejected
     * request: that ordering is what gate G6 is about.
     */
    fun prepare(mission: Mission, source: String, requestedMaxTicks: Int = mission.maxTicks): RunRequest {
        val problems = sourceProblems(source) + tickProblems(requestedMaxTicks)
        if (problems.isNotEmpty()) return RunRequest.Rejected(problems)

        val assembled = robofight.assembler.assemble(source)
        if (!assembled.ok) {
            return RunRequest.Rejected(assembled.errors.map { "line ${it.line}: ${it.msg}" })
        }
        return RunRequest.Accepted(
            descriptor = mission.descriptor(assembled.code),
            maxTicks = minOf(requestedMaxTicks, mission.maxTicks, MAX_REQUEST_TICKS),
            sourceBytes = source.encodeToByteArray().size,
            sourceLines = source.split('\n').size,
        )
    }
}

/** Result of [WebInputLimits.prepare]: either a runnable request or the reasons it was refused. */
sealed class RunRequest {

    abstract val problems: List<String>

    data class Accepted(
        val descriptor: MissionDescriptor,
        val maxTicks: Int,
        val sourceBytes: Int,
        val sourceLines: Int,
    ) : RunRequest() {
        override val problems: List<String> get() = emptyList()
    }

    data class Rejected(override val problems: List<String>) : RunRequest()

    fun acceptedOrNull(): Accepted? = this as? Accepted
}
