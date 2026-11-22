package robofight.fireline.web

import robofight.fireline.Mission
import robofight.fireline.WebInputLimits
import robofight.assembler.assemble

/**
 * The browser-side firmware library of the web console: the persistence model
 * behind the `localStorage` key `fireline.web.v1` (docs/FIRELINE-WEB.md §8
 * "Storage": firmware sources and names, selected firmware, nothing else; no
 * server-side copy; clearing site data or the in-page DELETE removes it).
 *
 * This class is the PURE codec half (encode/decode/lookup), with no DOM and no
 * `localStorage` access, so `wasmJsTest` asserts it in Node exactly like every
 * other layer here. `Main.kt` owns the single `localStorage.getItem/setItem`
 * pair for the key and passes the string through.
 *
 * Encoding is a line-oriented escaped text (version line, one line per entry
 * `name \t source-with-\n-escaped`, selection marker line). It deliberately is
 * not JSON: a JSON parser would be a second language the console parses, and
 * this format round-trips arbitrary source text with two escapes only. The
 * names are store names: uppercase with the `.ASM` store extension, the same
 * contract the Android store uses (gate G5 imports these very names).
 */
object WebLibrary {

    /** §8 namespaced storage key. Assembled from two parts: a single literal
     *  pass of this exact line once mangled the middle of the key into an
     *  ellipsis, and the parts make such damage impossible to write silently. */
    val STORAGE_KEY: String = "fireline." + "web.v1"

    private const val FORMAT_LINE = "fireline.web.v1"
    private const val SELECTION_MARKER = "\u0001selected " // \u0001 never occurs in a checked source

    data class Entry(val name: String, val source: String)

    data class Store(val entries: List<Entry>, val selectedName: String?)

    val EMPTY: Store = Store(emptyList(), null)

    /** Normalize a user-typed name into the store contract: trimmed, uppercase,
     *  `.ASM` extension; blank or symbol-bearing names are refused. */
    fun normalizeName(raw: String): String? {
        val trimmed = raw.trim().uppercase()
        if (trimmed.isEmpty()) return null
        val base = if (trimmed.endsWith(".ASM")) trimmed.dropLast(4) else trimmed
        if (base.isEmpty()) return null
        if (!base.matches(Regex("^[A-Z0-9][A-Z0-9_-]*$"))) return null
        if (base.length > 24) return null
        return base + ".ASM"
    }

    /** Engine-gate problems for a draft source (WebInputLimits first, then the assembler). */
    fun sourceProblems(source: String): List<String> {
        val gate = WebInputLimits.sourceProblems(source)
        if (gate.isNotEmpty()) return gate
        val result = assemble(source)
        if (result.ok) return emptyList()
        return result.errors.map { "line ${it.line}: ${it.msg}" }
    }

    // ------------------------------------------------------------------ codec

    fun encode(store: Store): String {
        val sb = StringBuilder(FORMAT_LINE)
        store.entries.forEach { entry ->
            sb.append('\n').append(entry.name).append('\t')
                .append(entry.source.replace("\\", "\\\\").replace("\n", "\\n"))
        }
        val selected = store.selectedName
        if (selected != null) sb.append('\n').append(SELECTION_MARKER).append(selected)
        return sb.toString()
    }

    /** A string that is not our format (or a corrupted one) decodes to [EMPTY];
     *  the console never trusts site data, §8. */
    fun decode(text: String?): Store {
        if (text == null || text.isEmpty()) return EMPTY
        val lines = text.split('\n')
        if (lines.firstOrNull() != FORMAT_LINE) return EMPTY
        val entries = ArrayList<Entry>()
        var selected: String? = null
        var broken = false
        for (line in lines.drop(1)) {
            if (line.startsWith(SELECTION_MARKER)) {
                selected = line.substring(SELECTION_MARKER.length).takeIf { it.isNotEmpty() }
                continue
            }
            val tab = line.indexOf('\t')
            if (tab <= 0) { broken = true; break }
            val name = normalizeName(line.substring(0, tab)) ?: run { broken = true; break }
            val source = line.substring(tab + 1).replace("\\n", "\n").replace("\\\\", "\\")
            entries.add(Entry(name, source))
        }
        return if (broken) EMPTY else Store(entries, selected)
    }

    // ------------------------------------------------------------------ edits

    fun saved(store: Store, name: String, source: String): Store {
        val without = store.entries.filterNot { it.name == name }
        return Store(without + Entry(name, source), name)
    }

    fun removed(store: Store, name: String): Store {
        val entries = store.entries.filterNot { it.name == name }
        return Store(entries, if (store.selectedName == name) null else store.selectedName)
    }

    fun withSelected(store: Store, name: String?): Store = Store(store.entries, name)

    fun find(store: Store, name: String): Entry? = store.entries.firstOrNull { it.name == name }

    /** Mission ids the console will open (authored catalog). */
    fun authoredMissionIds(missions: List<Mission>): Set<Int> = missions.map { it.id }.toSet()
}
