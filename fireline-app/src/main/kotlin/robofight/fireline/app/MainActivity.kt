package robofight.fireline.app

import robofight.fireline.app.mission.MissionPickerModel

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import robofight.fireline.app.files.FirmwareFile
import robofight.fireline.app.files.FirmwareFileNames
import robofight.fireline.app.files.FirmwareRepository
import robofight.fireline.app.files.SaveOutcome
import robofight.fireline.app.files.StarterFirmware
import robofight.fireline.app.editor.EditorModel
import robofight.fireline.app.mission.BestBadgeStore
import robofight.fireline.app.mission.FirmwareSelection
import robofight.fireline.app.mission.MissionRunSurface
import robofight.fireline.app.mission.MissionSelection
import robofight.fireline.app.mission.MissionSession
import robofight.fireline.app.mission.MissionSurface
import robofight.fireline.app.mission.ResultSurface
import robofight.fireline.app.mission.RunRecordStore
import robofight.fireline.app.mission.RunSummary
import robofight.fireline.app.mission.SharedPreferencesValues
import robofight.fireline.R
import robofight.fireline.Mission
import robofight.fireline.Missions

/**
 * FIRELINE MainActivity (PLAN-RF8-MULTIAPP.md 9.2): programmatic views only, no
 * XML layouts, no androidx - the Arena house style. Owns the shell (wordmark
 * strip, tagline, FILES | MISSION | RESULT tabs), the FIRELINE-private file
 * store, and the surfaces built so far: FILES, the editor, the MISSION picker
 * + run surface, and the RESULT surface (milestones 3-6).
 *
 * Mission selection state, the selected mission id (12.1), the last-run record
 * and per-mission best badges are plain-data SharedPreferences records,
 * versioned `fireline.mission.v1` / `fireline.mission.last.v1` /
 * `fireline.mission.best.v1`; RESULT renders only from the last-run record.
 *
 * Motion rule (DESIGN-FIRELINE.md 7): nothing here animates; the CRT overlay is
 * default-off and therefore absent.
 */
class MainActivity : Activity() {

    private lateinit var theme: FirelineTheme
    private lateinit var store: FirmwareRepository
    private lateinit var prefs: SharedPreferences
    private lateinit var firmwareSelection: FirmwareSelection
    private lateinit var missionSelection: MissionSelection
    private lateinit var bestStore: BestBadgeStore
    private lateinit var runStore: RunRecordStore
    private lateinit var contentFrame: FrameLayout
    private lateinit var tabs: MutableMap<String, TextView>
    private lateinit var filesSurface: FilesSurface
    private var shellRoot: LinearLayout? = null

    private var selectedFirmwareName: String? = null
    private var currentTab: String = "FILES"
    private var missionSurface: MissionSurface? = null
    private var currentRunSurface: MissionRunSurface? = null
    private var editorSurface: EditorSurface? = null
    private var pendingExport: Pair<String, String>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        theme = FirelineTheme(this)
        store = FirelineFiles(this)
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        firmwareSelection = FirmwareSelection(SharedPreferencesValues(prefs))
        missionSelection = MissionSelection(SharedPreferencesValues(prefs))
        bestStore = BestBadgeStore(SharedPreferencesValues(prefs))
        runStore = RunRecordStore(SharedPreferencesValues(prefs))
        seedStarterFirmwareOnce()
        selectedFirmwareName = firmwareSelection.get()
        buildFilesSurface()
        setContentView(buildShell())
        // Make sure the first layout pass already carries the system bar insets
        // (some windows deliver the first dispatch only on request).
        redispatchInsets()
        showFiles()
    }

    /**
     * Give a fresh install a selected, runnable first program. The preference
     * prevents a player who intentionally deletes every file from having the
     * starter recreated on the next launch.
     */
    private fun seedStarterFirmwareOnce() {
        if (prefs.getBoolean(PREF_STARTER_SEEDED, false)) return
        StarterFirmware.seedIfEmpty(store)?.let { firmwareSelection.set(it.name) }
        prefs.edit().putBoolean(PREF_STARTER_SEEDED, true).apply()
    }

    override fun onResume() {
        super.onResume()
        // The bars can change shape while the app is backgrounded (gesture
        // nav vs button nav, display cutout, rotation): re-read them.
        redispatchInsets()
    }

    // ---- shell ------------------------------------------------------------------

    private fun buildShell(): View {
        val baseSide = theme.dp(12)
        val baseVertical = theme.dp(10)
        val root = column(matchParent, matchParent).apply {
            setBackgroundColor(theme.field)
            setPadding(baseSide, baseVertical, baseSide, baseVertical)
            // Edge-to-edge is enforced from targetSdk 35 (we build against 36):
            // the window draws behind the status and navigation bars and the
            // framework no longer insets the content for us, so chrome that
            // ignores the insets sits under the status bar. Framework-only
            // handling (no androidx): the root keeps its own chrome padding
            // and adds the system bar insets on top of it - top for the
            // wordmark strip, bottom so the pinned RUN / STEP / RESET bar
            // clears the navigation bar. The listener lives as long as this
            // view tree, so a rotation or a nav-bar mode change re-pads from
            // the freshly dispatched insets instead of the build-time values.
            setOnApplyWindowInsetsListener { view, insets ->
                val (left, top, right, bottom) = insetParts(insets)
                view.setPadding(
                    baseSide + left,
                    baseVertical + top,
                    baseSide + right,
                    baseVertical + bottom,
                )
                insets
            }
        }
        root.addView(wordmarkStrip())
        root.addView(theme.rule())
        root.addView(tabStrip())
        contentFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(matchParent, 0, 1f)
        }
        root.addView(contentFrame)
        shellRoot = root
        return root
    }

    /** Ask the window to re-dispatch insets (rotation, nav-bar mode change). */
    private fun redispatchInsets() {
        shellRoot?.requestApplyInsets()
    }

    /**
     * The four system-bar inset sides. `WindowInsets.Type` / `getInsets` are
     * API 30+ (and the old `left/top/right/bottom` getters are gone from the
     * API 36 platform), so minSdk 24 devices read the legacy system-window
     * sides instead - there they also cover the IME, which is exactly the
     * resize the old `adjustResize` behaviour produced.
     */
    @Suppress("DEPRECATION")
    private fun insetParts(insets: android.view.WindowInsets): IntArray =
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
            intArrayOf(bars.left, bars.top, bars.right, bars.bottom)
        } else {
            intArrayOf(
                insets.systemWindowInsetLeft,
                insets.systemWindowInsetTop,
                insets.systemWindowInsetRight,
                insets.systemWindowInsetBottom,
            )
        }

    private fun wordmarkStrip(): View {
        val strip = row(matchParent, wrapContent)
        strip.addView(label("OPCODE FIRELINE", 12, theme.textBright, theme.displayFace))
        strip.addView(spacer())
        strip.addView(label(getString(R.string.privacy), 8, theme.water, theme.displayFace).apply {
            setPadding(theme.dp(8), theme.dp(4), theme.dp(8), theme.dp(4))
            contentDescription = "Open FIRELINE privacy policy"
            setOnClickListener {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.privacy_policy_url))))
            }
        })
        strip.addView(label("RF-8", 10, theme.textDim, theme.displayFace))
        val block = column(matchParent, wrapContent)
        block.addView(strip)
        // The tagline sat flush under the wordmark (0 gap): give it its own
        // chrome block gap (milestone 8 line-spacing polish).
        block.addView(label(getString(R.string.tagline), 8, theme.textDim, theme.displayFace).apply {
            layoutParams = LinearLayout.LayoutParams(wrapContent, wrapContent).apply {
                topMargin = theme.chromeBlockGapPx()
            }
        })
        return block
    }

    /** Tab order FILES | MISSION | RESULT (FIRELINE-DESIGN 11.1). */
    private fun tabStrip(): View {
        val strip = row(matchParent, wrapContent).apply {
            setPadding(0, theme.dp(10), 0, theme.dp(10))
        }
        tabs = LinkedHashMap()
        for ((index, name) in listOf("FILES", "MISSION", "RESULT").withIndex()) {
            val tab = label(name, 8, theme.textDim, theme.displayFace).apply {
                background = theme.panelDrawable(fill = theme.secondaryFill, stroke = theme.border)
                setPadding(theme.dp(10), theme.dp(6), theme.dp(10), theme.dp(6))
                gravity = Gravity.CENTER
                contentDescription = name
                setOnClickListener { onTab(name) }
                // Equal thirds of the full row instead of content width
                // (milestone 8): weight 1 with width 0 makes every tab the
                // same width and the strip spans the whole surface.
                layoutParams = LinearLayout.LayoutParams(0, wrapContent, 1f).apply {
                    if (index > 0) marginStart = theme.dp(4)
                }
            }
            tabs[name] = tab
            strip.addView(tab)
        }
        return strip
    }

    private fun onTab(name: String) {
        when (name) {
            "FILES" -> showFiles()
            "MISSION" -> {
                val run = currentRunSurface
                if (run == null) showMission() else {
                    selectTab("MISSION")
                    showContent(run.root)
                }
            }
            "RESULT" -> showResult()
        }
    }

    private fun selectTab(name: String) {
        currentTab = name
        for ((tab, view) in tabs) {
            val active = tab == name
            view.setTextColor(if (active) theme.runText else theme.textDim)
            view.background = theme.panelDrawable(
                fill = if (active) theme.runFill else theme.secondaryFill,
                stroke = if (active) theme.runFill else theme.border,
            )
        }
    }

    private fun showContent(view: View) {
        contentFrame.removeAllViews()
        contentFrame.addView(view)
    }

    // ---- FILES ------------------------------------------------------------------

    private fun showFiles() {
        editorSurface = null
        stopActiveRun()
        selectTab("FILES")
        filesSurface.refresh(selectedFirmwareName)
        showContent(filesSurface.root)
    }

    private fun buildFilesSurface() {
        filesSurface = FilesSurface(
            activity = this,
            theme = theme,
            store = store,
            onNewFile = { promptNewName() },
            onEdit = { file -> openEditor(file, displayName = file.name) },
            onDuplicate = { file ->
                when (val outcome = store.duplicate(file.id)) {
                    is SaveOutcome.Saved -> {
                        filesSurface.setNotice("copied to ${outcome.file.name}")
                        showFiles()
                    }
                    is SaveOutcome.Rejected -> filesSurface.setNotice(outcome.problems.joinToString("  //  "))
                }
            },
            onDelete = { file -> confirmDelete(file) },
            onSelectFirmware = { file -> selectFirmware(file) },
            onImport = { requestImport() },
            onExport = { file -> requestExport(file) },
        )
    }

    // ---- MISSION ----------------------------------------------------------------

    private fun showMission(attention: String? = null) {
        editorSurface = null
        stopActiveRun()
        selectTab("MISSION")
        val surface = MissionSurface(
            activity = this,
            theme = theme,
            store = store,
            selection = firmwareSelection,
            bestStore = bestStore,
            onOpenRun = { mission, firmwareName -> showMissionRun(mission, firmwareName) },
        )
        missionSurface = surface
        showContent(surface.picker(attention))
    }

    /**
     * Open the live run surface (milestone 5). The mission tuple is the
     * mission's own rules + the selected stored firmware source; the session
     * rebuilds a fresh FirelineWorld from it, so every open is a clean run.
     * A stored source that will not assemble (e.g. Arena-only opcodes) is
     * refused with a notice instead of crashing the tab.
     */
    private fun showMissionRun(mission: Mission, firmwareName: String) {
        if (MissionPickerModel.availableById(mission.id) == null) {
            showMission("mission %02d is not included in the free edition".format(mission.id))
            return
        }
        val file = store.find(firmwareName) ?: return showMission("firmware '$firmwareName' is gone  //  pick another")
        val assembly = EditorModel.assembleSource(file.source)
        if (!assembly.ok) {
            return showMission("firmware '${file.name}' does not assemble  //  line ${assembly.errors.firstOrNull()?.first ?: "?"}: ${assembly.errors.firstOrNull()?.second ?: "error"}")
        }
        stopActiveRun()
        val session = MissionSession(mission, file.name, file.source)
        missionSelection.set(mission.id)
        val surface = MissionRunSurface(
            activity = this,
            theme = theme,
            session = session,
            onRunFinished = { summary ->
                bestStore.update(summary)
                runStore.put(summary)
                // Leave the finished world visible. RESULT is an explicit tab
                // choice; returning to MISSION restores this same final grid.
            },
            onMissionList = { showMission() },
        )
        currentRunSurface = surface
        selectTab("MISSION")
        showContent(surface.root)
    }

    /** A live session must not keep ticking while another tab is on screen. */
    private fun stopActiveRun() {
        currentRunSurface?.stopRun()
        currentRunSurface = null
    }

    /** RESULT renders only from the stored RunSummary (12.1: plain data). */
    private fun showResult() {
        editorSurface = null
        currentRunSurface?.stopRun()
        selectTab("RESULT")
        val summary = runStore.get()
        if (summary == null) {
            showPending("RESULT", "no run recorded yet  //  finish a mission run to see the outcome")
            return
        }
        val surface = ResultSurface(
            activity = this,
            theme = theme,
            summary = summary,
            onRetry = {
                val mission = Missions.byId(summary.missionId)
                if (mission == null) showMission("mission ${summary.missionId} is no longer in the catalog")
                else showMissionRun(mission, summary.firmwareName)
            },
            onEditFirmware = {
                val file = store.find(summary.firmwareName)
                if (file == null) showMission("firmware '${summary.firmwareName}' is gone  //  pick another")
                else openEditor(file, displayName = file.name)
            },
            onMissionList = { showMission() },
        )
        showContent(surface.root)
    }

    private fun selectFirmware(file: FirmwareFile) {
        selectedFirmwareName = file.name
        firmwareSelection.set(file.name)
        filesSurface.refresh(selectedFirmwareName)
        filesSurface.setNotice("selected ${file.name} as mission firmware")
        // The MISSION picker shows the SELECTED marker too, so refresh it live.
        if (currentTab == "MISSION") showMission()
    }

    private fun confirmDelete(file: FirmwareFile) {
        AlertDialog.Builder(this)
            .setTitle("DELETE ${file.name}")
            .setMessage("Remove this source from FIRELINE? The file is not exported, so it will be gone.")
            .setPositiveButton("DELETE") { _, _ ->
                store.delete(file.id)
                if (file.name.equals(selectedFirmwareName, ignoreCase = true)) {
                    selectedFirmwareName = null
                    firmwareSelection.clear()
                }
                filesSurface.setNotice("deleted ${file.name}")
                showFiles()
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun promptNewName() {
        promptName("NEW FILE", "UNTITLED", onSave = { name -> openEditor(file = null, displayName = name) })
    }

    private fun promptRename(file: FirmwareFile) {
        promptName("RENAME ${file.name}", file.name.removeSuffix(".ASM"), onSave = { name ->
            when (val outcome = store.rename(file.id, name)) {
                is SaveOutcome.Saved -> {
                    editorSurface?.setEditing(outcome.file)
                    if (file.name.equals(selectedFirmwareName, ignoreCase = true)) {
                        selectedFirmwareName = outcome.file.name
                        firmwareSelection.set(outcome.file.name)
                    }
                    filesSurface.setNotice("renamed to ${outcome.file.name}")
                }
                is SaveOutcome.Rejected -> filesSurface.setNotice(outcome.problems.joinToString("  //  "))
            }
        })
    }

    /** One text dialog for every name the app needs (new file, rename). */
    private fun promptName(title: String, prefill: String, onSave: (String) -> Unit) {
        val input = EditText(this).apply {
            setText(prefill)
            setTextColor(theme.text)
            typeface = theme.workFace
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(input)
            .setPositiveButton("OK") { _, _ -> onSave(input.text.toString()) }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    // ---- editor -----------------------------------------------------------------

    private fun openEditor(file: FirmwareFile?, displayName: String) {
        selectTab("FILES")
        val surface = EditorSurface(
            activity = this,
            theme = theme,
            store = store,
            file = file,
            displayName = displayName,
            onClose = { showFiles() },
            onSaved = { saved ->
                if (selectedFirmwareName == null) selectFirmwareQuietly(saved)
                filesSurface.refresh(selectedFirmwareName)
            },
            onExport = { name, source -> requestExport(name, source) },
            onRename = { target -> promptRename(target) },
        )
        editorSurface = surface
        showContent(surface.root)
    }

    private fun selectFirmwareQuietly(file: FirmwareFile) {
        selectedFirmwareName = file.name
        firmwareSelection.set(file.name)
    }

    private fun showPending(tab: String, message: String) {
        selectTab(tab)
        val card = column(matchParent, wrapContent).apply {
            background = theme.panelDrawable()
            setPadding(theme.dp(10), theme.dp(10), theme.dp(10), theme.dp(10))
        }
        card.addView(label(message, 8, theme.text, theme.workFace))
        showContent(card)
    }

    // ---- SAF import / export (no storage permissions) ---------------------------

    private fun requestImport() {
        try {
            startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    // `text/plain` looked right but DocumentsUI renders every
                    // item whose inferred MIME differs as DISABLED (enabled=false
                    // in the accessibility tree), so exported `NAME.ASM` files
                    // (stored as octet-stream BIN) could never be picked
                    // (milestone 7 gate finding). The store validates and
                    // sanitizes whatever arrives anyway.
                    type = "*/*"
                },
                REQ_IMPORT,
            )
        } catch (e: android.content.ActivityNotFoundException) {
            filesSurface.setNotice("no documents UI on this device  //  import unavailable")
        }
    }

    private fun requestExport(file: FirmwareFile?) {
        if (file == null) {
            filesSurface.setNotice("nothing to export  //  no files in store")
            return
        }
        requestExport(file.name, file.source)
    }

    private fun requestExport(name: String, source: String) {
        pendingExport = name to source
        try {
            startActivityForResult(
                Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    // Generic type + explicit `.ASM` title: DocumentsUI only keeps
                    // a title's extension when it matches the MIME type, so the
                    // old text/plain pairing appended `.txt` to `NAME.ASM`
                    // (milestone 7 gate finding; FirmwareFileNames.exportFileName).
                    type = "application/octet-stream"
                    putExtra(Intent.EXTRA_TITLE, FirmwareFileNames.exportFileName(name))
                },
                REQ_EXPORT,
            )
        } catch (e: android.content.ActivityNotFoundException) {
            pendingExport = null
            filesSurface.setNotice("no documents UI on this device  //  export unavailable")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) {
            if (requestCode == REQ_EXPORT) pendingExport = null
            return
        }
        val uri = data?.data
        when (requestCode) {
            REQ_IMPORT -> handleImport(uri)
            REQ_EXPORT -> handleExport(uri)
        }
    }

    private fun handleImport(uri: Uri?) {
        if (uri == null) return
        val text = try {
            contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (e: Exception) {
            null
        }
        if (text == null) {
            filesSurface.setNotice("import failed  //  could not read the document")
            return
        }
        when (val outcome = store.save(displayName(uri), text)) {
            is SaveOutcome.Saved -> {
                val renamed = outcome.requestedName?.let { "  //  ${it} was taken" } ?: ""
                filesSurface.refresh(selectedFirmwareName)
                filesSurface.setNotice("imported ${outcome.file.name}${renamed}")
            }
            is SaveOutcome.Rejected -> filesSurface.setNotice("import refused  //  ${outcome.problems.joinToString("  //  ")}")
        }
    }

    private fun handleExport(uri: Uri?) {
        val payload = pendingExport
        pendingExport = null
        if (uri == null || payload == null) return
        val ok = try {
            contentResolver.openOutputStream(uri)?.use { stream ->
                stream.write(payload.second.toByteArray(Charsets.UTF_8))
                true
            } ?: false
        } catch (e: Exception) {
            false
        }
        filesSurface.setNotice(
            if (ok) "exported ${payload.first}  //  ${payload.second.length} characters as UTF-8"
            else "export failed  //  could not write the document"
        )
    }

    /** Provider display name, falling back to the last path segment. */
    private fun displayName(uri: Uri): String {
        val raw = if (DocumentsContract.isDocumentUri(this, uri)) {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                val column = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                if (c.moveToFirst()) c.getString(column) else null
            } ?: uri.lastPathSegment ?: "IMPORTED.ASM"
        } else {
            uri.lastPathSegment ?: "IMPORTED.ASM"
        }
        // DocumentsUI can hand back `NAME.ASM.txt` from a pre-fix export (or any
        // text wrapper a user saved around a source); strip the wrapper so the
        // store name stays `NAME.ASM` instead of sanitizing to `NAME-ASM.ASM`.
        val unwrapped = raw.removeSuffix(".txt").removeSuffix(".TXT")
        return unwrapped.ifBlank { raw }
    }

    // ---- view builders (house style) --------------------------------------------

    private fun label(value: String, sizeSp: Int, color: Int, face: Typeface): TextView =
        TextView(this).apply {
            text = value
            setTextColor(color)
            typeface = face
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp.toFloat())
            includeFontPadding = false
            // Chrome labels that wrap (tagline, notices) read cramped with the
            // font padding off; the extra leading only applies between lines.
            setLineSpacing(theme.chromeLineSpacingPx().toFloat(), 1f)
            layoutParams = LinearLayout.LayoutParams(wrapContent, wrapContent)
        }

    private fun row(width: Int, height: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = ViewGroup.LayoutParams(width, height)
    }

    private fun column(width: Int, height: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = ViewGroup.LayoutParams(width, height)
    }

    private fun spacer(): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
    }

    private val matchParent = ViewGroup.LayoutParams.MATCH_PARENT
    private val wrapContent = ViewGroup.LayoutParams.WRAP_CONTENT

    companion object {
        private const val PREFS = "fireline_ui"
        private const val PREF_STARTER_SEEDED = "starter_firmware_seeded"
        private const val REQ_IMPORT = 41
        private const val REQ_EXPORT = 42
    }
}
