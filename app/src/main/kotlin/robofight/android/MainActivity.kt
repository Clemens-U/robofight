package robofight.android

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Canvas
import android.net.Uri
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import java.io.File
import robofight.world.Palette
import robofight.world.Presets
import robofight.vm.Vm
import robofight.world.Bot

/**
 * Two-screen native UI for Opcode Arena.
 *
 * SHELL is the firmware workspace (file terminal + editor). RUN is the arena.
 * Keeping them separate gives text input room to resize above the soft keyboard
 * and lets the arena use the full display while a fight is running.
 */
class MainActivity : Activity() {

    private enum class AppMode { SHELL, RUN }

    private lateinit var shellScreen: LinearLayout
    private lateinit var runScreen: LinearLayout
    private lateinit var terminalPanel: LinearLayout
    private lateinit var editorPanel: LinearLayout
    private lateinit var shellTab: Button
    private lateinit var runTab: Button
    private lateinit var shellContext: TextView

    private lateinit var arena: ArenaView
    private lateinit var btnA: Button
    private lateinit var btnB: Button
    private lateinit var btnRun: Button
    private lateinit var btnStep: Button
    private lateinit var btnReset: Button
    private lateinit var btnSound: Button
    private lateinit var statA: TextView
    private lateinit var statB: TextView

    // Execution monitor: both code panes are always visible (side by side).
    // monHeadA/B hold the "A ▸ BOT" header; codeA/B hold the source window +
    // register line for that combatant. Refreshed every frame by updateMonitor().
    private lateinit var monHeadA: TextView
    private lateinit var monHeadB: TextView
    private lateinit var codeA: TextView
    private lateinit var codeB: TextView
    private lateinit var regA: TextView
    private lateinit var regB: TextView

    private lateinit var editor: EditText
    private lateinit var btnSave: Button
    private lateinit var btnNew: Button
    private lateinit var btnTerminal: Button
    private lateinit var btnFight: Button

    private lateinit var btnDir: Button
    private lateinit var btnHelp: Button
    private lateinit var btnEditCurrent: Button
    private lateinit var btnGo: Button
    private lateinit var out: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var cmd: EditText
    private var outText: TextView? = null

    private lateinit var status: TextView
    private lateinit var controller: RunController
    private lateinit var store: BotFiles
    private lateinit var sound: RetroSound
    private val handler = Handler(Looper.getMainLooper())
    private val pixelTypeface: Typeface by lazy {
        Typeface.createFromAsset(assets, "fonts/PressStart2P-Regular.ttf")
    }

    private var mode = AppMode.SHELL
    private var currentFile: String? = null
    private var editorDirty = false
    private var autoRunning = false
    /**
     * Export in flight: the firmware file the pending folder picker writes,
     * or "" for the all-firmware ZIP. Cleared as soon as the picker answers.
     */
    private var pendingExportName: String? = null

    /**
     * The RUN-screen bot slots: the five engine presets first, then one slot
     * per user firmware file (MYBOT, BOT1, ...) in name order — rebuilt by
     * [refreshSlots] whenever the file list changes.
     *
     * File slots resolve their source from the [BotFiles] store at fight time
     * (see [sourceFor]), so the saved file always wins over unsaved editor
     * text.
     */
    private val slots = ArrayList<BotSlot>()
    private var ai = 0
    private var bi = 1

    private val tickLoop = object : Runnable {
        override fun run() {
            val world = controller.world ?: return
            if (!world.finished) {
                // The engine heat and damage rates are calibrated for 10 ticks/sec.
                controller.step(1)
                updateStats()
            }
            if (!world.finished) {
                handler.postDelayed(this, 100L)
            } else {
                autoRunning = false
                status.text = controller.status
                btnRun.isEnabled = true
                btnRun.text = "RUN AGAIN"
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = C_BG
        window.navigationBarColor = C_BG
        setContentView(buildLayout())

        store = BotFiles(this)
        seedFromAssets()
        if (savedInstanceState != null) {
            currentFile = savedInstanceState.getString(STATE_FILE)
            editor.setText(savedInstanceState.getString(STATE_SOURCE, editor.text.toString()))
            editorDirty = savedInstanceState.getBoolean(STATE_DIRTY)
        }

        controller = RunController()
        arena.controller = controller
        controller.onFrame = { runOnUiThread { updateStats() } }
        // Hit effects: the view renders sparks / shake / flash; we play the
        // matching 8-bit SFX (or the shield "tink" when blocked).
        controller.onHit = { e ->
            arena.onHitEvent(e)
            if (e.blocked) sound.playBlock() else sound.playHit()
        }
        // One KO / round-end boom (win or draw).
        controller.onFinished = { sound.playBoom() }
        sound = RetroSound.get(this)
        btnSound.text = "SND ON"

        editor.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                editorDirty = true
                updateShellContext()
            }
        })

        shellTab.setOnClickListener { showShellTerminal() }
        runTab.setOnClickListener { showRunMode(startFight = false) }
        btnA.setOnClickListener { ai = (ai + 1) % slots.size; updateBtnTexts() }
        btnB.setOnClickListener { bi = (bi + 1) % slots.size; updateBtnTexts() }
        btnRun.setOnClickListener { doRun() }
        btnStep.setOnClickListener { doStep() }
        btnReset.setOnClickListener { doReset() }
        btnSound.setOnClickListener { toggleSound() }

        btnSave.setOnClickListener { saveFile() }
        btnNew.setOnClickListener { doNew(autoName()) }
        btnTerminal.setOnClickListener { showShellTerminal() }
        btnFight.setOnClickListener { showRunMode(startFight = true) }

        btnDir.setOnClickListener { runCmd("DIR") }
        btnHelp.setOnClickListener { runCmd("HELP") }
        btnEditCurrent.setOnClickListener {
            val name = currentFile
            if (name == null) runCmd("NEW ${autoName()}") else showEditor()
        }
        btnGo.setOnClickListener { submitCommand() }
        cmd.setOnEditorActionListener { _, _, _ -> submitCommand(); true }

        updateBtnTexts()
        if (savedInstanceState?.getBoolean(STATE_RUN_MODE) == true) {
            setMode(AppMode.RUN)
        } else {
            showShellTerminal()
        }
        printBoot()
        runCmd("DIR")
        updateStats()
    }

    override fun onDestroy() {
        handler.removeCallbacks(tickLoop)
        sound.release()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_FILE, currentFile)
        outState.putString(STATE_SOURCE, editor.text.toString())
        outState.putBoolean(STATE_DIRTY, editorDirty)
        outState.putBoolean(STATE_RUN_MODE, mode == AppMode.RUN)
        super.onSaveInstanceState(outState)
    }

    private fun submitCommand() {
        val line = cmd.text.toString().trim()
        cmd.text.clear()
        if (line.isNotEmpty()) runCmd(line)
    }

    private fun autoName(): String {
        var n = 1
        while (store.exists("BOT$n")) n++
        return "BOT$n"
    }

    private fun runCmd(line: String) {
        showShellTerminal(clearFocus = false)
        printLine("RF:\\> $line")
        val parts = line.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (parts.isNotEmpty()) dispatch(parts[0].uppercase(), parts.getOrElse(1) { "" })
        commitText()
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun dispatch(command: String, arg: String) {
        when (command) {
            "DIR", "D", "LS" -> printDir()
            "EDIT", "E", "OPEN" -> {
                if (arg.isEmpty()) printLine("USAGE: EDIT <NAME>") else openFile(arg)
            }
            "DEL", "DELETE", "RM" -> {
                if (arg.isEmpty()) {
                    printLine("USAGE: DEL <NAME>")
                } else if (!store.exists(arg)) {
                    printLine("NO SUCH FILE: $arg")
                } else {
                    val wasFightBot = slots.any {
                        it.isFile && it.name.equals(arg, ignoreCase = true)
                    }
                    if (currentFile.equals(arg, ignoreCase = true)) {
                        currentFile = null
                        editor.text.clear()
                        editorDirty = false
                    }
                    printLine(if (store.delete(arg)) "DELETED $arg" else "DELETE FAILED: $arg")
                    refreshSlots()
                    if (wasFightBot) printLine("  // SLOT LIST UPDATED  // A/B NOW CYCLES REMAINING BOTS")
                    updateShellContext()
                }
            }
            "NEW", "N" -> if (arg.isEmpty()) printLine("USAGE: NEW <NAME>") else doNew(arg)
            "EXPORT", "SAVE2", "SAVE-OUT" -> export(arg)
            "RUN", "FIGHT" -> showRunMode(startFight = true)
            "HELP", "?" -> printHelp()
            "CLEAR", "CLS" -> { out.removeAllViews(); outText = null }
            else -> printLine("UNKNOWN COMMAND: $command  // TRY HELP")
        }
    }

    private fun openFile(name: String) {
        if (!store.exists(name)) { printLine("NO SUCH FILE: $name"); return }
        val text = store.read(name) ?: ""
        val open = currentFile
        if (editorDirty && open != null && !open.equals(name, true) && editor.text.isNotEmpty()) {
            store.upsert(open, editor.text.toString())
            printLine("SAVED $open  // UNSAVED CHANGES KEPT")
        }
        editor.setText(text)
        editor.setSelection(0)
        editorDirty = false
        currentFile = name.uppercase()
        status.text = "EDITING ${currentFile}"
        showEditor()
        // EditText may scroll the caret into view after focus/layout. Post the
        // reset so a newly loaded file consistently opens at line one.
        editor.post {
            editor.setSelection(0)
            editor.scrollTo(0, 0)
        }
    }

    private fun doNew(name: String) {
        if (store.exists(name)) {
            printLine("$name EXISTS  // OPENING")
            openFile(name)
            return
        }
        val open = currentFile
        if (editorDirty && open != null && editor.text.isNotEmpty()) {
            store.upsert(open, editor.text.toString())
            printLine("SAVED $open  // UNSAVED CHANGES KEPT")
        }
        // A new bot ships with the RF-8 cheat sheet as comments — the program
        // is empty, so it assembles fine and RUN works immediately.
        store.upsert(name, Firmware.NEW_TEMPLATE)
        editor.setText(Firmware.NEW_TEMPLATE)
        editor.setSelection(0)
        currentFile = name.uppercase()
        editorDirty = false
        refreshSlots()
        printLine("CREATED ${currentFile}  // CHEAT SHEET LOADED")
        status.text = "NEW FILE ${currentFile}"
        showEditor()
    }

    private fun saveFile() {
        val name = currentFile
        if (name == null) {
            status.text = "NO FILE OPEN  // USE NEW <NAME>"
            showShellTerminal()
            printLine("NO FILE OPEN  // USE NEW <NAME>")
            commitText()
            return
        }
        store.upsert(name, editor.text.toString())
        editorDirty = false
        status.text = "SAVED $name  // ${store.size(name)} BYTES"
        updateShellContext()
    }

    /**
     * EXPORT: copies firmware out of the file store into a folder the user
     * picks in the standard Android folder picker (SAF — no storage
     * permission needed, the picker grants write access to that folder):
     *
     *  - `EXPORT` / `EXPORT ALL` / `EXPORT FULL` — every file as one ZIP,
     *    `robofight-export-<stamp>.zip`.
     *  - `EXPORT <name>` — that one file, under its own name.
     */
    private fun export(arg: String) {
        val a = arg.uppercase()
        val name = if (a.isEmpty() || a == "ALL" || a == "FULL" || a == "*") "" else arg
        if (name.isNotEmpty()) {
            if (!store.exists(name)) {
                printLine("NO SUCH FILE: $name  // TRY EXPORT FOR A ZIP OF ALL")
                return
            }
        } else if (store.count() == 0) {
            printLine("NOTHING TO EXPORT  // NO FILES IN STORE")
            return
        }
        pendingExportName = name
        status.text = "EXPORT  // PICK A FOLDER"
        showShellTerminal(clearFocus = false)
        try {
            // NOTE: no CATEGORY_OPENABLE — the documents-ui filter for
            // OPEN_DOCUMENT_TREE matches CATEGORY_DEFAULT only, so adding
            // OPENABLE makes the implicit intent UNRESOLVABLE. The system
            // returns START_INTENT_NOT_RESOLVED (-91) without throwing, the
            // picker silently never opens, and the fallback path takes over.
            startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                }, REQ_EXPORT_TREE
            )
        } catch (e: ActivityNotFoundException) {
            // No documents UI on this device (e.g. plain AOSP emulators):
            // fall back to the app's own external files directory instead of
            // crashing.
            pendingExportName = null
            fallbackExport(name)
            return
        }
        // Only say "pick a folder" once the picker actually launched — a
        // printLine before the try would leak into the fallback output too.
        printLine("PICK A FOLDER FOR THE EXPORT ...")
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_EXPORT_TREE) return
        // null = the fallback already wrote the file (no documents UI), or the
        // export was never armed — a late CANCELED for the failed launch must
        // not overwrite the success message.
        val name = pendingExportName ?: return
        pendingExportName = null
        if (resultCode != Activity.RESULT_OK) {
            printLine("EXPORT CANCELLED")
            status.text = "EXPORT CANCELLED"
            commitText()
            return
        }
        val tree = data?.data ?: run {
            printLine("EXPORT FAILED  // NO FOLDER SELECTED")
            status.text = "EXPORT FAILED"
            commitText()
            return
        }
        try {
            val grant = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            contentResolver.takePersistableUriPermission(tree, grant)
            // The raw tree URI is not queryable (DocumentsProvider has no
            // URI pattern for a bare tree/*) — query the root *document*
            // URI that the tree stands for.
            val dirName = treeDocUri(tree)?.let {
                contentResolver.query(it, null, null, null, null)
                    ?.use { c ->
                        if (c.moveToFirst()) c.getString(c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME))
                        else "FOLDER"
                    }
            } ?: "FOLDER"
            if (name.isEmpty()) {
                val files = store.all()
                val bytes = FirmwareExport.zipBytes(files)
                val file = FirmwareExport.zipName()
                if (!writeExportDoc(tree, file, FirmwareExport.MIME_ZIP, bytes)) {
                    printLine("EXPORT FAILED  // CANNOT WRITE $file TO THE PICKED FOLDER")
                    status.text = "EXPORT FAILED"
                    commitText()
                    return
                }
                printLine("EXPORTED ${files.size} FILE(S) AS $file")
                printLine("  ZIP ${bytes.size} BYTES  // $dirName")
                status.text = "EXPORTED ${files.size} FILE(S)"
            } else {
                val bytes = (store.read(name) ?: "").toByteArray(Charsets.UTF_8)
                val file = FirmwareExport.safeName(name)
                if (!writeExportDoc(tree, file, FirmwareExport.MIME_SOURCE, bytes)) {
                    printLine("EXPORT FAILED  // CANNOT WRITE $file TO THE PICKED FOLDER")
                    status.text = "EXPORT FAILED"
                    commitText()
                    return
                }
                printLine("EXPORTED $name AS $file")
                printLine("  ${bytes.size} BYTES  // $dirName")
                status.text = "EXPORTED $name"
            }
            commitText()
        } catch (e: Exception) {
            printLine("EXPORT FAILED: ${e.message}")
            status.text = "EXPORT FAILED"
            commitText()
        }
        showShellTerminal(clearFocus = false)
    }

    /**
     * Creates [fileName] inside the picked [tree] folder and writes [bytes]
     * to it. Returns false when the file can't be created or written (the
     * caller reports the failure) — [DocumentsContract.createDocument] may
     * return null, so the null case is handled here, not with a cast.
     *
     * The picker hands us a *tree* URI, but createDocument needs a *document*
     * URI: passed the tree URI raw, the provider throws
     * UnsupportedOperationException ("Unsupported Uri .../tree/...").
     * buildDocumentUriUsingTree is the documented conversion (the same one
     * DocumentFile.fromTreeUri performs internally).
     */
    private fun writeExportDoc(tree: Uri, fileName: String, mimeType: String, bytes: ByteArray): Boolean {
        val rootDoc = treeDocUri(tree) ?: return false
        val doc = DocumentsContract.createDocument(contentResolver, rootDoc, fileName, mimeType) ?: return false
        return try {
            val out = contentResolver.openOutputStream(doc) ?: return false
            out.use { it.write(bytes) }
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * The root *document* URI that a picker's [tree] URI stands for —
     * `tree/<id>` → `document/<id>`. Both provider call sites (querying the
     * folder's display name and createDocument) need this form; the raw
     * `tree/...` URI matches no DocumentsProvider URI pattern and throws
     * UnsupportedOperationException. Null when the URI is not a tree URI at
     * all.
     */
    private fun treeDocUri(tree: Uri): Uri? = try {
        DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
    } catch (e: Exception) {
        null
    }

    /**
     * No documents UI available (plain AOSP emulator): write straight to the
     * app's external files directory instead — same content, a fixed
     * location, and the terminal tells the user where it landed.
     */
    private fun fallbackExport(name: String) {
        try {
            val root = getExternalFilesDir(null) ?: filesDir
            if (name.isEmpty()) {
                val files = store.all()
                val bytes = FirmwareExport.zipBytes(files)
                val target = File(root, FirmwareExport.zipName())
                target.writeBytes(bytes)
                printLine("EXPORTED ${files.size} FILE(S) AS ${target.name}")
                printLine("  ZIP ${bytes.size} BYTES  // ${root.absolutePath}")
                status.text = "EXPORTED ${files.size} FILE(S)"
            } else {
                val bytes = (store.read(name) ?: "").toByteArray(Charsets.UTF_8)
                val target = File(root, FirmwareExport.safeName(name))
                target.writeBytes(bytes)
                printLine("EXPORTED $name AS ${target.name}")
                printLine("  ${bytes.size} BYTES  // ${root.absolutePath}")
                status.text = "EXPORTED $name"
            }
            commitText()
        } catch (e: Exception) {
            printLine("EXPORT FAILED: ${e.message}")
            status.text = "EXPORT FAILED"
            commitText()
        }
    }

    private fun newOutText() = terminalText(12f, C_GREEN)

    private fun commitText() {
        val text = outText ?: return
        if (text.text.isNotEmpty()) out.addView(text)
        outText = null
        scroll.requestLayout()
    }

    private fun printLine(line: String) {
        val text = outText ?: newOutText().also { outText = it }
        if (text.text.isNotEmpty()) text.append("\n")
        text.append(line)
    }

    private fun printBoot() {
        printLine("OPCODE ARENA OS 0.4  // RF-8 COMBAT SYSTEM")
        printLine("MEM 64K  GRID 20x20  LINK READY")
        printLine("TYPE HELP FOR AVAILABLE COMMANDS")
        printLine("")
        commitText()
    }

    private fun printDir() {
        commitText()
        val files = store.list()
        if (files.isEmpty()) { printLine("  NO FILES  // NEW <NAME>"); return }
        printLine("  NAME                 SIZE   MODIFIED")
        commitText()
        for (file in files) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(3), 0, dp(3))
                isClickable = true
                isFocusable = true
                setOnClickListener { printLine("OPENING ${file.name} ..."); openFile(file.name) }
            }
            val name = terminalText(12f, C_BRIGHT).apply {
                text = "  > ${file.name}"
                setSingleLine(true)
                ellipsize = TextUtils.TruncateAt.END
            }
            val bytes = terminalText(12f, C_GREEN).apply { text = file.bytes.toString().padStart(6) }
            val modified = terminalText(11f, C_DIM).apply { text = "  ${store.fmtTime(file.modified)}" }
            row.addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(bytes)
            row.addView(modified)
            out.addView(row)
        }
        printLine("  // TAP A FILE TO EDIT")
    }

    private fun printHelp() {
        printLine("DIR              LIST FIRMWARE (PRESETS + YOUR BOTS)")
        printLine("EDIT <NAME>      OPEN IN EDITOR (HUNTER.asm, MYBOT, BOT1, …)")
        printLine("NEW <NAME>       CREATE FIRMWARE (STARTS WITH CHEAT SHEET)")
        printLine("DEL <NAME>       DELETE FIRMWARE")
        printLine("EXPORT           PICK A FOLDER, ZIP-EXPORT ALL FIRMWARE THERE")
        printLine("EXPORT <NAME>    PICK A FOLDER, SAVE ONE FIRMWARE FILE THERE")
        printLine("RUN              ENTER ARENA + FIGHT")
        printLine("CLEAR            CLEAR TERMINAL")
        printLine("HELP             SHOW COMMANDS")
        printLine("")
        printLine("IN RUN MODE: A/B SELECT ANY BOT, PRESET OR FILE")
    }

    private fun showShellTerminal(clearFocus: Boolean = true) {
        // A fight only executes while its screen is visible. Keep autoRunning so
        // returning to RUN resumes the same world rather than starting over.
        handler.removeCallbacks(tickLoop)
        setMode(AppMode.SHELL)
        terminalPanel.visibility = View.VISIBLE
        editorPanel.visibility = View.GONE
        if (clearFocus) cmd.clearFocus()
        updateShellContext()
    }

    private fun showEditor() {
        handler.removeCallbacks(tickLoop)
        setMode(AppMode.SHELL)
        terminalPanel.visibility = View.GONE
        editorPanel.visibility = View.VISIBLE
        updateShellContext()
        editor.requestFocus()
    }

    private fun showRunMode(startFight: Boolean) {
        setMode(AppMode.RUN)
        hideKeyboard()
        if (startFight) {
            doRun()
        } else if (autoRunning && controller.world?.finished == false) {
            handler.removeCallbacks(tickLoop)
            handler.postDelayed(tickLoop, 100L)
        }
    }

    private fun setMode(next: AppMode) {
        mode = next
        shellScreen.visibility = if (next == AppMode.SHELL) View.VISIBLE else View.GONE
        runScreen.visibility = if (next == AppMode.RUN) View.VISIBLE else View.GONE
        styleModeTab(shellTab, next == AppMode.SHELL)
        styleModeTab(runTab, next == AppMode.RUN)
    }

    private fun hideKeyboard() {
        currentFocus?.let { focus ->
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(focus.windowToken, 0)
            focus.clearFocus()
        }
    }

    private fun updateShellContext() {
        if (!::shellContext.isInitialized) return
        val file = currentFile ?: "NO FILE"
        shellContext.text = "WORKSPACE / $file${if (editorDirty) " *" else ""}"
    }

    /**
     * Seeds the firmware library (and backfills files after upgrades):
     *
     *  - `MYBOT` — your editable bot, from assets/MYBOT.asm.
     *  - `HUNTER.asm` … `WALKER.asm` — the engine presets, from the
     *    `firmware/` source files (nicer comments than the embedded strings;
     *    packaged into the APK under assets/firmware/); falls back to the
     *    [Presets] source if the asset is missing.
     *
     * Existing files are never overwritten, so edits survive app updates.
     */
    private fun seedFromAssets() {
        if (!store.exists("MYBOT")) {
            store.upsert("MYBOT", loadAsset("MYBOT.asm") ?: Presets.WALKER)
        }
        for (preset in Presets.all()) {
            val name = "${preset.name}.asm"
            if (!store.exists(name)) {
                val source = loadAsset("firmware/${preset.name}.asm") ?:
                    presetHeader(preset.name) + preset.firmware
                store.upsert(name, source)
            }
        }
        refreshSlots()
        store.read("MYBOT")?.takeIf { it.isNotEmpty() }?.let {
            editor.setText(it)
            editor.setSelection(0)
            editorDirty = false
            currentFile = "MYBOT"
        }
    }

    /** Short comment header written above a preset's code when no firmware file exists. */
    private fun presetHeader(name: String) =
        "; OPCODE ARENA PRESET — ${name}\n" +
        "; Starter firmware from the engine. Edit freely, then SAVE;\n" +
        "; your version stays in the file store and is what RUN uses.\n\n"

    /**
     * Rebuild the user-file slots that follow the five preset slots: one per
     * firmware file in the store, in name order. A/B selections are kept
     * clamped to the last slot when the file an index referenced disappears.
     */
    private fun refreshSlots() {
        slots.clear()
        slots.addAll(Presets.all().map { BotSlot(it.name, it.glyph, it.color, it.firmware) })
        for (file in store.list()) {
            val (glyph, color) = slotIdentity(file.name)
            slots.add(BotSlot(file.name, glyph, color, store.read(file.name) ?: "", isFile = true))
        }
        if (ai >= slots.size) ai = slots.size - 1
        if (bi >= slots.size) bi = slots.size - 1
    }

    /**
     * Glyph + palette color for a user-file slot: files named after a preset
     * (HUNTER.asm, …) reuse that preset's identity, MYBOT keeps its yellow
     * 'M', and everything else gets a stable color from its name.
     */
    private fun slotIdentity(name: String): Pair<Char, Int> {
        val preset = Presets.all().firstOrNull {
            it.name.equals(name.removeSuffix(".asm"), ignoreCase = true)
        }
        if (preset != null) return preset.glyph to preset.color
        if (name.equals("MYBOT", ignoreCase = true)) return 'M' to Palette.YELLOW
        return (name.firstOrNull() ?: '?') to ((name.hashCode() and 0x7FFFFFFF) % 14 + 1)
    }

    /**
     * Resolve a slot's firmware at fight time: presets carry their built-in
     * source, user files are read fresh from the [BotFiles] store — so the
     * saved file always wins over unsaved editor text.
     */
    private fun sourceFor(slot: BotSlot): String =
        if (slot.isFile) store.read(slot.name) ?: "" else slot.firmware

    private fun updateBtnTexts() {
        btnA.text = "A  ${slots[ai].name}  >"
        btnB.text = "B  ${slots[bi].name}  >"
        if (::statA.isInitialized && ::controller.isInitialized) updateStats()
    }

    private fun toggleSound() {
        val next = !sound.isOn()
        sound.on(next)
        btnSound.text = if (next) "SND ON" else "SND OFF"
        styleModeTab(btnSound, next)
        if (next) sound.playHit() // audible confirmation
    }

    private fun doRun() {
        handler.removeCallbacks(tickLoop)
        if (!controller.start(slots[ai], slots[bi], ::sourceFor)) {
            autoRunning = false
            status.text = "ASSEMBLER ERROR  // ${controller.status.uppercase()}"
            btnRun.isEnabled = true
            btnRun.text = "RUN"
            return
        }
        autoRunning = true
        btnRun.isEnabled = false
        btnRun.text = "RUNNING"
        updateStats()
        handler.postDelayed(tickLoop, 100L)
    }

    private fun doStep() {
        handler.removeCallbacks(tickLoop)
        autoRunning = false
        btnRun.isEnabled = true
        btnRun.text = "RUN"
        if (controller.world == null) {
            if (!controller.start(slots[ai], slots[bi], ::sourceFor)) {
                status.text = "ASSEMBLER ERROR  // ${controller.status.uppercase()}"
                return
            }
        }
        val world = controller.world ?: return
        if (!world.finished) controller.step(1)
        updateStats()
    }

    private fun doReset() {
        handler.removeCallbacks(tickLoop)
        autoRunning = false
        controller.reset()
        btnRun.isEnabled = true
        btnRun.text = "RUN"
        updateStats()
    }

    private fun updateStats() {
        val world = controller.world
        if (world == null) {
            statA.text = statsBlock(null, null, null, null, null)
            statB.text = statsBlock(null, null, null, null, null)
            status.text =
            "SYS READY  // SELECT BOTS AND RUN"
        } else {
            val a = world.bots[0]
            val b = world.bots[1]
            statA.text = statsBlock(a.hp, a.shots, a.hits, a.dmgDealt, a.heat)
            statB.text = statsBlock(b.hp, b.shots, b.hits, b.dmgDealt, b.heat)
            val state = if (world.finished) controller.status else "EXECUTING"
            status.text = "T+${world.tick.toString().padStart(3, '0')}  // $state"
        }
        updateMonitor()
        arena.invalidate()
    }

    /**
     * One combatant's live readout. The HEAT line shows the exact value plus
     * a 10-cell bar over the 0–100 scale (SHOOT & SHIELD add 20 each; it cools
     * 2 per idle tick):
     * cell i is bright `#` when heat >= (i+1)*10, else dim `-`.
     * Intensity contrast — not a new hue — so the strict green-phosphor look
     * holds; the pixel font has no block glyphs, so the bar is pure ASCII.
     * [heat] is null before a fight, rendering the whole bar empty/dim.
     */
    private fun statsBlock(hp: Int?, shots: Int?, hits: Int?, damage: Int?, heat: Int?): SpannableStringBuilder {
        val out = SpannableStringBuilder()
        if (hp == null) {
            out.append("HP ---   SH --\nHIT --   DMG ---\nHEAT --- ")
        } else {
            out.append("HP ${hp.toString().padStart(3)}   SH ${shots.toString().padStart(2)}\n")
            out.append("HIT ${hits.toString().padStart(2)}  DMG ${damage.toString().padStart(3)}\n")
            out.append("HEAT ${heat.toString().padStart(3, '0')} ")
        }
        val barStart = out.length
        for (i in 0 until 10) {
            val filled = heat != null && heat >= (i + 1) * 10
            out.append(if (filled) '#' else '-')
            val color = if (filled) C_BRIGHT else C_DIM
            out.setSpan(ForegroundColorSpan(color), barStart + i, barStart + i + 1, SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return out
    }

    private fun loadAsset(name: String): String? = try {
        assets.open(name).bufferedReader().use { it.readText() }
    } catch (_: Exception) { null }

    private fun buildLayout(): ViewGroup {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(C_BG)
        }
        applyWindowInsets(root)

        val masthead = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(8), dp(7))
            background = panelBackground(C_DARK, C_BORDER)
        }
        masthead.addView(ImageView(this).apply {
            setImageResource(R.mipmap.ic_launcher)
            contentDescription = getString(R.string.app_icon_description)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }, LinearLayout.LayoutParams(dp(36), dp(36)).apply {
            marginEnd = dp(8)
        })
        masthead.addView(terminalText(14f, C_BRIGHT).apply {
            text = "OPCODE ARENA"
            typeface = pixelTypeface
        })
        masthead.addView(BlinkCursor(this), LinearLayout.LayoutParams(dp(9), dp(20)).apply {
            marginStart = dp(2)
            marginEnd = dp(8)
        })
        masthead.addView(terminalText(10f, C_DIM).apply {
            text = "RF-8 / ONLINE"
            gravity = Gravity.END
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(masthead)

        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(7), dp(8), dp(7))
        }
        shellTab = terminalButton("[ SHELL ]", strong = true)
        runTab = terminalButton("[ RUN ]", strong = true)
        tabs.addView(shellTab, LinearLayout.LayoutParams(0, dp(46), 1f))
        tabs.addView(runTab, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginStart = dp(6) })
        root.addView(tabs)

        val screenHost = FrameLayout(this)
        shellScreen = buildShellScreen()
        runScreen = buildRunScreen()
        screenHost.addView(shellScreen, matchFrame())
        screenHost.addView(runScreen, matchFrame())
        root.addView(screenHost, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        status = terminalText(11f, C_BRIGHT).apply {
            setPadding(dp(10), dp(7), dp(10), dp(8))
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
            background = panelBackground(C_DARK, C_BORDER)
        }
        root.addView(status)
        return root
    }

    /** Keep controls out of system UI and resize the content above the keyboard. */
    private fun applyWindowInsets(root: View) {
        root.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val safeArea = insets.getInsets(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
                )
                val keyboard = insets.getInsets(WindowInsets.Type.ime())
                view.setPadding(
                    safeArea.left,
                    safeArea.top,
                    safeArea.right,
                    maxOf(safeArea.bottom, keyboard.bottom)
                )
            } else {
                // Before API 30 the IME has no separate inset type. With the
                // activity's adjustResize mode, systemWindowInsetBottom tracks
                // the keyboard while it is visible.
                @Suppress("DEPRECATION")
                view.setPadding(
                    insets.systemWindowInsetLeft,
                    insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight,
                    insets.systemWindowInsetBottom
                )
            }
            insets
        }
        root.requestApplyInsets()
    }

    private fun buildShellScreen(): LinearLayout {
        val shell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), 0, dp(8), dp(8))
        }
        shellContext = terminalText(11f, C_DIM).apply { setPadding(dp(4), 0, dp(4), dp(6)) }
        shell.addView(shellContext)

        val host = FrameLayout(this)
        terminalPanel = buildTerminalPanel()
        editorPanel = buildEditorPanel()
        host.addView(terminalPanel, matchFrame())
        host.addView(editorPanel, matchFrame())
        shell.addView(host, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return shell
    }

    private fun buildTerminalPanel(): LinearLayout {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(7), dp(8), dp(7))
            background = panelBackground(C_PANEL, C_GREEN_DARK)
        }
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnDir = terminalButton("DIR")
        btnHelp = terminalButton("HELP")
        btnEditCurrent = terminalButton("EDIT")
        actions.addView(btnDir, weightedButton())
        actions.addView(btnHelp, weightedButton(dp(5)))
        actions.addView(btnEditCurrent, weightedButton(dp(5)))
        panel.addView(actions)

        out = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(2), dp(8), dp(2), dp(6))
        }
        scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(out, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        panel.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val commandRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = panelBackground(C_BLACK, C_BORDER)
            setPadding(dp(7), dp(2), dp(3), dp(2))
        }
        commandRow.addView(terminalText(14f, C_BRIGHT).apply { text = "RF:\\>" })
        cmd = EditText(this).apply {
            hint = "TYPE COMMAND"
            setHintTextColor(C_DIM)
            setTextColor(C_BRIGHT)
            textSize = 11f
            typeface = pixelTypeface
            usePixelTextRendering()
            background = null
            setPadding(dp(7), 0, dp(4), 0)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_GO
            setSingleLine(true)
        }
        commandRow.addView(cmd, LinearLayout.LayoutParams(0, dp(48), 1f))
        btnGo = terminalButton("ENTER", strong = true)
        commandRow.addView(btnGo, LinearLayout.LayoutParams(dp(74), dp(40)))
        panel.addView(commandRow)
        return panel
    }

    private fun buildEditorPanel(): LinearLayout {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(7), dp(7), dp(7), dp(7))
            background = panelBackground(C_PANEL, C_GREEN_DARK)
        }
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnSave = terminalButton("SAVE", strong = true)
        btnNew = terminalButton("NEW")
        btnTerminal = terminalButton("TERM")
        btnFight = terminalButton("FIGHT >", strong = true)
        actions.addView(btnSave, weightedButton())
        actions.addView(btnNew, weightedButton(dp(4)))
        actions.addView(btnTerminal, weightedButton(dp(4)))
        actions.addView(btnFight, weightedButton(dp(4)))
        panel.addView(actions)

        val editorFrame = FrameLayout(this).apply {
            background = panelBackground(C_BLACK, C_BORDER)
            setPadding(dp(5), dp(5), dp(5), dp(5))
        }
        editor = EditText(this).apply {
            background = null
            hint = "; ENTER RF-8 FIRMWARE HERE"
            setHintTextColor(C_DIM)
            setTextColor(C_BRIGHT)
            textSize = 10f
            gravity = Gravity.TOP or Gravity.START
            typeface = pixelTypeface
            usePixelTextRendering()
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        editorFrame.addView(editor, matchFrame())
        editorFrame.addView(Scanlines(this), matchFrame())
        panel.addView(editorFrame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
            topMargin = dp(7)
        })
        return panel
    }

    private fun buildRunScreen(): LinearLayout {
        val run = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), 0, dp(8), dp(8))
        }
        run.addView(terminalText(11f, C_DIM).apply {
            text = "ARENA / LIVE EXECUTION"
            setPadding(dp(4), 0, dp(4), dp(6))
        })

        val arenaFrame = FrameLayout(this).apply {
            background = panelBackground(C_PANEL, C_GREEN_DARK)
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        arena = ArenaView(this)
        arenaFrame.addView(arena, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER
        ))
        btnA = terminalButton("A  HUNTER  >")
        btnB = terminalButton("B  TURTLE  >")
        btnRun = terminalButton("RUN", strong = true)
        btnStep = terminalButton("STEP")
        btnReset = terminalButton("RESET")
        btnSound = terminalButton("SND ON", strong = true)
        statA = runStatView()
        statB = runStatView()

        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            val body = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            body.addView(arenaFrame, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.45f))

            val console = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(8), 0, 0, 0)
            }
            fun consoleItem(top: Int = 0) =
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                    topMargin = dp(top)
                }
            console.addView(terminalText(11f, C_DIM).apply { text = "COMBATANTS" })
            console.addView(btnA, consoleItem(5))
            console.addView(statA, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(58)
            ))
            console.addView(btnB, consoleItem(5))
            console.addView(statB, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(58)
            ))
            console.addView(buildCodeMonitorRow(), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(118)
            ).apply { topMargin = dp(5) })
            console.addView(terminalText(11f, C_DIM).apply {
                text = "EXECUTION"
                gravity = Gravity.BOTTOM
                setPadding(0, dp(4), 0, dp(3))
            })
            console.addView(btnRun, consoleItem())
            console.addView(btnStep, consoleItem(5))
            console.addView(btnReset, consoleItem(5))
            console.addView(btnSound, consoleItem(5))
            body.addView(console, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            run.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        } else {
            // Arena is top-aligned: it takes the top weight, the code monitor
            // sits directly beneath it, then the slots/stats/controls stack.
            run.addView(arenaFrame, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.15f
            ).apply { gravity = Gravity.TOP })
            run.addView(buildCodeMonitorRow(), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(128)
            ).apply { topMargin = dp(6) })
            val slotsRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(7), 0, 0)
            }
            slotsRow.addView(btnA, weightedButton())
            slotsRow.addView(btnB, weightedButton(dp(6)))
            run.addView(slotsRow)

            val statsRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(3), 0, 0)
            }
            statsRow.addView(statA, LinearLayout.LayoutParams(0, dp(64), 1f))
            statsRow.addView(statB, LinearLayout.LayoutParams(0, dp(64), 1f).apply {
                marginStart = dp(6)
            })
            run.addView(statsRow)

            val controls = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(6), 0, 0)
            }
            controls.addView(btnRun, weightedButton())
            controls.addView(btnStep, weightedButton(dp(6)))
            controls.addView(btnReset, weightedButton(dp(6)))
            controls.addView(btnSound, weightedButton(dp(6)))
            run.addView(controls)
        }
        return run
    }

    private fun runStatView() = terminalText(10f, C_GREEN).apply {
        gravity = Gravity.CENTER
        setPadding(dp(3), 0, dp(3), 0)
        background = panelBackground(C_DARK, C_GREEN_DARK)
        maxLines = 3
    }

    /**
     * The execution monitor: two code panes side by side, one per combatant.
     * Each pane shows the original firmware source in a scrolling window
     * (2 lines back, the current line marked with ">", 3 lines ahead) plus a
     * one-line register readout. Built in both the portrait and landscape
     * branches of buildRunScreen(); content is refreshed by updateMonitor().
     */
    private fun buildCodeMonitorRow(): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 0)
        }
        row.addView(codePanes(0), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        row.addView(codePanes(1), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
            marginStart = dp(5)
        })
        return row
    }

    private fun codePanes(side: Int): LinearLayout {
        val pane = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = panelBackground(C_PANEL, C_GREEN_DARK)
            setPadding(dp(4), dp(3), dp(4), dp(3))
        }
        val head = terminalText(8f, C_DIM).apply { setPadding(dp(1), 0, dp(1), dp(2)) }
        // The code window takes all remaining height, top-aligned; the
        // register line is its own view pinned to the pane's bottom edge.
        // The window sits in a HorizontalScrollView and wraps its content:
        // every line starts at the left edge, and long lines (wide comments)
        // clip at the pane border instead of wrapping onto the next line.
        val code = terminalText(7f, C_GREEN).apply {
            setPadding(dp(1), 0, dp(1), 0)
            gravity = Gravity.TOP or Gravity.START
            maxLines = MON_CODE_LINES
        }
        val codeScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            // fillViewport=false: the TextView wraps its content, so lines are
            // never reflowed; long lines clip at the pane border.
            isFillViewport = false
            addView(code, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        val reg = terminalText(7f, C_DIM).apply {
            setPadding(dp(1), dp(2), dp(1), 0)
            gravity = Gravity.BOTTOM or Gravity.START
            maxLines = 1
        }
        if (side == 0) { monHeadA = head; codeA = code; regA = reg }
        else { monHeadB = head; codeB = code; regB = reg }
        pane.addView(head)
        pane.addView(codeScroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        pane.addView(reg)
        return pane
    }

    /**
     * Render both code panes for the current frame. The current line is found
     * from the VM's currentInsn (held steady across a multi-tick op's whole
     * duration), mapped to a source line via the assembler's line map. The
     * window is biased so the current line sits in the lower half — 2 lines
     * ahead are always visible.
     */
    private fun updateMonitor() {
        if (!::codeA.isInitialized) return
        renderCodePane(0, monHeadA, codeA, regA)
        renderCodePane(1, monHeadB, codeB, regB)
    }

    private fun renderCodePane(side: Int, head: TextView, code: TextView, reg: TextView) {
        val world = controller.world
        val bc = controller.botCodes[side]
        if (world == null || bc == null) {
            head.text = if (side == 0) "A" else "B"
            code.text = "  (idle)"
            reg.text = ""
            return
        }
        val bot = world.bots[side]
        val vm = bot.vm
        head.text = "${if (side == 0) "A" else "B"} ▸ ${bot.name}"
        head.setTextColor(if (bot.alive) C_BRIGHT else C_DIM)
        if (!bot.alive) {
            code.text = "  KO — destroyed"
            reg.text = ""
            return
        }
        val insn = vm.currentInsn
        // Map the current instruction to its source line via the assembler's
        // line map. A bot that runs off the end of its program (mem tail is
        // 0x00 NOPs) clamps to the last line and folds an END marker onto it.
        val atEnd = insn !in 0 until bc.lineOf.size
        val lines = bc.source.split("\n")
        val curLine = if (atEnd) lines.size - 1 else bc.lineOf[insn]
        // Window: MON_BEFORE lines back, current, MON_AFTER ahead (total
        // MON_CODE_LINES). from is solved so the current line sits in the
        // lower third — MON_AFTER lines stay visible ahead of it.
        val from = (curLine - MON_BEFORE).coerceAtMost((lines.size - MON_CODE_LINES).coerceAtLeast(0))
        val to = (from + MON_CODE_LINES - 1).coerceAtMost(lines.size - 1)
        val out = SpannableStringBuilder()
        for (ln in from..to) {
            val isCur = ln == curLine
            val marker = if (isCur) ">" else " "
            val text = "  $marker ${lines[ln]}${if (atEnd && isCur) " …END" else ""}"
            val start = out.length
            out.append(text)
            out.append("\n")
            val color = if (isCur) C_BRIGHT else if (ln < curLine) C_DIM else C_GREEN
            out.setSpan(ForegroundColorSpan(color), start, out.length, SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        code.text = out
        reg.text = "  " + regLine(vm)
    }

    /** One-line register readout: A X Y (8-bit), SP PC (10-bit), and flags. */
    private fun regLine(vm: Vm): String {
        val flags = when {
            (vm.flags and Vm.FLAG_C) != 0 -> "C "
            else -> "  "
        } + when {
            (vm.flags and Vm.FLAG_Z) != 0 -> "Z "
            else -> "  "
        } + when {
            (vm.flags and Vm.FLAG_N) != 0 -> "N "
            else -> "  "
        }
        return "A%02X X%02X Y%02X SP%03X PC%03X %s".format(
            vm.a, vm.x, vm.y, vm.sp, vm.pc, flags.trim()
        )
    }

    private fun terminalText(size: Float, color: Int) = TextView(this).apply {
        textSize = size * 0.82f
        setTextColor(color)
        typeface = pixelTypeface
        usePixelTextRendering()
        setLineSpacing(dp(3).toFloat(), 1f)
        includeFontPadding = false
    }

    private fun terminalButton(label: String, strong: Boolean = false) = Button(this).apply {
        text = label
        textSize = 9f
        setTextColor(if (strong) C_BLACK else C_BRIGHT)
        typeface = pixelTypeface
        usePixelTextRendering()
        isAllCaps = false
        minHeight = 0
        minimumHeight = 0
        setPadding(dp(5), 0, dp(5), 0)
        background = panelBackground(if (strong) C_GREEN else C_BLACK, C_GREEN_DARK)
    }

    private fun styleModeTab(button: Button, selected: Boolean) {
        button.setTextColor(if (selected) C_BLACK else C_GREEN)
        button.background = panelBackground(if (selected) C_GREEN else C_BLACK, C_GREEN)
    }

    private fun weightedButton(startMargin: Int = 0) = LinearLayout.LayoutParams(0, dp(42), 1f).apply {
        marginStart = startMargin
    }

    private fun matchFrame() = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
    )

    private fun panelBackground(fill: Int, stroke: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fill)
        setStroke(dp(1), stroke)
        cornerRadius = 0f
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    /** Preserve the font's deliberate square pixels instead of smoothing them. */
    private fun TextView.usePixelTextRendering() {
        paintFlags = paintFlags and Paint.ANTI_ALIAS_FLAG.inv() and Paint.SUBPIXEL_TEXT_FLAG.inv()
        paint.isDither = false
    }

    companion object {
        private const val STATE_FILE = "state.file"
        private const val STATE_SOURCE = "state.source"
        private const val STATE_DIRTY = "state.dirty"
        private const val STATE_RUN_MODE = "state.runMode"
        private const val REQ_EXPORT_TREE = 41
        // Execution-monitor window: how many source lines to show around the
        // current one. 2 back, the current line, 3 ahead — the current opcode
        // marker sits in the lower third with clear lookahead.
        private const val MON_BEFORE = 2
        private const val MON_AFTER = 3
        private const val MON_CODE_LINES = MON_BEFORE + 1 + MON_AFTER
        private val C_BG = Color.rgb(1, 7, 3)
        private val C_BLACK = Color.rgb(0, 3, 1)
        private val C_PANEL = Color.rgb(2, 13, 6)
        private val C_DARK = Color.rgb(2, 18, 8)
        private val C_GREEN_DARK = Color.rgb(20, 92, 42)
        private val C_BORDER = Color.rgb(36, 132, 61)
        private val C_DIM = Color.rgb(48, 130, 67)
        private val C_GREEN = Color.rgb(52, 236, 91)
        private val C_BRIGHT = Color.rgb(180, 255, 190)
    }
}

/** Faint CRT scanlines; non-interactive so the editor underneath keeps focus. */
private class Scanlines(context: Context) : View(context) {
    private val paint = Paint().apply { color = Color.argb(24, 0, 0, 0) }
    init {
        isFocusable = false
        isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    override fun onDraw(canvas: Canvas) {
        var y = 0f
        while (y < height) {
            canvas.drawLine(0f, y, width.toFloat(), y, paint)
            y += 4f
        }
    }
}

/** Hardware-style block cursor used in the masthead even before an input has focus. */
private class BlinkCursor(context: Context) : View(context) {
    private val paint = Paint().apply { color = Color.rgb(52, 236, 91) }
    private var lit = true
    private val blink = object : Runnable {
        override fun run() {
            lit = !lit
            invalidate()
            postDelayed(this, 520L)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        postDelayed(blink, 520L)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(blink)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        if (lit) canvas.drawRect(0f, height * 0.18f, width.toFloat(), height.toFloat(), paint)
    }
}
