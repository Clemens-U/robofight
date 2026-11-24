package robofight.fireline.web

import kotlin.js.ExperimentalJsExport
import kotlin.js.JsExport
import kotlin.js.js
import robofight.fireline.Mission

/*
 * The wasmJs entry point of the FIRELINE WEB CONSOLE (docs/FIRELINE-WEB.md §13,
 * §15 frozen layout; docs/WEB-W3-RECORD.md names this the full-loop step).
 *
 * It is deliberately thin, and it contains no mission or run logic at all: no
 * tick arithmetic, no status wording, no formatting rules. Every visible value
 * comes from `ConsoleModel` / `ConsoleRun` / the engine's `TelemetrySnapshot`,
 * and every line of HTML comes from `ConsoleRender.renderConsoleHtml`. This file
 * only (1) keeps which mission is open and which shell state the render layer
 * draws (pacing, editor draft, share link, library), (2) hands the model plus
 * that run to the render function, (3) assigns the result to one DOM node's
 * innerHTML, and (4) maps clicks to `ConsoleRun.step()` / `run()` / `reset()`,
 * to the pacing timer, and to the §8 storage codec (`WebLibrary`). The same
 * `MissionSession` the Android MISSION screen drives is behind those run calls,
 * which is what §13 asks for ("two UIs drifting" is the risk this split exists
 * to prevent).
 *
 * Pacing never touches engine semantics: a tick is always exactly one
 * `ConsoleRun.step()` (STEP is the authority, FIRELINE-DESIGN 13.2), and the
 * SPEED chips / PANIC only choose how often that call happens.
 *
 * Kotlin/Wasm DOM interop: this module declares no npm dependency and does not
 * use kotlinx-browser, so `document` is reached through the `js()` snippet form
 * that the Kotlin/Wasm interop supports (`kotlin.js.js`, package-level function
 * bodies only, parameters visible inside the snippet).
 */

/** Mission the console opens on load: mission 02 / CORRIDOR with the authored
 *  reference firmware - the exact state the frozen golden master
 *  `docs/web/fl-web-5-playable-landing.html` is drawn from. */
private const val STARTUP_MISSION_ID = 2

/** The single container node the shell re-renders into (`fireline-web/console.html`). */
private const val SHELL_CONTAINER_ID = "fl-shell"

/** The pair columns' collapse breakpoint (§15.5), mirrored from console.html's CSS. */
private const val BREAKPOINT_MEDIA_QUERY = "max-width:900px"

private val model = ConsoleModel()

/** The mission currently open in the console; null until a mission is opened. */
private var openRun: ConsoleRun? = null

/** Shell state the render layer draws (speed, PANIC, editor, share, library). */
private var uiState = ConsoleUiState.DEFAULT

/** Seed override carried by a `#run=` permalink (0 = the mission's authored seed). */
private var seedOverride = Permalink.AUTHORED_SEED

/** Pacing timer handle (`setTimeout` id, 0 when nothing is scheduled). */
private var tickTimer: Int = 0

/** What the last completed render built its markup for (W3 record 16): the measured pair column and
 *  the cell edge derived from it. The follow-up pass re-renders only when a fresh measurement would
 *  produce a different cell, so a tick render costs nothing and only a first render (empty
 *  container), a resize or a flipped breakpoint re-lays out the grid. */
private var renderedColumnPx: Int = -1
private var renderedCellPx: Int = -1

/** Called by `console.html` after the module loads. */
@OptIn(ExperimentalJsExport::class)
@JsExport
fun startFirelineConsole() {
    watchLayoutBreakpoint()
    openStartupMission()
}

// ------------------------------------------------------------------- open a run

/** Open a mission with its authored reference firmware (store name `NAME-REF.ASM`,
 *  the store-name contract the exported and shared firmwares use, gate G5), after
 *  giving a `#run=` permalink the chance to define the state instead. */
private fun openStartupMission() {
    if (openFromPermalink(currentFragment())) return
    openReferenceMission(STARTUP_MISSION_ID)
    /* Two passes (W3 record 16): the first render had no measured column yet because the
       container was empty, this one measures the pair the first render laid out and hands that
       width to the grid scaling. */
    relayout()
}

private fun openReferenceMission(missionId: Int) {
    val mission = model.missions.firstOrNull { it.id == missionId } ?: return
    openRun = model.openMission(missionId, mission.referenceSource, referenceName(mission))
    seedOverride = Permalink.AUTHORED_SEED
    uiState = uiState.copy(libraryTargetMissionId = missionId, importMissionId = missionId, editDraft = null)
    schedule()
}

/**
 * §8 "Share links": a `#run=FLW1-…` fragment re-runs the exact console state it
 * describes. A refused or foreign link is not an error page: the console falls
 * back to its startup mission and prints the refusal reason.
 */
private fun openFromPermalink(fragment: String): Boolean {
    if (fragment.isEmpty()) return false
    val decoded = Permalink.decode(fragment, WebLibrary.authoredMissionIds(model.missions))
    if (decoded is Permalink.Decoded.Refused) {
        uiState = uiState.copy(statusLine = "share link refused: " + decoded.problems.joinToString(" | "))
        return false
    }
    val ok = decoded as Permalink.Decoded.Ok
    val draft = model.draftFirmware(ok.missionId, ok.firmwareName, ok.firmwareSource)
    if (draft is ConsoleModel.DraftResult.Rejected) {
        uiState = uiState.copy(statusLine = "share link refused: " + draft.problems.joinToString(" | "))
        return false
    }
    openRun = (draft as ConsoleModel.DraftResult.Accepted).run
    seedOverride = ok.seedOverride
    uiState = uiState.copy(
        libraryTargetMissionId = ok.missionId,
        importMissionId = ok.missionId,
        editDraft = null,
        statusLine = "opened from a share link",
    )
    schedule()
    return true
}

// ------------------------------------------------------------------- pacing loop

/** One engine tick per timer fire: SPEED 1/5/20/50 ticks per second, PANIC = one
 *  timer hop per tick with no speed limit (§15.4). Stops on a finished run. */
private fun schedule() {
    cancelTickTimer()
    updateState()
    render()
    val run = openRun ?: return
    if (run.finished || run.guardReached) return
    val delayMs = if (uiState.panic) 0 else 1000 / uiState.speed
    tickTimer = setTimeout({ tickOnce() }, delayMs)
}

private fun tickOnce() {
    tickTimer = 0
    val run = openRun ?: return
    run.step()
    schedule()
}

private fun cancelTickTimer() {
    if (tickTimer != 0) {
        clearTimeout(tickTimer)
        tickTimer = 0
    }
}

private fun setSpeed(ticksPerSecond: Int) {
    if (ticksPerSecond !in ConsoleRender.SPEED_CHOICES) return
    uiState = uiState.copy(speed = ticksPerSecond, panic = false)
    schedule()
}

private fun togglePanic() {
    uiState = uiState.copy(panic = !uiState.panic)
    schedule()
}

// ---------------------------------------------------------------- state + actions

private fun updateState() {
    val store = WebLibrary.decode(readStored(WebLibrary.STORAGE_KEY))
    val run = openRun
    var link: String? = null
    var linkProblem = ""
    if (run != null) {
        try {
            link = Permalink.fragmentFor(run.mission, run.firmwareName, run.session.firmwareSource, seedOverride)
        } catch (e: IllegalArgumentException) {
            linkProblem = "share link refused: ${e.message}"
        }
    }
    val draft = uiState.editDraft
    val checkedDraft = draft?.let {
        if (run == null) it else it.copy(problems = WebLibrary.sourceProblems(it.source))
    }
    uiState = uiState.copy(
        library = store,
        shareLink = link,
        editDraft = checkedDraft,
        statusLine = if (linkProblem.isNotEmpty()) linkProblem else uiState.statusLine,
        asmNote = run?.let { assemblerNote(it) } ?: ConsoleRender.DEFAULT_ASSEMBLER_NOTE,
    )
}

/** The golden master's assembler note wording with this run's own engine numbers
 *  (`MissionSession.result()`, the same fields `RUN STATS` shows). */
private fun assemblerNote(run: ConsoleRun): String =
    "assembler: 0 errors, " + run.firmwareBytes() + " bytes, " + run.sourceLines().size +
        " source lines. " + ConsoleRender.DEFAULT_ASSEMBLER_NOTE_TAIL

private fun stepRun() {
    cancelTickTimer()
    openRun?.step()
    schedule()
}

private fun runRun() {
    cancelTickTimer()
    openRun?.run()
    schedule()
}

private fun resetRun() {
    openRun?.reset()
    schedule()
}

// ----------------------------------------------------------------------- editor

private fun startEditing() {
    val run = openRun ?: return
    cancelTickTimer()
    uiState = uiState.copy(editDraft = EditDraft(source = run.session.firmwareSource, name = run.firmwareName))
    render()
}

private fun cancelEditing() {
    uiState = uiState.copy(editDraft = null)
    schedule()
}

/** APPLY (§12a D4): the draft goes through `WebInputLimits` and the real RF-8
 *  assembler before a session is opened; a refusal names the line it failed on. */
private fun applyEditorDraft() {
    val run = openRun ?: return
    val source = readTextValue(ConsoleRender.ID_EDITOR_SOURCE)
    val typedName = readTextValue(ConsoleRender.ID_EDITOR_NAME)
    val normalizedName = WebLibrary.normalizeName(if (typedName.isBlank()) run.firmwareName else typedName)
    if (normalizedName == null) {
        uiState = uiState.copy(editDraft = EditDraft(source, typedName, listOf("store name refused: '$typedName'")))
        render()
        return
    }
    val draft = model.draftFirmware(run.missionId, normalizedName, source)
    if (draft is ConsoleModel.DraftResult.Rejected) {
        uiState = uiState.copy(editDraft = EditDraft(source, normalizedName, draft.problems))
        render()
        return
    }
    openRun = (draft as ConsoleModel.DraftResult.Accepted).run
    seedOverride = Permalink.AUTHORED_SEED
    uiState = uiState.copy(editDraft = null, statusLine = "applied $normalizedName (mission ${run.missionId})")
    schedule()
}

// -------------------------------------------------------- library / import / export

private fun saveCurrentFirmware() {
    val run = openRun ?: return
    uiState = uiState.copy(
        library = WebLibrary.saved(uiState.library, run.firmwareName, run.session.firmwareSource),
        statusLine = "saved ${run.firmwareName} in this browser",
    )
    persistLibrary()
    render()
}

private fun loadLibraryFirmware(name: String) {
    val entry = WebLibrary.find(uiState.library, name) ?: return
    val missionId = readSelectValue(ConsoleRender.ID_LIBRARY_TARGET)
    val draft = model.draftFirmware(missionId, entry.name, entry.source)
    if (draft is ConsoleModel.DraftResult.Rejected) {
        uiState = uiState.copy(statusLine = "$name refused: " + draft.problems.joinToString(" | "))
        render()
        return
    }
    openRun = (draft as ConsoleModel.DraftResult.Accepted).run
    seedOverride = Permalink.AUTHORED_SEED
    uiState = uiState.copy(
        library = WebLibrary.withSelected(uiState.library, name),
        libraryTargetMissionId = missionId,
        editDraft = null,
        statusLine = "loaded ${entry.name} into mission $missionId",
    )
    persistLibrary()
    schedule()
}

private fun deleteLibraryFirmware(name: String) {
    uiState = uiState.copy(
        library = WebLibrary.removed(uiState.library, name),
        statusLine = "deleted $name from this browser",
    )
    persistLibrary()
    render()
}

private fun clearLibrary() {
    uiState = uiState.copy(library = WebLibrary.EMPTY, statusLine = "library deleted from this browser")
    persistLibrary()
    render()
}

private fun persistLibrary() {
    writeStored(WebLibrary.STORAGE_KEY, WebLibrary.encode(uiState.library))
}

/** IMPORT .ASM (§12a D4, gate G5 the other direction): the file name becomes the
 *  store name through the same normalization the Android store uses, the content
 *  goes through the web input gate and the assembler, and only then is a session
 *  opened and the entry stored. */
private fun importFirmwareFile(fileName: String, sourceText: String) {
    val name = WebLibrary.normalizeName(fileName.substringBeforeLast('.')) ?: WebLibrary.normalizeName(fileName)
    if (name == null) {
        uiState = uiState.copy(statusLine = "import refused: '$fileName' is not a store name (NAME.ASM)")
        render()
        return
    }
    val missionId = readSelectValue(ConsoleRender.ID_IMPORT_MISSION)
    val draft = model.draftFirmware(missionId, name, sourceText)
    if (draft is ConsoleModel.DraftResult.Rejected) {
        uiState = uiState.copy(statusLine = "$name refused: " + draft.problems.joinToString(" | "))
        render()
        return
    }
    openRun = (draft as ConsoleModel.DraftResult.Accepted).run
    seedOverride = Permalink.AUTHORED_SEED
    uiState = uiState.copy(
        library = WebLibrary.saved(uiState.library, name, sourceText),
        importMissionId = missionId,
        libraryTargetMissionId = missionId,
        editDraft = null,
        statusLine = "imported $name into mission $missionId",
    )
    persistLibrary()
    schedule()
}

/** COPY: the full page URL plus the `#run=` fragment (§8: the payload stays in
 *  the fragment, so it never reaches the server or an access log). The fragment
 *  is also written into this page's own address, so a refused clipboard still
 *  leaves the user a copyable, openable link. */
private fun copyShareLink() {
    val link = uiState.shareLink ?: return
    setFragment(link)
    val copied = copyToClipboard(currentHref().substringBefore('#') + link)
    uiState = uiState.copy(
        statusLine = if (copied) "share link copied"
        else "clipboard refused by the browser, link is in the address bar",
    )
    render()
}

/** DOWNLOAD .ASM: the open run's firmware source as a UTF-8 `.ASM` file named by
 *  the store contract (`CORRIDOR-REF.ASM`), the name `:fireline-app` imports. */
private fun downloadFirmware() {
    val run = openRun ?: return
    triggerDownload(run.firmwareName, run.session.firmwareSource)
    uiState = uiState.copy(statusLine = "downloaded ${run.firmwareName}")
    render()
}

private fun referenceName(mission: Mission): String = mission.name.uppercase().replace(' ', '-') + "-REF.ASM"

/** The laid-out world column width of the shell container (0 when the console has not rendered a
 *  pair yet). The grid scaling of W3 record 16 derives the cell edge from THIS measured number,
 *  because Chrome cannot evaluate a length expression inside grid track sizing reliably.
 *
 *  Interop rule (W3 record 16): a `js()` snippet may only refer to this function's own
 *  parameters. An identifier that is not a parameter (here `id`, borrowed from the other DOM
 *  helpers) compiles but throws a JavaScript ReferenceError at call time, which arrives in the
 *  browser as an opaque `[object WebAssembly.Exception]`. */
private fun currentWorldColumnPx(shellId: String): Int =
    js("""{ const n = document.querySelector('#' + shellId + ' .pairworld');
      const w = n ? n.clientWidth : 0; return Number.isFinite(w) ? w : 0; }""")

/** One render pass: pure HTML out of the render layer, into the container, then
 *  the click wiring is re-attached because the container's children were just
 *  replaced. The world column is measured BEFORE the container is replaced, so the
 *  measurement sees the shell that is currently on screen. */
private fun render(scheduleFollowUpLayout: Boolean = true) {
    val worldColumnPx = currentWorldColumnPx(SHELL_CONTAINER_ID)
    setInnerHtml(SHELL_CONTAINER_ID, ConsoleRender.renderConsoleHtml(model, openRun, uiState, worldColumnPx))
    onClick(ConsoleRender.ID_RUN) { runRun() }
    onClick(ConsoleRender.ID_STEP) { stepRun() }
    onClick(ConsoleRender.ID_RESET) { resetRun() }
    onClick(ConsoleRender.ID_PANIC) { togglePanic() }
    onClick(ConsoleRender.ID_PANIC_ON) { togglePanic() }
    onClick(ConsoleRender.ID_COPY) { copyShareLink() }
    onClick(ConsoleRender.ID_DOWNLOAD) { downloadFirmware() }
    onClick(ConsoleRender.ID_EDIT) { startEditing() }
    onClick(ConsoleRender.ID_APPLY) { applyEditorDraft() }
    onClick(ConsoleRender.ID_EDITOR_CANCEL) { cancelEditing() }
    onClick(ConsoleRender.ID_SAVE) { saveCurrentFirmware() }
    onClick(ConsoleRender.ID_CLEAR_LIBRARY) { clearLibrary() }
    for (speed in ConsoleRender.SPEED_CHOICES) {
        onClick(ConsoleRender.speedButtonId(speed)) { setSpeed(speed) }
    }
    for (row in model.pickerRows()) {
        onClick(ConsoleRender.openCardButtonId(row.id)) { openReferenceMission(row.id) }
    }
    for (entry in uiState.library.entries) {
        onClick(ConsoleRender.libraryLoadButtonId(entry.name)) { loadLibraryFirmware(entry.name) }
        onClick(ConsoleRender.libraryDeleteButtonId(entry.name)) { deleteLibraryFirmware(entry.name) }
    }
    onChange(ConsoleRender.ID_LIBRARY_TARGET) {
        uiState = uiState.copy(libraryTargetMissionId = readSelectValue(ConsoleRender.ID_LIBRARY_TARGET))
        render()
    }
    onChange(ConsoleRender.ID_IMPORT_MISSION) {
        uiState = uiState.copy(importMissionId = readSelectValue(ConsoleRender.ID_IMPORT_MISSION))
        render()
    }
    onImportFileSelected(ConsoleRender.ID_IMPORT_FILE) { name, text -> importFirmwareFile(name, text) }

    /* The container's children were just replaced, so the layout is fresh: the follow-up pass
       measures the pair this render laid out and re-renders only if the cell it implies differs
       from the cell this render was built for. A relayout pass never re-arms (that would render
       forever). */
    renderedColumnPx = worldColumnPx
    renderedCellPx = WorldGrid.cellSizePx(worldColumnPx)
    if (scheduleFollowUpLayout) relayoutIfChanged()
}


/** Second layout pass (W3 record 16): re-render so the grid scaling uses a column width that the
 *  PREVIOUS render laid out. A measure cannot come from a container that is still empty, and the
 *  grid box itself is forbidden as the measure because Chrome sizes a grid box from its own tracks.
 *
 *  The pass is SYNCHRONOUS, not a `requestAnimationFrame` callback: measured layout callbacks did
 *  not fire reliably through the wasm interop here, and a second render does not need a frame
 *  anyway. The loop is broken by `scheduleFollowUpLayout = false` on the relayout pass (only a
 *  normal render arms a follow-up), and the same pass is what a resize or a breakpoint flip runs. */
private fun relayout() {
    relayoutPass()
}

/** The follow-up of a normal render: re-render only when the measured pair column is NOT the width
 *  the just-rendered markup was built for (the first render of an empty container, a resized window,
 *  a flipped breakpoint). A tick render whose width already matches costs nothing. */
private fun relayoutIfChanged() {
    if (WorldGrid.cellSizePx(currentWorldColumnPx(SHELL_CONTAINER_ID)) == renderedCellPx) return
    relayoutPass()
}

private fun relayoutPass() {
    render(scheduleFollowUpLayout = false)
}


/** Re-render when the window changes, so the grid rescales with it (W3 record 16): the
 *  pair columns collapse at the §15.5 breakpoint, and a plain resize inside one band
 *  changes the column width the scaling depends on. */
private fun watchLayoutBreakpoint() {
    watchMediaWidth(BREAKPOINT_MEDIA_QUERY) { relayout() }
    watchWindowResize { relayout() }
}

/** One `matchMedia` listener: the Kotlin handler must arrive as an explicit `js()`
 *  parameter (the pattern every other DOM helper here uses); a handler captured from
 *  the enclosing scope traps at call time (W3 record 16). */
private fun watchMediaWidth(query: String, handler: () -> Unit): Unit =
    js("""{ const mq = matchMedia(query); mq.addEventListener("change", () => handler()); }""")

private fun watchWindowResize(handler: () -> Unit): Unit =
    js("""{ window.addEventListener("resize", () => handler()); }""")

// ---------------------------------------------------------- DOM interop helpers
// `js()` snippets are only allowed as the single expression of a package-level
// function; the Kotlin parameters are visible inside them, and a Kotlin function
// type marshals to a JavaScript function (Kotlin/Wasm interop type table).

private fun setInnerHtml(id: String, html: String): Unit =
    js("""{ const node = document.getElementById(id); if (node) node.innerHTML = html; }""")

private fun onClick(id: String, handler: () -> Unit): Unit =
    js("""{ const node = document.getElementById(id); if (node) node.addEventListener("click", () => handler()); }""")

private fun onChange(id: String, handler: () -> Unit): Unit =
    js("""{ const node = document.getElementById(id); if (node) node.addEventListener("change", () => handler()); }""")

/** Value of the editor's `<textarea>` / store-name `<input>` at click time. */
private fun readTextValue(id: String): String =
    js("""{ const node = document.getElementById(id); return node ? String(node.value) : ""; }""")

/** `value` of a mission `<select>` as an Int (0 when the node or its value is gone). */
private fun readSelectValue(id: String): Int =
    js("""{ const node = document.getElementById(id); const v = node ? parseInt(node.value, 10) : 0; return Number.isFinite(v) ? v : 0; }""")

private fun currentFragment(): String = js("""{ return String(location.hash || ""); }""")

/** Write the share fragment (leading `#run=…`) into this page's address. */
private fun setFragment(fragment: String): Unit =
    js("""{ location.hash = fragment.startsWith("#") ? fragment.slice(1) : fragment; }""")

private fun currentHref(): String = js("""{ return String(location.href || ""); }""")

/** §8 storage: the console's only `localStorage` read. A browser that refuses
 *  storage (private mode) reads as null, which the codec decodes to an empty
 *  library, exactly like a foreign value. */
private fun readStored(key: String): String? =
    js("""{ try { const v = localStorage.getItem(key); return v === null ? null : String(v); } catch (e) { return null; } }""")

/** §8 storage: the console's only `localStorage` write. A quota failure is not a
 *  page failure: the open run stays open and the status line reports it. */
private fun writeStored(key: String, value: String): Unit =
    js("""{ try { localStorage.setItem(key, value); } catch (e) { } }""")

/** Pacing: one timer hop. `SPEED n` = `1000/n` ms, `PANIC` = 0 ms (§15.4). */
private fun setTimeout(handler: () -> Unit, ms: Int): Int =
    js("""{ return setTimeout(() => handler(), ms); }""")

private fun clearTimeout(handle: Int): Unit = js("""{ clearTimeout(handle); }""")

/** COPY of §15.4. `navigator.clipboard.writeText` is asynchronous, and the
 *  console only has a synchronous status line, so the copy uses the synchronous
 *  selection path (a throwaway readonly textarea plus `execCommand("copy")`) and
 *  reports what the browser actually did. */
private fun copyToClipboard(text: String): Boolean = js("""{
  const ta = document.createElement("textarea");
  ta.value = text;
  ta.setAttribute("readonly", "");
  ta.style.position = "fixed";
  ta.style.left = "-10000px";
  document.body.appendChild(ta);
  ta.select();
  let ok = false;
  try { ok = document.execCommand("copy") === true; } catch (e) { ok = false; }
  document.body.removeChild(ta);
  return ok;
}""")

/** IMPORT .ASM file pick: `FileReader.readAsText` (UTF-8), then the Kotlin
 *  callback gets the file name and the full text. The input is cleared so the
 *  same file can be imported twice. */
private fun onImportFileSelected(inputId: String, handler: (String, String) -> Unit): Unit = js("""{
  const node = document.getElementById(inputId);
  if (!node) return;
  node.addEventListener("change", () => {
    const file = node.files && node.files[0];
    if (!file) return;
    const reader = new FileReader();
    reader.onload = () => handler(String(file.name), String(reader.result));
    reader.readAsText(file, "utf-8");
    node.value = "";
  });
}""")

/** DOWNLOAD .ASM: one object URL plus `a[download]`, so the file is a real UTF-8
 *  `.asm` with the content type `:fireline-app` exports firmware as
 *  (`application/octet-stream`, `MainActivity` export). */
private fun triggerDownload(fileName: String, text: String): Unit = js("""{
  const blob = new Blob([text], { type: "application/octet-stream" });
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = fileName;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  setTimeout(() => URL.revokeObjectURL(url), 2000);
}""")
