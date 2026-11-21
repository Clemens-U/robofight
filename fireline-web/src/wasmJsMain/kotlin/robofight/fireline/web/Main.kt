package robofight.fireline.web

import kotlin.js.ExperimentalJsExport
import kotlin.js.JsExport
import kotlin.js.js

/*
 * The wasmJs entry point of the FIRELINE WEB CONSOLE (docs/FIRELINE-WEB.md §13,
 * §15 frozen layout; docs/WEB-W3-RECORD.md §7 names this the shell step).
 *
 * It is deliberately thin, and it contains no mission or run logic at all: no
 * tick arithmetic, no status wording, no formatting rules. Every visible value
 * comes from `ConsoleModel` / `ConsoleRun` / the engine's `TelemetrySnapshot`,
 * and every line of HTML comes from `ConsoleRender.renderConsoleHtml`. This file
 * only (1) keeps which mission is open, (2) hands the model plus that run to the
 * render function, (3) assigns the result to one DOM node's innerHTML, and
 * (4) maps clicks to `ConsoleRun.step()` / `run()` / `reset()`. The same
 * `MissionSession` the Android MISSION screen drives is behind those three
 * calls, which is what §13 asks for ("two UIs drifting" is the risk this split
 * exists to prevent).
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

private val model = ConsoleModel()

/** The mission currently open in the console; null until a card is opened. */
private var openRun: ConsoleRun? = null

/** Called by `console.html` after the module loads. */
@OptIn(ExperimentalJsExport::class)
@JsExport
fun startFirelineConsole() {
    openMission(STARTUP_MISSION_ID)
}

private fun openMission(missionId: Int) {
    openRun = model.openMission(missionId)
    render()
}

private fun stepRun() {
    openRun?.step()
    render()
}

private fun runRun() {
    openRun?.run()
    render()
}

private fun resetRun() {
    openRun?.reset()
    render()
}

/** One render pass: pure HTML out of the render layer, into the container, then
 *  the click wiring is re-attached because the container's children were just
 *  replaced. */
private fun render() {
    setInnerHtml(SHELL_CONTAINER_ID, ConsoleRender.renderConsoleHtml(model, openRun))
    onClick(ConsoleRender.ID_RUN) { runRun() }
    onClick(ConsoleRender.ID_STEP) { stepRun() }
    onClick(ConsoleRender.ID_RESET) { resetRun() }
    for (row in model.pickerRows()) {
        onClick(ConsoleRender.openCardButtonId(row.id)) { openMission(row.id) }
    }
}

// ---------------------------------------------------------- DOM interop helpers
// `js()` snippets are only allowed as the single expression of a package-level
// function; the Kotlin parameters are visible inside them, and a Kotlin function
// type marshals to a JavaScript function (Kotlin/Wasm interop type table).

private fun setInnerHtml(id: String, html: String): Unit =
    js("""{ const node = document.getElementById(id); if (node) node.innerHTML = html; }""")

private fun onClick(id: String, handler: () -> Unit): Unit =
    js("""{ const node = document.getElementById(id); if (node) node.addEventListener("click", () => handler()); }""")
