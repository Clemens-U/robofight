package robofight.fireline.app

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
import robofight.fireline.app.files.FirmwareRepository
import robofight.fireline.app.files.SaveOutcome
import robofight.fireline.app.mission.BestBadgeStore
import robofight.fireline.app.mission.FirmwareSelection
import robofight.fireline.app.mission.MissionSelection
import robofight.fireline.app.mission.MissionSurface
import robofight.fireline.app.mission.SharedPreferencesValues
import robofight.fireline.R
import robofight.fireline.Mission

/**
 * FIRELINE MainActivity (PLAN-RF8-MULTIAPP.md 9.2): programmatic views only, no
 * XML layouts, no androidx - the Arena house style. Owns the shell (wordmark
 * strip, tagline, FILES | MISSION | RESULT tabs), the FIRELINE-private file
 * store, and the surfaces built so far: FILES, the editor, and the MISSION
 * picker + run setup (milestone 4).
 *
 * Milestone 4 state: MISSION shows the five authored mission cards with their
 * best-result badges and the firmware picker; opening a run setup persists the
 * selected mission id (12.1) next to the already-persisted firmware selection.
 * The live grid/telemetry/control bar is milestone 5 and the RESULT surface is
 * milestone 6; RESULT still renders its pending notice.
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
    private lateinit var contentFrame: FrameLayout
    private lateinit var tabs: MutableMap<String, TextView>
    private lateinit var filesSurface: FilesSurface

    private var selectedFirmwareName: String? = null
    private var currentTab: String = "FILES"
    private var missionSurface: MissionSurface? = null
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
        selectedFirmwareName = firmwareSelection.get()
        buildFilesSurface()
        setContentView(buildShell())
        showFiles()
    }

    // ---- shell ------------------------------------------------------------------

    private fun buildShell(): View {
        val root = column(matchParent, matchParent).apply {
            setBackgroundColor(theme.field)
            setPadding(theme.dp(12), theme.dp(10), theme.dp(12), theme.dp(10))
        }
        root.addView(wordmarkStrip())
        root.addView(theme.rule())
        root.addView(tabStrip())
        contentFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(matchParent, 0, 1f)
        }
        root.addView(contentFrame)
        return root
    }

    private fun wordmarkStrip(): View {
        val strip = row(matchParent, wrapContent)
        strip.addView(label("OPCODE FIRELINE", 12, theme.textBright, theme.displayFace))
        strip.addView(spacer())
        strip.addView(label("RF-8", 10, theme.textDim, theme.displayFace))
        val block = column(matchParent, wrapContent)
        block.addView(strip)
        block.addView(label(getString(R.string.tagline), 8, theme.textDim, theme.displayFace))
        return block
    }

    /** Tab order FILES | MISSION | RESULT (FIRELINE-DESIGN 11.1). */
    private fun tabStrip(): View {
        val strip = row(matchParent, wrapContent).apply { setPadding(0, theme.dp(8), 0, theme.dp(8)) }
        tabs = LinkedHashMap()
        for (name in listOf("FILES", "MISSION", "RESULT")) {
            val tab = label(name, 8, theme.textDim, theme.displayFace).apply {
                background = theme.panelDrawable(fill = theme.secondaryFill, stroke = theme.border)
                setPadding(theme.dp(10), theme.dp(6), theme.dp(10), theme.dp(6))
                gravity = Gravity.CENTER
                contentDescription = name
                setOnClickListener { onTab(name) }
            }
            tabs[name] = tab
            strip.addView(tab)
        }
        return strip
    }

    private fun onTab(name: String) {
        when (name) {
            "FILES" -> showFiles()
            "MISSION" -> showMission()
            "RESULT" -> showPending("RESULT", getString(R.string.result_pending))
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

    private fun showMission() {
        editorSurface = null
        selectTab("MISSION")
        val surface = MissionSurface(
            activity = this,
            theme = theme,
            store = store,
            selection = firmwareSelection,
            missionSelection = missionSelection,
            bestStore = bestStore,
            onOpenRun = { mission, firmwareName -> showMissionSetup(mission, firmwareName) },
            onSelectFirmware = { file -> selectFirmware(file) },
            onBackToPicker = { showMission() },
        )
        missionSurface = surface
        showContent(surface.picker())
    }

    private fun showMissionSetup(mission: Mission, firmwareName: String) {
        selectTab("MISSION")
        showContent(missionSurface!!.runSetup(mission, firmwareName))
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
                    type = "text/plain"
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
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TITLE, name)
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
        if (DocumentsContract.isDocumentUri(this, uri)) {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                val column = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                if (c.moveToFirst()) return c.getString(column)
            }
        }
        return uri.lastPathSegment ?: "IMPORTED.ASM"
    }

    // ---- view builders (house style) --------------------------------------------

    private fun label(value: String, sizeSp: Int, color: Int, face: Typeface): TextView =
        TextView(this).apply {
            text = value
            setTextColor(color)
            typeface = face
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp.toFloat())
            includeFontPadding = false
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
        private const val REQ_IMPORT = 41
        private const val REQ_EXPORT = 42
    }
}
