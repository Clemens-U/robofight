package robofight.android

import android.app.Activity
import android.app.AlertDialog
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
import robofight.world.Arena
import robofight.world.Layout
import robofight.world.Palette
import robofight.world.Presets
import robofight.vm.Vm
import robofight.world.Bot
import robofight.world.World
import robofight.world.GRID
import robofight.world.SPAWN_SCATTER
import robofight.world.scatterSpawn
import robofight.world.DIR_E
import robofight.assembler.assemble
import robofight.challenge.ChallengeRun
import robofight.challenge.ChallengeFormat
import robofight.challenge.ChallengeResponse
import robofight.challenge.ChallengeCodeFileName
import robofight.disasm.Disasm
import robofight.world.SPAWN_SEPARATE
import robofight.world.SPAWN_CLASH

/**
 * Two-screen native UI for Opcode Arena.
 *
 * SHELL is the firmware workspace (file terminal + editor). RUN is the arena.
 * Keeping them separate gives text input room to resize above the soft keyboard
 * and lets the arena use the full display while a fight is running.
 */
class MainActivity : Activity() {

    private enum class AppMode { SHELL, RUN, CODES, BUILD }

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

    // --- [ CODES ] screen (R2): import a challenge code, preview its arena +
    // rules (opponent firmware hard-hidden), pick a bot for each open slot,
    // then RUN (deterministic eng=1) or SAVE the canonical code to the store.
    private lateinit var codesScreen: LinearLayout
    private lateinit var codesTab: Button
    private lateinit var codePaste: EditText
    private lateinit var codePasteBtnPaste: Button
    private lateinit var codePasteBtnImport: Button
    private lateinit var codePasteBtnClear: Button
    private lateinit var previewArena: ArenaView
    private lateinit var codeMode: TextView
    private lateinit var codeRules: TextView
    private lateinit var codeOpponent: TextView
    private lateinit var codeResult: TextView
    private lateinit var codeWarning: TextView
    private lateinit var codePreviewCard: LinearLayout
    private lateinit var codeErrorCard: LinearLayout
    private lateinit var codeErrorText: TextView
    private lateinit var codePickRow: LinearLayout
    private lateinit var codePickLabel: TextView
    private lateinit var codeBtnRun: Button
    private lateinit var codeBtnSave: Button
    private val codePickers = ArrayList<Button>()      // one per OPEN slot, in slot order
    private val codePickSlotOf = ArrayList<Int>()      // slot index (0=A,1=B) per picker
    private val codePickIdx = intArrayOf(0, 0)         // selected BotSlot index per slot
    private var importedDesc: ChallengeFormat.FightDescriptor? = null
    private var importedCode: String? = null
    /** The challenge code loaded in the RUN screen, so "RUN AGAIN" re-runs the
     *  SAME deterministic fight (identical seed/spawns) rather than a random
     *  one. Cleared by any manual fight start or RESET. */
    private var currentChallenge: ChallengeFormat.FightDescriptor? = null

    // --- R3: response flow + share + local history --------------------------------
    private lateinit var codeBtnShare: Button
    private lateinit var codeBtnCopy: Button
    private lateinit var codeBtnRespond: Button
    private lateinit var codeHistory: TextView
    /** The last FINISHED challenge fight (descriptor + slot-oriented outcome),
     *  captured by [captureCodeOutcome] when the fight ends. A player win in
     *  this pair unlocks SEND BACK (spec §8); a draw/loss never does. */
    private var lastCodeOutcome: Pair<ChallengeFormat.FightDescriptor, ChallengeRun.Outcome>? = null
    /** The canonical code string of the challenge currently on the RUN screen —
     *  the history-table key for the fight that just finished. */
    private var lastCodeText: String? = null

    // --- R3: [ BUILD ] screen — named modes + dials + opponent picker → RF1- code --
    private lateinit var buildScreen: LinearLayout
    private lateinit var buildBtnBack: Button
    private lateinit var buildBtnBuild: Button
    private lateinit var buildBtnShare: Button
    private lateinit var buildBtnCopy: Button
    private lateinit var buildDialMode: Button
    private lateinit var buildDialSpawn: Button
    private lateinit var buildDialHpA: Button
    private lateinit var buildDialHpB: Button
    private lateinit var buildDialArena: Button
    private lateinit var buildSeed: EditText
    private lateinit var buildBtnRngSeed: Button
    private lateinit var buildBtnOpponent: Button
    private lateinit var buildOppNote: TextView
    private lateinit var buildOutput: TextView
    private lateinit var codeBtnBuild: Button
    private val buildModeBtns = ArrayList<Button>()   // DUEL/MELEE/SIEGE/GAMBIT/CUSTOM
    private var buildModeIdx = 0        // 0=DUEL 1=MELEE 2=SIEGE 3=GAMBIT 4=CUSTOM (§6.1)
    private var buildModeDial = 0       // ChallengeFormat.MODE_*
    private var buildSpawnDial = 0      // SPAWN_SEPARATE / CLASH / SCATTER
    private var buildHpADial = 1        // index into HP_OPTS (default 100)
    private var buildHpBDial = 1
    private var buildArenaIdx = 0
    private var buildOppIdx = 0         // index into [slots] (5 presets + user files)
    private var lastBuiltCode: String? = null

    // --- P3: locked Pro-arena preview extras (arena name, opponent listing, circuit link) --
    private lateinit var codeFwScroll: HorizontalScrollView
    private lateinit var codeArenaRowParams: LinearLayout.LayoutParams
    private lateinit var codeFwListing: TextView
    private var codeFwExpanded = false
    private val codeFwFull = ArrayList<String>()
    private val codeFwCompact = ArrayList<String>()
    private lateinit var codeBtnCircuit: Button
    private lateinit var codeCircuitNote: TextView

    // --- P3: pro-only ZIP import + one-time migration hint ---------------------------------
    private var migrationCard: LinearLayout? = null
    private var codesImportedRow: LinearLayout? = null
    private var importedReadOnly = false

    /** Flavor resource gate: true only in The Circuit. */
    private val proMigrationEnabled: Boolean by lazy {
        resources.getBoolean(R.bool.rf_pro_migration)
    }

    private fun uiPrefs() = getSharedPreferences(PREF_UI, MODE_PRIVATE)

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
    private var currentCode: String? = null   // a saved .chc code being viewed read-only (R2)
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
    /**
     * The RUN-screen arena picker: one button per free arena in
     * [Arena.all()] order (OPEN/BLOCKS/RING/CROSS/MAZE). [arenaIdx] is the
     * selected arena (default 0 = OPEN); the picked layout feeds
     * [RunController.start] and is rendered by [ArenaView.arenaLayout].
     * P2 rev. B: pack-tagged arenas (pro app) do NOT add picker rows — they
     * are reached through the compact cycle chip in the section header
     * (see [buildPackSelector]), so the picker's vertical cost stays exactly
     * one row in both flavors.
     */
    private val arenaButtons = ArrayList<Button>()
    private var arenaIdx = 0
    private val arenaLayout: Layout get() = Arena.all()[arenaIdx]

    /** Pack-arena cycle chip (pro only; empty in the free app). */
    private val packArenas = ArrayList<Layout>()
    private var packChip: Button? = null

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
                btnRun.isEnabled = true
                val playerWon = captureCodeOutcome(world)
                showRunResponseAction(playerWon)
                status.text = if (playerWon) "CHALLENGE WON." else controller.status
            }
        }
    }

    /** Replace the compact execution controls with the earned response CTA. */
    private fun showRunResponseAction(show: Boolean) {
        btnRun.text = if (show) "SEND BACK THE PROOF" else "RUN AGAIN"
        btnStep.visibility = if (show) View.GONE else View.VISIBLE
        btnReset.visibility = if (show) View.GONE else View.VISIBLE
        btnSound.visibility = if (show) View.GONE else View.VISIBLE
    }

    private fun resetRunResponseAction() {
        if (!::btnRun.isInitialized) return
        btnStep.visibility = View.VISIBLE
        btnReset.visibility = View.VISIBLE
        btnSound.visibility = View.VISIBLE
    }

    private fun canSendBackCurrentChallenge(): Boolean {
        if (controller.world?.finished != true) return false
        val ch = currentChallenge ?: return false
        val pair = lastCodeOutcome ?: return false
        return pair.first === ch && ChallengeResponse.isPlayerWin(pair.first, pair.second)
    }

    /**
     * R3: the fight on the RUN screen just finished — if it was a challenge
     * ([currentChallenge]), record its outcome in the CODE's slot orientation
     * (exactly [ChallengeRun.run]'s computation, so the app's result equals
     * the headless one) and write the local history row. A player win in this
     * pair is what unlocks SEND BACK (spec §8).
     */
    private fun captureCodeOutcome(world: World): Boolean {
        val ch = currentChallenge ?: return false
        val a = world.bots[0]
        val b = world.bots[1]
        // Resolve by identity against the canonical A/B list. This avoids
        // allowing any future Bot equality implementation to blur slot
        // orientation, which is security-critical for response ownership.
        val winnerSlot = when {
            world.winner === a -> 0
            world.winner === b -> 1
            else -> ChallengeFormat.WIN_DRAW
        }
        val loserEndHp = when (winnerSlot) {
            0 -> b.hp
            1 -> a.hp
            else -> a.hp // DRAW: slot A's HP
        }
        val outcome = ChallengeRun.Outcome(winnerSlot, world.tick, loserEndHp)
        lastCodeOutcome = ch to outcome
        val layoutName = Arena.byId(ch.layoutId)?.name ?: "OPEN"
        val playerWon = ChallengeResponse.isPlayerWin(ch, outcome)
        store.recordOutcome(
            lastCodeText ?: ch.let { ChallengeFormat.encode(it) },
            layoutName,
            playerWon,
            outcome.winnerSlot == ChallengeFormat.WIN_DRAW,
        )
        return playerWon
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Two-app gate (A1): the flavor source set decides what this call
        // compiles to — src/free provides a no-op, src/pro registers the Pro
        // arena packs. No BuildConfig branch needed in shared code.
        ProArenas.registerAll()
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
        codesTab.setOnClickListener { showCodesMode() }
        btnA.setOnClickListener { if (currentChallenge == null) { ai = (ai + 1) % slots.size; updateBtnTexts() } }
        btnB.setOnClickListener { if (currentChallenge == null) { bi = (bi + 1) % slots.size; updateBtnTexts() } }
        btnRun.setOnClickListener {
            if (canSendBackCurrentChallenge()) {
                doCodeRespond()
            } else {
                val ch = currentChallenge
                if (ch != null) doCodeRun(ch) else doRun()
            }
        }
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
        updateArenaBtns()
        if (savedInstanceState?.getBoolean(STATE_RUN_MODE) == true) {
            setMode(AppMode.RUN)
        } else {
            showShellTerminal()
        }
        printBoot()
        runCmd("DIR")
        updateStats()
        handleSendIntent()
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
                if (arg.isEmpty()) {
                    printLine("USAGE: EDIT <NAME>")
                } else if (store.codeExists(arg)) {
                    openCode(arg)
                } else {
                    openFile(arg)
                }
            }
            "DEL", "DELETE", "RM" -> {
                if (arg.isEmpty()) {
                    printLine("USAGE: DEL <NAME>")
                } else if (store.codeExists(arg)) {
                    // Saved challenge codes live in the `codes` table, never the bot cycle.
                    if (currentCode.equals(arg, ignoreCase = true)) {
                        currentCode = null
                        editor.text.clear()
                        makeEditorEditable()
                    }
                    printLine(if (store.deleteCode(arg)) "DELETED $arg" else "DELETE FAILED: $arg")
                    updateShellContext()
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
            "IMPORT", "IMPORTZIP" ->
                if (proMigrationEnabled) startImportZip()
                else printLine("IMPORT NOT AVAILABLE IN THIS APP")
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
        makeEditorEditable()
        currentCode = null
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

    /**
     * EDIT <name.chc>: open a saved challenge code READ-ONLY. Re-opening
     * re-runs full validation (spec §11); a corrupt stored code reports its
     * exact §10 string. The editor is locked so the code can be read and
     * copied but never edited or re-saved as firmware.
     */
    private fun openCode(name: String) {
        val raw = store.readCode(name)
        if (raw == null) { printLine("NO SUCH CODE: $name"); return }
        when (val r = ChallengeFormat.parse(raw)) {
            is ChallengeFormat.ParseResult.Err -> {
                printLine("CODE INVALID: ${r.message}")
                printLine("  // STORED CODE FAILED RE-VALIDATION  // DEL $name TO REMOVE")
                return
            }
            is ChallengeFormat.ParseResult.Ok -> { /* valid — view it */ }
        }
        currentFile = null
        currentCode = name
        editor.setText(raw)
        editor.setSelection(0)
        editorDirty = false        // setText fired the watcher; viewing a code is not a dirty edit
        makeEditorReadonly()
        status.text = "VIEWING $name  (READ ONLY)"
        updateShellContext()
        showEditor()
        editor.post {
            editor.setSelection(0)
            editor.scrollTo(0, 0)
        }
    }

    /** Lock the editor so the code currently shown can't be edited or saved. */
    private fun makeEditorReadonly() {
        editor.keyListener = null
        editor.isFocusableInTouchMode = false
    }

    /** Restore normal multi-line text editing (re-creates the key listener). */
    private fun makeEditorEditable() {
        editor.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        editor.isFocusableInTouchMode = true
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
        makeEditorEditable()
        currentCode = null
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
        if (currentCode != null) {
            status.text = "CODES ARE READ-ONLY  // EDIT <NAME> FOR FIRMWARE"
            printLine("CODES ARE READ-ONLY  // CHALLENGE CODES CAN'T BE EDITED OR RE-SAVED")
            commitText()
            return
        }
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
        } else if (store.count() == 0 && store.countCodes() == 0) {
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
        if (requestCode == REQ_IMPORT_ZIP) {
            handleImportResult(resultCode, data)
            return
        }
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
                // P3 migration: the full-export ZIP carries the firmware AND
                // every saved challenge code (.chc entries), so the whole
                // library travels to the other app.
                val files = LinkedHashMap(store.all())
                var codeCount = 0
                for (code in store.listCodes()) {
                    val raw = store.readCode(code.name) ?: continue
                    files[code.name] = raw
                    codeCount++
                }
                val bytes = FirmwareExport.zipBytes(files)
                val file = FirmwareExport.zipName()
                if (!writeExportDoc(tree, file, FirmwareExport.MIME_ZIP, bytes)) {
                    printLine("EXPORT FAILED  // CANNOT WRITE $file TO THE PICKED FOLDER")
                    status.text = "EXPORT FAILED"
                    commitText()
                    return
                }
                printLine("EXPORTED ${files.size - codeCount} FIRMWARE + $codeCount CODE(S) AS $file")
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
        val doc = DocumentsContract.createDocument(contentResolver, rootDoc, mimeType, fileName) ?: return false
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
                val files = LinkedHashMap(store.all())
                var codeCount = 0
                for (code in store.listCodes()) {
                    val raw = store.readCode(code.name) ?: continue
                    files[code.name] = raw
                    codeCount++
                }
                val bytes = FirmwareExport.zipBytes(files)
                val target = File(root, FirmwareExport.zipName())
                target.writeBytes(bytes)
                printLine("EXPORTED ${files.size - codeCount} FIRMWARE + $codeCount CODE(S) AS ${target.name}")
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

    // --- P3: ZIP import (pro flavor only; free no-ops on proMigrationEnabled) -------------

    /**
     * Pick a `robofight-export-*.zip` the OTHER app wrote, via the same
     * Documents UI the export picker uses — SAF ACTION_OPEN_DOCUMENT here
     * (a single readable file, not a tree). Only armed when the flavor
     * resource gate says so.
     */
    private fun startImportZip() {
        showShellTerminal(clearFocus = false)
        try {
            startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    // Providers do not agree on a ZIP MIME type: valid archives
                    // are also commonly exposed as application/x-zip-compressed
                    // or application/octet-stream. Let the picker show application
                    // files and rely on ImportZip's strict content validation.
                    type = "application/*"
                },
                REQ_IMPORT_ZIP,
            )
            printLine("PICK THE robofight-export-….zip THE FREE APP WROTE ...")
        } catch (e: ActivityNotFoundException) {
            printLine("IMPORT FAILED  // NO DOCUMENTS UI ON THIS DEVICE")
            commitText()
        }
    }

    /**
     * Read the picked ZIP through [ImportZip] (validation + caps live
     * there) and land it in the stores: firmware via [BotFiles.upsert]
     * (collision-safe names — never overwrites a user file), codes via
     * [BotFiles.upsertCode] flagged imported → read-only library content
     * (CODES tab loads them; SAVE/SHARE refuse them).
     */
    private fun handleImportResult(resultCode: Int, data: Intent?) {
        showShellTerminal(clearFocus = false)
        if (resultCode != Activity.RESULT_OK) {
            printLine("IMPORT CANCELLED")
            status.text = "IMPORT CANCELLED"
            commitText()
            return
        }
        val bytes = data?.data?.let { uri ->
            try {
                contentResolver.openInputStream(uri)?.use { it.readBytes() }
            } catch (e: Exception) {
                null
            }
        }
        if (bytes == null || bytes.isEmpty()) {
            printLine("IMPORT FAILED  // CANNOT READ THE PICKED FILE")
            status.text = "IMPORT FAILED"
            commitText()
            return
        }
        val report = ImportZip.read(bytes)
        var firmware = 0
        var codes = 0
        for (e in report.entries) {
            if (e.isCode) {
                val name = uniqueCodeName(e.name)
                if (store.upsertCode(name, e.source, imported = true)) {
                    codes++
                    printLine("  IMPORTED CODE $name  (READ-ONLY)")
                }
            } else {
                val name = uniqueFileName(e.name)
                store.upsert(name, e.source)
                firmware++
                printLine("  IMPORTED FIRMWARE $name")
            }
        }
        for (r in report.rejected) printLine("  SKIPPED  $r")
        refreshSlots()
        updateShellContext()
        status.text = "IMPORTED $firmware FIRMWARE + $codes CODES"
        printLine(
            if (firmware + codes > 0)
                "IMPORT DONE  // $firmware FIRMWARE  // $codes CODES  //  CODES ARE READ-ONLY"
            else "IMPORT FOUND NOTHING USABLE  // NEEDS FIRMWARE FILES OR .chc CODES"
        )
        commitText()
    }

    /** Collision-safe firmware name: MYBOT → MYBOT_2, MYBOT_3, … (never clobbers). */
    private fun uniqueFileName(name: String): String {
        if (!store.exists(name)) return name
        var n = 2
        while (store.exists("${name}_$n")) n++
        return "${name}_$n"
    }

    /** Collision-safe .chc name (same _2/_3 rule the v4 code rename used). */
    private fun uniqueCodeName(name: String): String {
        if (!store.codeExists(name)) return name
        val base = name.removeSuffix(ChallengeCodeFileName.EXT)
        var n = 2
        while (store.codeExists("${base}_$n${ChallengeCodeFileName.EXT}")) n++
        return "${base}_$n${ChallengeCodeFileName.EXT}"
    }

    /**
     * P3 first-run migration banner for the SHELL tab (pro only): explains
     * the free app's EXPORT zip once per install, then stays dismissed
     * (SharedPreferences flag, set on tap). Tapping arms the import picker.
     */
    private fun buildMigrationCard(): LinearLayout? {
        if (!proMigrationEnabled) return null
        if (uiPrefs().getBoolean(PREF_MIGRATION_SEEN, false)) return null
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = panelBackground(C_DARK, C_BORDER)
            setPadding(dp(8), dp(7), dp(8), dp(7))
        }
        card.addView(terminalText(9f, C_BRIGHT).apply { text = "MIGRATE FROM THE FREE APP" })
        card.addView(terminalText(8f, C_DIM).apply {
            text = "RUN EXPORT IN THE FREE APP  //  IT ZIPS YOUR FIRMWARE AND SAVED CODES AS robofight-export-<DATE>.ZIP  //  PICK THAT ZIP HERE"
        })
        val migrate = terminalButton("MIGRATE FROM FREE >")
        migrate.setOnClickListener {
            uiPrefs().edit().putBoolean(PREF_MIGRATION_SEEN, true).apply()
            migrationCard?.visibility = View.GONE
            startImportZip()
        }
        card.addView(
            migrate,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)).apply { topMargin = dp(6) },
        )
        return card
    }

    /**
     * Open the Circuit's Play Store listing (pro flavor cross-sell): market:
     * first, plain https fallback when no store app answers.
     */
    private fun openProListing() {
        val pkg = "robofight.android.pro"
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")))
        } catch (e: ActivityNotFoundException) {
            try {
                startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$pkg")),
                )
            } catch (e2: ActivityNotFoundException) {
                status.text = "STORE PAGE UNAVAILABLE"
            }
        }
    }

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
        printLine(getString(R.string.rf_boot_line))
        // The shipped RF-8 profile has a 10-bit (1 KiB) address space.  The
        // planned 64 KiB profile must not be advertised until the VM, assembler,
        // and challenge format can actually address it.
        printLine("MEM 1K   GRID 20x20  LINK READY")
        printLine("TYPE HELP FOR AVAILABLE COMMANDS")
        printLine("")
        commitText()
    }

    private fun printDir() {
        commitText()
        val files = store.list()
        val codes = store.listCodes()
        if (files.isEmpty() && codes.isEmpty()) { printLine("  NO FILES  // NEW <NAME>"); return }
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
        if (codes.isNotEmpty()) {
            printLine("  SAVED CHALLENGE CODES  (.chc)")
            commitText()
            for (code in codes) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(3), 0, dp(3))
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { printLine("OPENING ${code.name} ..."); openCode(code.name) }
                }
                val name = terminalText(12f, C_CODE).apply {
                    text = "  > ${code.name}"
                    setSingleLine(true)
                    ellipsize = TextUtils.TruncateAt.END
                }
                val bytes = terminalText(12f, C_GREEN).apply { text = code.bytes.toString().padStart(6) }
                val modified = terminalText(11f, C_DIM).apply { text = "  ${store.fmtTime(code.modified)}" }
                row.addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(bytes)
                row.addView(modified)
                out.addView(row)
            }
        }
        printLine("  // TAP A FILE TO EDIT  // TAP A CODE TO VIEW (READ-ONLY)")
    }

    private fun printHelp() {
        printLine("DIR              LIST FIRMWARE + SAVED CODES (.chc)")
        printLine("EDIT <NAME>      OPEN IN EDITOR (HUNTER.asm, MYBOT, BOT1, …)")
        printLine("EDIT <NAME.chc>  VIEW A SAVED CHALLENGE CODE (READ-ONLY)")
        printLine("NEW <NAME>       CREATE FIRMWARE (STARTS WITH CHEAT SHEET)")
        printLine("DEL <NAME>       DELETE FIRMWARE")
        printLine("EXPORT           PICK A FOLDER, ZIP-EXPORT ALL FIRMWARE + CODES THERE")
        printLine("EXPORT <NAME>    PICK A FOLDER, SAVE ONE FIRMWARE FILE THERE")
        if (proMigrationEnabled)
            printLine("IMPORT           PICK A robofight-export-… ZIP FROM THE FREE APP")
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
        codesScreen.visibility = if (next == AppMode.CODES) View.VISIBLE else View.GONE
        buildScreen.visibility = if (next == AppMode.BUILD) View.VISIBLE else View.GONE
        styleModeTab(shellTab, next == AppMode.SHELL)
        styleModeTab(runTab, next == AppMode.RUN)
        styleModeTab(codesTab, next == AppMode.CODES)
    }

    /**
     * Receive a shared challenge code (ACTION_SEND, text/plain) — e.g. from
     * another device's share sheet or a messaging app. The code is loaded into
     * the [ CODES ] tab and imported immediately, so a tap on a shared code
     * drops the user straight onto its preview.
     */
    private fun handleSendIntent() {
        if (intent?.action != Intent.ACTION_SEND) return
        // The share carries the code in EXTRA_TEXT. The MIME type is unreliable —
        // an explicit-component launch and some share paths omit it — so only
        // reject clearly non-text types; a valid RF1 code is the real signal.
        val type = intent.type
        if (type != null && !"text/plain".equals(type, ignoreCase = true)) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        if (text.isNullOrBlank()) return
        showCodesMode()
        importedReadOnly = false        // a shared code is the player's own import
        codePaste.setText(text)
        importCode(text)
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
        val code = currentCode
        if (code != null) {
            shellContext.text = "VIEWING $code  (READ-ONLY)"
        } else {
            val file = currentFile ?: "NO FILE"
            shellContext.text = "WORKSPACE / $file${if (editorDirty) " *" else ""}"
        }
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
        syncBuilder()   // R3: slots exist now — refresh the builder's opponent row
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
        resetRunResponseAction()
        currentChallenge = null   // a manual fight supersedes any loaded challenge
        updateBtnTexts()          // restore the normal A/B picker labels
        if (!controller.start(slots[ai], slots[bi], ::sourceFor, arenaLayout)) {
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
            currentChallenge = null
            updateBtnTexts()      // restore the normal A/B picker labels
            if (!controller.start(slots[ai], slots[bi], ::sourceFor, arenaLayout)) {
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
        resetRunResponseAction()
        currentChallenge = null
        controller.reset()
        updateBtnTexts()          // restore the normal A/B picker labels
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
        masthead.addView(terminalText(14f, getColor(R.color.rf_title)).apply {
            text = "OPCODE ARENA"
            typeface = pixelTypeface
        })
        masthead.addView(BlinkCursor(this), LinearLayout.LayoutParams(dp(9), dp(20)).apply {
            marginStart = dp(2)
            marginEnd = dp(8)
        })
        masthead.addView(terminalText(10f, C_DIM).apply {
            text = getString(R.string.rf_masthead_link)
            gravity = Gravity.END
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(masthead)

        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(7), dp(8), dp(7))
        }
        shellTab = terminalButton("[ SHELL ]", strong = true)
        runTab = terminalButton("[ RUN ]", strong = true)
        codesTab = terminalButton("[ CODES ]", strong = true)
        tabs.addView(shellTab, LinearLayout.LayoutParams(0, dp(46), 1f))
        tabs.addView(runTab, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginStart = dp(6) })
        tabs.addView(codesTab, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginStart = dp(6) })
        root.addView(tabs)

        val screenHost = FrameLayout(this)
        shellScreen = buildShellScreen()
        runScreen = buildRunScreen()
        codesScreen = buildCodesScreen()
        buildScreen = buildBuildScreen()
        screenHost.addView(shellScreen, matchFrame())
        screenHost.addView(runScreen, matchFrame())
        screenHost.addView(codesScreen, matchFrame())
        screenHost.addView(buildScreen, matchFrame())
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
        // P3 (pro flavor only; free has no rf_pro_migration resource so this
        // no-ops): one-time migration hint ABOVE the terminal panel — SHELL
        // tab only, no vertical space taken from any arena grid (PLAN-PRO).
        buildMigrationCard()?.let {
            migrationCard = it
            shell.addView(
                it,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = dp(6) },
            )
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
        if (proMigrationEnabled) {
            // P3 (pro only): same picker the terminal command IMPORT opens.
            val btnImport = terminalButton("IMPORT")
            btnImport.setOnClickListener { startImportZip() }
            actions.addView(btnImport, weightedButton(dp(5)))
        }
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
        val arenaAll = Arena.all()
        packArenas.clear()
        packArenas.addAll(arenaAll.filter { it.pack != null })

        val sectionHead = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), 0, dp(4), dp(6))
        }
        sectionHead.addView(terminalText(11f, C_DIM).apply { text = "ARENA / LIVE EXECUTION" },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        sectionHead.addView(buildPackSelector())
        run.addView(sectionHead)
        run.addView(buildArenaPickerRow())

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
            console.addView(terminalText(11f, C_DIM).apply { text = getString(R.string.rf_combatants_label) })
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
     * The arena picker: one terminal-style button per UNTAGGED (core) arena in
     * [Arena.all()] order, sharing the row width the way the A/B bot picker
     * does. P2 rev. B: pack-tagged arenas (the pro app's ARENA PACK 1) are not
     * listed here — they live on the cycle chip in the section header
     * ([buildPackSelector]), so the picker occupies exactly one row in both
     * flavors and the arena grid keeps the free version's size. Family entries
     * (GEN-MAZE, GEN-ISLES) preview their seed-0 geometry until a challenge
     * with that arena is loaded (see [syncArenaToChallenge]). Tapping a button
     * selects that arena; the picked layout feeds [RunController.start] and
     * [ArenaView.arenaLayout].
     */
    private fun buildArenaPickerRow(): LinearLayout {
        val core = Arena.all().filter { it.pack == null }
        return pickerRow(core, 0)
    }

    /**
     * The pack cycle chip shown in the RUN header (pro only; invisible in the
     * free app where [packArenas] is empty). One tap cycles the next ARENA
     * PACK arena and the chip names it, so the 6 pack arenas cost no picker
     * rows at all. While a challenge is loaded its layout is fixed — a tap
     * then just re-asserts the challenge arena instead of cycling.
     */
    private fun buildPackSelector(): View {
        if (packArenas.isEmpty()) {
            // A bare View with default WRAP params measures itself at the
            // parent's FULL size when LinearLayout hands it an AT_MOST spec
            // (View.getDefaultSize), which inflated the header row and pushed
            // the picker/arena/monitor/controls rows off-screen in the free
            // app. Pin the placeholder to 0x0 instead.
            return View(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, 0)
            }
        }
        val chip = terminalButton("").apply {
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener {
                val ch = currentChallenge
                if (ch != null) { syncArenaToChallenge(ch); return@setOnClickListener }
                val cur = packArenas.indexOfFirst { it.id == Arena.all()[arenaIdx].id }
                arenaIdx = Arena.all().indexOf(packArenas[(cur + 1) % packArenas.size])
                updateArenaBtns()
            }
        }
        packChip = chip
        return chip
    }

    /**
     * Keep the pack chip in sync with [arenaIdx]. The flavor tier accent marks
     * paid content: amber in The Circuit, green in the free flavor (where this
     * chip is absent). Text names the pack arena or just the pack.
     */
    private fun updatePackChip() {
        val chip = packChip ?: return
        val cur = Arena.all()[arenaIdx]
        val pos = packArenas.indexOfFirst { it.id == cur.id }
        val tierAccent = getColor(R.color.rf_tier_accent)
        val tierAccentDark = getColor(R.color.rf_tier_accent_dark)
        chip.text = if (pos >= 0) "${packArenas[pos].pack}: ${packArenas[pos].name} >"
        else "ARENA PACK 1 >"
        chip.setTextColor(if (pos >= 0) C_BLACK else tierAccent)
        chip.background = panelBackground(if (pos >= 0) tierAccent else C_BLACK, tierAccentDark)
    }

    /** One weighted button row for [layouts], which start at registry index [base]. */
    private fun pickerRow(layouts: List<Layout>, base: Int): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(7), 0, 0)
        }
        layouts.forEachIndexed { n, l ->
            val i = base + n
            val b = terminalButton(l.name)
            arenaButtons.add(b)
            b.setOnClickListener {
                // While a challenge is loaded its layout is fixed: re-assert it
                // rather than desync the display from the fight's own world.
                val ch = currentChallenge
                if (ch != null) { syncArenaToChallenge(ch); return@setOnClickListener }
                arenaIdx = i; updateArenaBtns()
            }
            row.addView(b, weightedButton(if (n == 0) 0 else dp(5)))
        }
        return row
    }

    /** Highlight the selected arena button and push the picked layout into the view. */
    private fun updateArenaBtns() {
        arenaButtons.forEachIndexed { i, b ->
            b.setTextColor(if (i == arenaIdx) C_BLACK else C_BRIGHT)
            b.background = panelBackground(if (i == arenaIdx) C_GREEN else C_BLACK, C_GREEN_DARK)
        }
        if (::arena.isInitialized) arena.arenaLayout = Arena.all()[arenaIdx]
        updatePackChip()
    }

    /**
     * Point the live arena view and picker at a challenge's layout. The fight's
     * World already carries that layout (ChallengeRun.buildWorld), but the
     * display reads [arena.arenaLayout] — so without this the view would keep
     * showing the last-picked arena while the bots fight on a different one.
     */
    private fun syncArenaToChallenge(desc: ChallengeFormat.FightDescriptor) {
        val entry = Arena.byId(desc.layoutId) ?: return
        val idx = Arena.all().indexOf(entry)
        if (idx >= 0) {
            arenaIdx = idx
            updateArenaBtns()
            // Seed-derived family arenas fight on f(id, seed) (CHALLENGE-CODES
            // §7.4): show the layout the challenge actually plays, not the
            // seed-0 registry preview. Fixed arenas return the same instance.
            if (::arena.isInitialized)
                arena.arenaLayout = Arena.playableLayout(desc.layoutId, desc.seed) ?: entry
        }
    }

    // =========================================================================
    //  [ BUILD ] screen — R3: named modes + dials + opponent picker → RF1- code
    // =========================================================================

    /**
     * The [ BUILD ] screen (R3, spec §6/§6.1): one row of NAMED modes
     * (DUEL/MELEE/SIEGE/GAMBIT — UI-only presets that just set the dials),
     * the free dials (mode, spawn, HP A/B, arena, seed), and the opponent
     * picker for the PINNED slot B (the same 5 presets + user files the RUN
     * screen cycles). BUILD assembles the opponent's firmware, pins it in B,
     * leaves A open (slots = 0b01 always), embeds the source when it is
     * ≤ 2,048 B, and hands the canonical cold code to the [ CODES ] screen —
     * where it can be previewed, RUN, SAVED and SHARED like any import.
     */
    private fun buildBuildScreen(): LinearLayout {
        val screen = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        screen.addView(terminalText(12f, C_BRIGHT).apply {
            text = "BUILD CHALLENGE"
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(4))
        })
        screen.addView(terminalText(8f, C_DIM).apply {
            text = "NAMED MODE OR CUSTOM DIALS  ·  PICK AN OPPONENT  ·  BUILD"
            setPadding(0, 0, 0, dp(6))
        })

        // --- named modes (§6.1 — display layer; codes store the dials) ---
        screen.addView(terminalText(8f, C_DIM).apply { text = "MODE" })
        val modeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        arrayOf("DUEL", "MELEE", "SIEGE", "GAMBIT", "CUSTOM").forEachIndexed { i, n ->
            val b = terminalButton(n)
            b.setOnClickListener { applyNamedMode(i) }
            buildModeBtns.add(b)
            modeRow.addView(b, weightedButton(if (i == 0) 0 else dp(4)))
        }
        screen.addView(modeRow)

        // --- dials (CUSTOM exposes them; a named mode just presets them) ---
        val dial1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buildDialMode = terminalButton("MODE")
        buildDialSpawn = terminalButton("SPAWN")
        buildDialHpA = terminalButton("HP A")
        buildDialHpB = terminalButton("HP B")
        buildDialMode.setOnClickListener { buildModeDial = (buildModeDial + 1) % MODE_LABELS.size; syncBuilder() }
        buildDialSpawn.setOnClickListener { buildSpawnDial = (buildSpawnDial + 1) % SPAWN_LABELS.size; syncBuilder() }
        buildDialHpA.setOnClickListener { buildHpADial = (buildHpADial + 1) % HP_OPTS.size; syncBuilder() }
        buildDialHpB.setOnClickListener { buildHpBDial = (buildHpBDial + 1) % HP_OPTS.size; syncBuilder() }
        dial1.addView(buildDialMode, weightedButton())
        dial1.addView(buildDialSpawn, weightedButton(dp(4)))
        dial1.addView(buildDialHpA, weightedButton(dp(4)))
        dial1.addView(buildDialHpB, weightedButton(dp(4)))
        screen.addView(dial1, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6) })

        val dial2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        buildDialArena = terminalButton("ARENA")
        buildDialArena.setOnClickListener {
            buildArenaIdx = (buildArenaIdx + 1) % Arena.all().size; syncBuilder()
        }
        buildSeed = EditText(this).apply {
            background = null
            hint = "SEED  (DEC OR 0xHEX)"
            setHintTextColor(C_DIM)
            setTextColor(C_CODE)
            textSize = 10f
            typeface = pixelTypeface
            usePixelTextRendering()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            setSingleLine(true)
            setPadding(dp(4), 0, dp(4), 0)
        }
        buildBtnRngSeed = terminalButton("RND")
        buildBtnRngSeed.setOnClickListener {
            val s = kotlin.random.Random.nextInt(Int.MIN_VALUE, Int.MAX_VALUE)
            buildSeed.setText((s.toLong() and 0xFFFFFFFFL).toString(16))
            syncBuilder()
        }
        dial2.addView(buildDialArena, LinearLayout.LayoutParams(0, dp(42), 1f))
        dial2.addView(buildSeed, LinearLayout.LayoutParams(0, dp(42), 2f).apply { marginStart = dp(4) })
        dial2.addView(buildBtnRngSeed, weightedButton(dp(4)))
        screen.addView(dial2, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(5) })

        // --- opponent picker (the pinned slot B) ---
        screen.addView(terminalText(8f, C_DIM).apply {
            text = "OPPONENT  //  PINNED SLOT B"
            setPadding(0, dp(9), 0, dp(3))
        })
        buildBtnOpponent = terminalButton("OPPONENT  …")
        buildBtnOpponent.setOnClickListener {
            buildOppIdx = (buildOppIdx + 1) % slots.size
            syncBuilder()
        }
        screen.addView(buildBtnOpponent, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(46)
        ))
        buildOppNote = terminalText(8f, C_DIM)
        screen.addView(buildOppNote)

        // --- output code ---
        screen.addView(terminalText(8f, C_DIM).apply {
            text = "CODE"
            setPadding(0, dp(9), 0, dp(3))
        })
        val outFrame = FrameLayout(this).apply {
            background = panelBackground(C_BLACK, C_BORDER)
            setPadding(dp(5), dp(5), dp(5), dp(5))
        }
        buildOutput = terminalText(8f, C_CODE)
        buildOutput.text = "(BUILD A CODE)"
        outFrame.addView(buildOutput, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        screen.addView(outFrame, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(86)
        ))

        // --- actions ---
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buildBtnBuild = terminalButton("BUILD CODE", strong = true)
        buildBtnShare = terminalButton("SHARE")
        buildBtnCopy = terminalButton("COPY")
        buildBtnBack = terminalButton("< CODES")
        actions.addView(buildBtnBuild, weightedButton())
        actions.addView(buildBtnShare, weightedButton(dp(4)))
        actions.addView(buildBtnCopy, weightedButton(dp(4)))
        actions.addView(buildBtnBack, weightedButton(dp(4)))
        screen.addView(actions, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(9) })

        buildBtnBuild.setOnClickListener { doCodeBuild() }
        buildBtnShare.setOnClickListener { shareCode(lastBuiltCode) }
        buildBtnCopy.setOnClickListener { copyCode(lastBuiltCode) }
        buildBtnBack.setOnClickListener { showCodesMode() }
        syncBuilder()
        return screen
    }

    /** §6.1 named modes — UI-only presets over the dials (codes never store a name). */
    private fun applyNamedMode(i: Int) {
        when (i) {
            0 -> { // DUEL: KO · SEPARATE · 100/100
                buildModeDial = ChallengeFormat.MODE_KO; buildSpawnDial = SPAWN_SEPARATE
                buildHpADial = 1; buildHpBDial = 1
            }
            1 -> { // MELEE: KO · CLASH · 100/100
                buildModeDial = ChallengeFormat.MODE_KO; buildSpawnDial = SPAWN_CLASH
                buildHpADial = 1; buildHpBDial = 1
            }
            2 -> { // SIEGE: 300 ticks · SEPARATE · 200/200
                buildModeDial = ChallengeFormat.MODE_300; buildSpawnDial = SPAWN_SEPARATE
                buildHpADial = 3; buildHpBDial = 3
            }
            3 -> { // GAMBIT: KO · SEPARATE · 50/150 (pinned side over-equipped)
                buildModeDial = ChallengeFormat.MODE_KO; buildSpawnDial = SPAWN_SEPARATE
                buildHpADial = 0; buildHpBDial = 2
            }
            else -> { /* CUSTOM — the dials stay exactly as the user set them */ }
        }
        buildModeIdx = i
        syncBuilder()
    }

    /** Do the current dials still match named mode [i]? (row highlighting) */
    private fun dialsMatchNamed(i: Int): Boolean = when (i) {
        0 -> buildModeDial == ChallengeFormat.MODE_KO && buildSpawnDial == SPAWN_SEPARATE &&
            buildHpADial == 1 && buildHpBDial == 1
        1 -> buildModeDial == ChallengeFormat.MODE_KO && buildSpawnDial == SPAWN_CLASH &&
            buildHpADial == 1 && buildHpBDial == 1
        2 -> buildModeDial == ChallengeFormat.MODE_300 && buildSpawnDial == SPAWN_SEPARATE &&
            buildHpADial == 3 && buildHpBDial == 3
        3 -> buildModeDial == ChallengeFormat.MODE_KO && buildSpawnDial == SPAWN_SEPARATE &&
            buildHpADial == 0 && buildHpBDial == 2
        else -> false
    }

    /** Refresh every builder label + the named-mode row highlight. */
    private fun syncBuilder() {
        if (!::buildDialMode.isInitialized || slots.isEmpty()) return
        if (buildOppIdx >= slots.size) buildOppIdx = (slots.size - 1).coerceAtLeast(0)
        buildDialMode.text = "MODE ${MODE_LABELS[buildModeDial]} >"
        buildDialSpawn.text = "SPAWN ${SPAWN_LABELS[buildSpawnDial]} >"
        buildDialHpA.text = "HP A ${HP_OPTS[buildHpADial]} >"
        buildDialHpB.text = "HP B ${HP_OPTS[buildHpBDial]} >"
        buildDialArena.text = "ARENA ${Arena.all()[buildArenaIdx].name} >"
        buildBtnOpponent.text = "OPPONENT  ${slots[buildOppIdx].name}  >"
        val src = sourceFor(slots[buildOppIdx])
        val asm = assemble(src)
        if (!asm.ok) {
            buildOppNote.text = "ASSEMBLER ERROR  // LINE ${asm.errors.first().line}: ${asm.errors.first().msg}"
            buildOppNote.setTextColor(C_ERR)
        } else {
            val srcBytes = src.toByteArray(Charsets.UTF_8).size
            buildOppNote.text = if (srcBytes <= ChallengeFormat.MAX_SRC) {
                "SRC ${srcBytes} B ≤ ${ChallengeFormat.MAX_SRC}  //  EMBEDS SOURCE"
            } else {
                "SRC ${srcBytes} B > ${ChallengeFormat.MAX_SRC}  //  BINARY ONLY  (FW ${asm.code.size} B)"
            }
            buildOppNote.setTextColor(C_DIM)
        }
        // Highlight the named row whose dials still match; else CUSTOM.
        val effective = (0..3).firstOrNull { buildModeIdx == it && dialsMatchNamed(it) } ?: 4
        buildModeBtns.forEachIndexed { i, b ->
            b.setTextColor(if (i == effective) C_BLACK else C_BRIGHT)
            b.background = panelBackground(if (i == effective) C_GREEN else C_BLACK, C_GREEN_DARK)
        }
    }

    /** Seed dial value: decimal or 0x-hex, any 32-bit value; empty = 0. */
    private fun parseSeed(text: String): Int? {
        val t = text.trim()
        if (t.isEmpty()) return 0
        return try {
            if (t.startsWith("0x") || t.startsWith("0X")) {
                (t.substring(2).toLong(16) and 0xFFFFFFFFL).toInt()
            } else {
                (t.toLong() and 0xFFFFFFFFL).toInt()
            }
        } catch (e: NumberFormatException) {
            null
        }
    }

    /**
     * R3: BUILD CODE — assemble the picked opponent, pin it in slot B (A stays
     * open, slots = 0b01), embed the source when it is ≤ 2,048 B, encode the
     * canonical COLD code (no recorded result), and import it onto the
     * [ CODES ] screen where preview / RUN / SAVE / SHARE all apply.
     */
    private fun doCodeBuild() {
        val seed = parseSeed(buildSeed.text.toString())
        if (seed == null) {
            status.text = "SEED INVALID  // DECIMAL OR 0x HEX"
            return
        }
        if (slots.isEmpty()) {
            status.text = "NO OPPONENT AVAILABLE"
            return
        }
        val slot = slots[buildOppIdx.coerceAtMost(slots.size - 1)]
        val src = sourceFor(slot)
        val asm = assemble(src)
        if (!asm.ok) {
            status.text = "ASSEMBLER ERROR  // LINE ${asm.errors.first().line}: ${asm.errors.first().msg}"
            return
        }
        val fw = asm.code
        if (fw.size % 3 != 0 || fw.size > ChallengeFormat.MAX_FW) {
            status.text = "OPPONENT TOO BIG  // FW ${fw.size} B > ${ChallengeFormat.MAX_FW}"
            return
        }
        val srcB: String? =
            if (src.toByteArray(Charsets.UTF_8).size <= ChallengeFormat.MAX_SRC) src else null
        val desc = ChallengeFormat.FightDescriptor(
            ChallengeFormat.ENGINE_VERSION,
            Arena.all()[buildArenaIdx].id,
            0b01,                          // A open, B pinned — always
            seed,
            buildModeDial,
            buildSpawnDial,
            HP_OPTS[buildHpADial],
            HP_OPTS[buildHpBDial],
            null,                          // cold: the author never ran it
            ByteArray(0),
            fw,
            null,
            srcB,
        )
        val code = try {
            ChallengeFormat.encode(desc)
        } catch (e: IllegalArgumentException) {
            status.text = "BUILD FAILED  // ${e.message ?: "INVALID DESCRIPTOR"}"
            return
        }
        lastBuiltCode = code
        buildOutput.text = code
        status.text = "CODE BUILT  // ${code.length} CHARS"
        codePaste.setText(code)
        importCode(code)
        setMode(AppMode.CODES)
    }

    // =========================================================================
    //  [ CODES ] screen — R2 challenge-code import / preview / run / save
    // =========================================================================

    /**
     * The [ CODES ] screen (R2). Paste or receive a challenge code (`RF1-…`),
     * preview its arena + rules — the pinned opponent's firmware is hard-hidden
     * in v1 (LOCKED DECISION, no reveal) — pick a bot for each open slot, then
     * RUN the deterministic fight (eng=1, via [ChallengeRun.buildWorld]) or SAVE
     * the canonical code to the store. See CHALLENGE-CODES.md.
     */
    private fun buildCodesScreen(): LinearLayout {
        val screen = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        screen.addView(terminalText(12f, C_BRIGHT).apply {
            text = "CHALLENGE CODES"
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(4))
        })
        screen.addView(terminalText(8f, C_DIM).apply {
            text = "PASTE A RF1-… CODE, TAP IMPORT   //   OR OPEN A SHARE"
            setPadding(0, 0, 0, dp(6))
        })

        // --- paste box ---
        val pasteFrame = FrameLayout(this).apply {
            background = panelBackground(C_BLACK, C_BORDER)
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        codePaste = EditText(this).apply {
            background = null
            hint = "RF1-…"
            setHintTextColor(C_DIM)
            setTextColor(C_CODE)
            textSize = 10f
            gravity = Gravity.TOP or Gravity.START
            typeface = pixelTypeface
            usePixelTextRendering()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            maxLines = 3
            minLines = 2
        }
        pasteFrame.addView(codePaste, matchFrame())
        screen.addView(pasteFrame, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(74)
        ))

        // --- paste buttons ---
        val pasteBtns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        codePasteBtnPaste = terminalButton("PASTE")
        codePasteBtnImport = terminalButton("IMPORT", strong = true)
        codePasteBtnClear = terminalButton("CLEAR")
        codeBtnBuild = terminalButton("BUILD")
        pasteBtns.addView(codePasteBtnPaste, weightedButton())
        pasteBtns.addView(codePasteBtnImport, weightedButton(dp(5)))
        pasteBtns.addView(codePasteBtnClear, weightedButton(dp(5)))
        pasteBtns.addView(codeBtnBuild, weightedButton(dp(5)))
        screen.addView(pasteBtns, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6) })

        // --- P3 (pro flavor only): read-only library of codes imported from
        // the free app. A dim list; tapping an entry loads it into the import
        // flow below (it can be RUN/copied/viewed, never re-saved or shared
        // as a new save). Free has no rf_pro_migration resource → no row. ---
        if (proMigrationEnabled) {
            codesImportedRow = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
            }
            screen.addView(codesImportedRow, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) })
        }

        // --- error card (hidden until a failed import) ---
        codeErrorCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = panelBackground(C_DARK, C_BORDER)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            visibility = View.GONE
        }
        codeErrorCard.addView(terminalText(9f, C_ERR).apply { text = "IMPORT FAILED" })
        codeErrorText = terminalText(8f, C_BRIGHT)
        codeErrorCard.addView(codeErrorText)
        screen.addView(codeErrorCard, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6) })

        // --- preview card (hidden until a valid import) ---
        codePreviewCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = panelBackground(C_PANEL, C_GREEN_DARK)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            visibility = View.GONE
        }
        // P3 layout rev C (user pick, 2026-10-24): the codes-screen card was
        // so dense that the weighted arena frame crushed to a ~17 dp sliver,
        // text lines stacked with 0 dp gaps, and the CIRCUIT button fell below
        // the fold. Rev C puts the pinned-firmware listing IN the arena row,
        // as a second column beside the placeholder — the locked arena's dead
        // horizontal space hosts the listing instead of an extra 82 dp row —
        // and the picker/action rows become compact 34 dp buttons.
        val arenaRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val arenaFrame = FrameLayout(this).apply {
            background = panelBackground(C_BLACK, C_BORDER)
            setPadding(dp(7), dp(7), dp(7), dp(7))
        }
        previewArena = ArenaView(this)
        arenaFrame.addView(previewArena, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER
        ))
        arenaRow.addView(arenaFrame, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.2f))

        codeMode = terminalText(11f, C_BRIGHT).apply { gravity = Gravity.CENTER }
        codePreviewCard.addView(codeMode, cardLineParams())
        codeRules = terminalText(8f, C_GREEN)
        codePreviewCard.addView(codeRules, cardLineParams())
        codeOpponent = terminalText(8f, C_DIM)
        codePreviewCard.addView(codeOpponent, cardLineParams())

        // P3: pinned opponent firmware as a DISASSEMBLED LISTING — shown only
        // for PRO-locked codes (the Circuit ships its opponents' listings as a
        // taste; free↔free codes keep the byte-count display untouched).
        // Horizontal scroll with isFillViewport=false: long lines CLIP, never
        // wrap. Tap the pane to toggle compact ↔ full.
        codeFwListing = terminalText(8f, C_DIM)
        codeFwScroll = HorizontalScrollView(this).apply {
            isFillViewport = false
            setHorizontalScrollBarEnabled(false)
            visibility = View.GONE
            isClickable = true
            isFocusable = true
            setOnClickListener {
                codeFwExpanded = !codeFwExpanded
                renderCodeFw()
            }
        }
        codeFwScroll.addView(codeFwListing)
        // Rev C: second column of the arena row (rev B's vertical listing row
        // is gone; the pane only appears when a listing exists).
        codeFwScroll.background = panelBackground(C_BLACK, C_BORDER)
        codeFwScroll.setPadding(dp(4), dp(4), dp(4), dp(4))
        // Column split: the RIGHT column is sized so the widest listing line
        // (~56 chars: a 16-byte hexdump row ≈ 53.6 dp at 8f pixel font) fits;
        // the left placeholder takes the rest.
        arenaRow.addView(codeFwScroll, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.MATCH_PARENT, 1.35f
        ).apply { leftMargin = dp(5) })
        // Row height policy (set per state in buildPreview): locked = fixed
        // 160 dp, exactly the listing pane's content (8 lines + padding) —
        // no weight, or the only-weighted-child rule inflates it to 350 dp
        // of empty frame; unlocked = weight 1f, the arena uses all slack.
        codeArenaRowParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6) }
        codePreviewCard.addView(arenaRow, codeArenaRowParams)

        codeResult = terminalText(8f, C_CODE).apply { visibility = View.GONE }
        codePreviewCard.addView(codeResult, cardLineParams())
        // Circuit strings on screen (user rules 2026-10-24): the paid product
        // is always THE CIRCUIT and only the words "THE CIRCUIT" render AMBER
        // via circuitText(); the surrounding copy stays the normal green.
        codeWarning = terminalText(8f, C_GREEN).apply { visibility = View.GONE }
        codePreviewCard.addView(codeWarning, cardLineParams())

        // Picker label as its OWN line (baseline look) — the Rev C inline
        // variant was reverted on user request 2026-10-24. Picker buttons
        // stay compact 34 dp.
        codePickLabel = terminalText(8f, C_DIM).apply {
            contentDescription = "pick_bot_label"
        }
        codePreviewCard.addView(codePickLabel, cardLineParams())
        codePickRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(5), 0, 0)
        }
        codePreviewCard.addView(codePickRow)

        // R3: local fight history for this code (app feature, outside the format).
        codeHistory = terminalText(8f, C_DIM).apply { visibility = View.GONE }
        codePreviewCard.addView(codeHistory, cardLineParams())

        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(5), 0, 0)
        }
        codeBtnRun = terminalButton("RUN", strong = true)
        codeBtnShare = terminalButton("SHARE")
        codeBtnCopy = terminalButton("COPY")
        codeBtnSave = terminalButton("SAVE")
        // Rev C: 34 dp compact action buttons (still a usable touch target).
        actionRow.addView(codeBtnRun, LinearLayout.LayoutParams(0, dp(34), 1f))
        actionRow.addView(codeBtnShare, weightedButton(dp(5), 34))
        actionRow.addView(codeBtnCopy, weightedButton(dp(5), 34))
        actionRow.addView(codeBtnSave, weightedButton(dp(5), 34))
        codePreviewCard.addView(actionRow)

        // R3: SEND BACK — visible only after a player win in an imported
        // challenge (spec §8: draw/loss = no response).
        codeBtnRespond = terminalButton("SEND BACK", strong = true).apply { visibility = View.GONE }
        codePreviewCard.addView(codeBtnRespond, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(42)
        ).apply { topMargin = dp(5) })

        // P3: quiet cross-sell row — appears ONLY when a Pro-arena code is
        // locked in this app (lock != null). One dim copy line + the link
        // button; positioning is arenas/riddles/firmware, never combat.
        // Rev C: 34 dp button so the row always stays above the fold.
        // Only "THE CIRCUIT" is amber (circuitText); the rest stays green.
        codeCircuitNote = terminalText(8f, C_GREEN).apply {
            text = circuitText(
                "MORE ARENAS, RIDDLES AND FIRMWARE TO COLLECT IN THE CIRCUIT."
            )
            visibility = View.GONE
        }
        codeBtnCircuit = terminalButton("PLAY IN THE CIRCUIT >").apply {
            visibility = View.GONE
        }
        codeBtnCircuit.text = circuitText("PLAY IN THE CIRCUIT >")
        codeBtnCircuit.setOnClickListener { openProListing() }
        codePreviewCard.addView(codeCircuitNote, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6) })
        codePreviewCard.addView(codeBtnCircuit, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(34)
        ).apply { topMargin = dp(6) })

        screen.addView(codePreviewCard, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ).apply { topMargin = dp(6) })

        // --- wire the buttons ---
        codePasteBtnPaste.setOnClickListener { pasteFromClipboard() }
        codePasteBtnImport.setOnClickListener {
            importedReadOnly = false        // pasted text is the player's own import
            importCode(codePaste.text.toString())
            refreshCodesActions()
        }
        codePasteBtnClear.setOnClickListener {
            codePaste.setText("")
            importedDesc = null
            importedReadOnly = false
            codePreviewCard.visibility = View.GONE
            codeErrorCard.visibility = View.GONE
            codeBtnRespond.visibility = View.GONE
        }
        codeBtnRun.setOnClickListener { val d = importedDesc ?: return@setOnClickListener; doCodeRun(d) }
        codeBtnSave.setOnClickListener { doCodeSave() }
        codeBtnBuild.setOnClickListener { showBuildMode() }
        codeBtnShare.setOnClickListener { shareCode(importedCode) }
        codeBtnCopy.setOnClickListener { copyCode(importedCode) }
        codeBtnRespond.setOnClickListener { doCodeRespond() }
        return screen
    }

    /** R3: switch to the [ BUILD ] screen (keyboard away, dials as-is). */
    private fun showBuildMode() {
        handler.removeCallbacks(tickLoop)
        setMode(AppMode.BUILD)
        hideKeyboard()
        syncBuilder()
    }

    /** Switch to the [ CODES ] tab and settle the keyboard. */
    private fun showCodesMode() {
        handler.removeCallbacks(tickLoop)
        setMode(AppMode.CODES)
        hideKeyboard()
        refreshCodesActions()
        refreshImportedCodes()
    }

    /**
     * R3: re-arm the context actions on the codes screen. SEND BACK shows only
     * when (a) a code is imported and (b) the last finished fight of THAT code
     * was a player win (spec §8 — draw/loss never unlocks a response). Also
     * refreshes the local history line for the imported code.
     */
    private fun refreshCodesActions() {
        val desc = importedDesc
        val pair = lastCodeOutcome
        // The win must belong to THIS code: compare canonical text (a
        // re-import of the same code produces a fresh descriptor instance).
        val unlock = desc != null && pair != null &&
            lastCodeText != null && lastCodeText == importedCode &&
            ChallengeResponse.isPlayerWin(pair.first, pair.second)
        codeBtnRespond.visibility = if (unlock) View.VISIBLE else View.GONE
        // P3: imported codes are read-only library content — no SAVE and no
        // SHARE as a new save (RUN / COPY stay available).
        codeBtnSave.visibility = if (importedReadOnly) View.GONE else View.VISIBLE
        codeBtnShare.visibility = if (importedReadOnly) View.GONE else View.VISIBLE
        refreshCodeHistory()
    }

    /** R3: the per-code + per-arena history line under the pickers. */
    private fun refreshCodeHistory() {
        val code = importedCode
        if (code == null) {
            codeHistory.visibility = View.GONE
            return
        }
        val h = store.codeHistory(code)
        val parts = ArrayList<String>()
        if (h != null) {
            parts.add("CODE  ${h.played}P ${h.wins}W ${h.losses}L ${h.draws}D")
        } else {
            parts.add("CODE  NOT PLAYED YET")
        }
        val arenas = store.arenaHistory()
        if (arenas.isNotEmpty()) {
            parts.add("ARENAS " + arenas.joinToString("  ") { "${it.first} ${it.second}/${it.third}" })
        }
        codeHistory.text = parts.joinToString("   //   ")
        codeHistory.visibility = View.VISIBLE
    }

    /**
     * P3 (pro flavor only): the read-only "IMPORTED FROM FREE" list on the
     * CODES tab. Entries come straight from the store (imported flag set by
     * the ZIP import); no import bookkeeping in the UI.
     */
    private fun refreshImportedCodes() {
        val row = codesImportedRow ?: return
        row.removeAllViews()
        val imported = store.listCodes().filter { it.imported }
        if (imported.isEmpty()) {
            row.visibility = View.GONE
            return
        }
        row.addView(terminalText(8f, C_DIM).apply { text = "IMPORTED FROM FREE  (READ-ONLY)" })
        for (rec in imported) {
            row.addView(terminalText(8f, C_CODE).apply {
                text = "  > ${rec.name}   //  TAP TO LOAD"
                isClickable = true
                isFocusable = true
                setOnClickListener { loadImportedCode(rec.name) }
            })
        }
        row.visibility = View.VISIBLE
    }

    /** Load an imported (read-only) code into the preview flow below the list. */
    private fun loadImportedCode(name: String) {
        val raw = store.readCode(name) ?: return
        importedReadOnly = true
        codePaste.setText(raw)
        importCode(raw)
        refreshCodesActions()
    }

    /** Copy the clipboard text into the paste box (no auto-import). */
    private fun pasteFromClipboard() {
        val clip = getSystemService(CLIPBOARD_SERVICE) as? android.content.ClipboardManager
        val text = clip?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)
        if (!text.isNullOrEmpty()) codePaste.setText(text)
    }

    /**
     * Parse a pasted code and show either its preview (Ok) or the exact §10
     * error string (Err). A valid import re-canonicalizes the code (parse →
     * encode round-trip) so the stored/played form is always canonical.
     */
    private fun importCode(raw: String) {
        val code = raw.trim()   // tolerate pasted/share text with surrounding whitespace
        when (val r = ChallengeFormat.parse(code)) {
            is ChallengeFormat.ParseResult.Err -> {
                importedDesc = null
                importedCode = null
                codePreviewCard.visibility = View.GONE
                codeErrorText.text = r.message
                codeErrorCard.visibility = View.VISIBLE
                status.text = "IMPORT FAILED"
            }
            is ChallengeFormat.ParseResult.Ok -> {
                importedDesc = r.desc
                importedCode = ChallengeFormat.encode(r.desc)
                codeErrorCard.visibility = View.GONE
                codePreviewCard.visibility = View.VISIBLE
                status.text = "CODE OK  // ${r.desc.codeMode}"
                buildPreview(r.desc, r.warning, r.lock)
            }
        }
    }

    /**
     * Populate the preview card for a parsed code: static arena + spawn markers
     * (opponent firmware hard-hidden), mode/rules, the hidden-slot panel, the
     * recorded result badge, any eng-skew warning, and one bot picker per open
     * slot.
     */
    private fun buildPreview(
        desc: ChallengeFormat.FightDescriptor,
        warning: String?,
        lock: String?,
    ) {
        val entry = Arena.byId(desc.layoutId)
        // Preview the geometry the code actually fights on: seed-derived family
        // arenas preview f(id, seed) (§7.4); fixed arenas and locked (null)
        // previews behave exactly as before.
        val layout = if (entry != null) Arena.playableLayout(desc.layoutId, desc.seed) ?: entry else null
        previewArena.arenaLayout = layout
        previewArena.previewSpawns = emptyList()
        previewArena.idleText = if (lock != null) "CIRCUIT ARENA — PREVIEW UNAVAILABLE" else "SYSTEM IDLE"

        if (layout != null) {
            val cells = spawnCellsFor(desc, layout)
            val markers = ArrayList<ArenaView.PreviewMarker>()
            markers.add(ArenaView.PreviewMarker(
                cells[0], 'A', if (desc.slotAOpen) C_YELLOW else C_GREEN,
                if (desc.slotAOpen) "YOU" else "HIDDEN"
            ))
            markers.add(ArenaView.PreviewMarker(
                cells[1], 'B', if (desc.slotBOpen) C_YELLOW else C_GREEN,
                if (desc.slotBOpen) "YOU" else "HIDDEN"
            ))
            previewArena.previewSpawns = markers
        }

        // P3: even when the arena itself is locked here, its NAME is shared
        // engine metadata (Arena.proArenaNames — names only, no geometry):
        // the player sees WHICH arena the code fights on, nothing playable.
        val proArenaName = if (lock != null) Arena.proArenaNames(desc.layoutId) else null
        codeMode.text = if (proArenaName != null)
            "ARENA: $proArenaName  ·  ${desc.codeMode}"
        else desc.codeMode
        val rules = StringBuilder()
        rules.append("MODE ").append(desc.modeLabel)
            .append("  ·  SPAWN ").append(desc.spawnLabel)
            .append("  ·  HP ").append(desc.hpA).append('/').append(desc.hpB)
        if (desc.spawn == SPAWN_SCATTER) rules.append("  ·  SEED ").append(desc.seed)
        codeRules.text = rules.toString()

        val hidden = ArrayList<String>()
        if (!desc.slotAOpen) hidden.add("A ▸ HIDDEN · ${desc.fwA.size} B")
        if (!desc.slotBOpen) hidden.add("B ▸ HIDDEN · ${desc.fwB.size} B")
        codeOpponent.text =
            if (hidden.isEmpty()) "NO HIDDEN SLOT   //   YOU PLAY BOTH SLOTS"
            else hidden.joinToString("    ")
        buildCodeFwListing(desc, lock)

        // Rev C row policy: locked card = fixed row hosting the listing;
        // Rev D raised it 160 -> 178 dp (listing content ~170 dp) so the
        // arena placeholder breathes instead of hugging the frame border.
        // No weight, or the row absorbs all the card's slack as 350 dp of
        // empty frame; unlocked card = the arena keeps all slack via weight 1f.
        codeArenaRowParams.height = if (codeFwFull.isNotEmpty()) dp(178) else 0
        codeArenaRowParams.weight = if (codeFwFull.isNotEmpty()) 0f else 1f

        val res = desc.result
        if (res != null && res.winner != 0xFF) {
            codeResult.visibility = View.VISIBLE
            val winner = when (res.winner) { 0 -> "A WINS"; 1 -> "B WINS"; else -> "DRAW" }
            codeResult.text = "RECORDED: $winner  ·  T+${res.ticks}  ·  HP ${res.endHp}"
        } else {
            codeResult.visibility = View.GONE
        }

        val notices = listOfNotNull(lock, warning)
        if (notices.isNotEmpty()) {
            codeWarning.visibility = View.VISIBLE
            codeWarning.text = circuitText(notices.joinToString("\n"))
        } else {
            codeWarning.visibility = View.GONE
        }

        codeBtnRun.isEnabled = lock == null
        codeBtnRun.text = if (lock == null) "RUN" else "LOCKED"

        // P3: the cross-sell row exists only for locked codes — never on an
        // unlocked preview, so free↔free flows keep their exact old card.
        // Rev C: short copy for the locked card (keeps the row single-line).
        if (lock != null)
            codeCircuitNote.text = circuitText(
                "MORE ARENAS, RIDDLES AND FIRMWARE TO COLLECT IN THE CIRCUIT."
            )
        codeCircuitNote.visibility = if (lock != null) View.VISIBLE else View.GONE
        codeBtnCircuit.visibility = if (lock != null) View.VISIBLE else View.GONE

        // One bot picker per open slot (cycles through the same slot list the
        // RUN screen uses: 5 presets + user files).
        codePickers.clear()
        codePickSlotOf.clear()
        codePickRow.removeAllViews()
        codePickIdx[0] = 0
        codePickIdx[1] = 0
        val openSlots = ArrayList<Int>()
        if (desc.slotAOpen) openSlots.add(0)
        if (desc.slotBOpen) openSlots.add(1)
        codePickLabel.text =
            if (openSlots.isEmpty()) "" else "PICK " + (if (openSlots.size > 1) "BOTH BOTS" else "YOUR BOT")
        openSlots.forEachIndexed { i, slotIdx ->
            val b = terminalButton("…")
            b.setOnClickListener {
                codePickIdx[slotIdx] = (codePickIdx[slotIdx] + 1) % slots.size
                updateCodePickers()
            }
            codePickers.add(b)
            codePickSlotOf.add(slotIdx)
            // Rev C: compact 34 dp picker buttons.
            codePickRow.addView(b, weightedButton(if (i > 0) dp(5) else 0, 34))
        }
        updateCodePickers()
    }

    /**
     * P3: opponent firmware listing for PRO-locked codes only. Pinned images
     * render as a mnemonic listing through the engine's own disassembler
     * (Disasm); a malformed (not-multiple-of-3) image falls back to a compact
     * hex dump instead. UNLOCKED codes always get an empty listing — the
     * free↔free secrecy (byte counts only) is unchanged.
     */
    private fun buildCodeFwListing(
        desc: ChallengeFormat.FightDescriptor,
        lock: String?,
    ) {
        codeFwFull.clear()
        codeFwCompact.clear()
        codeFwExpanded = false
        if (lock != null) {
            for ((slot, fw) in listOf("A" to desc.fwA, "B" to desc.fwB)) {
                if (fw.isEmpty()) continue   // open slot — nothing pinned
                codeFwFull.add("$slot ▸ OPPONENT FIRMWARE  ·  ${fw.size} B")
                if (fw.size % 3 == 0) {
                    for (i in Disasm.disassemble(fw))
                        codeFwFull.add("  ${"%04X  %s".format(i.offset, i.text)}")
                } else {
                    codeFwFull.add("  (NO LISTING  //  IMAGE NOT A MULTIPLE OF 3)")
                    var off = 0
                    while (off < fw.size) {
                        val n = minOf(16, fw.size - off)
                        val hex = (0 until n).joinToString(" ") { "%02X".format(fw[off + it]) }
                        codeFwFull.add("  %04X  $hex".format(off))
                        off += n
                    }
                }
            }
            // Rev C: the listing lives in its own pane BESIDE the arena
            // placeholder — the opponent line needs no "BELOW" pointer
            // (the baseline appended "// OPPONENT LISTING BELOW" here).
        }
        if (codeFwFull.isNotEmpty()) {
            // Compact view: per-slot header + first 5 instruction lines + a
            // note; the full view (after a tap) lists everything, clipped.
            var i = 0
            while (i < codeFwFull.size) {
                codeFwCompact.add(codeFwFull[i])
                i++
                var shown = 0
                while (i < codeFwFull.size && codeFwFull[i].startsWith("  ") && shown < 5) {
                    codeFwCompact.add(codeFwFull[i])
                    i++
                    shown++
                }
                if (i < codeFwFull.size && codeFwFull[i].startsWith("  "))
                    codeFwCompact.add("  …  TAP FOR FULL LISTING")
            }
        }
        renderCodeFw()
    }

    private fun renderCodeFw() {
        if (codeFwFull.isEmpty()) {
            codeFwScroll.visibility = View.GONE
            return
        }
        codeFwListing.text = (if (codeFwExpanded) codeFwFull else codeFwCompact).joinToString("\n")
        codeFwScroll.visibility = View.VISIBLE
    }

    /**
     * The A/B spawn cells for a code's preview, computed EXACTLY as
     * [ChallengeRun.buildWorld] does (SEPARATE/CLASH fixed cells; SCATTER draws
     * through a fresh world LCG seeded with the code's seed) so the markers land
     * on the very cells the bots start on.
     */
    private fun spawnCellsFor(desc: ChallengeFormat.FightDescriptor, layout: Layout): IntArray {
        val w = World(layout)
        w.setSeed(desc.seed)
        val ax: Int; val ay: Int; val bx: Int; val by: Int
        if (desc.spawn == SPAWN_SCATTER) {
            val s = scatterSpawn(layout, w::nextRand)
            ax = s.first.x; ay = s.first.y; bx = s.second.x; by = s.second.y
        } else {
            val (a, b) = layout.spawnCells(desc.spawn)
            ax = a.x; ay = a.y; bx = b.x; by = b.y
        }
        return intArrayOf(ay * GRID + ax, by * GRID + bx)
    }

    /** Refresh each picker button's label from its current pick. */
    private fun updateCodePickers() {
        codePickers.forEachIndexed { i, b ->
            val slotIdx = codePickSlotOf[i]
            val slot = slots[codePickIdx[slotIdx]]
            b.text = "${if (slotIdx == 0) "A" else "B"}  ${slot.name}  >"
        }
    }

    /**
     * SAVE the imported code to the store. The suggested name is editable and
     * visibly includes `.chc`; omitting the suffix adds it automatically.
     */
    private fun doCodeSave() {
        if (importedReadOnly) {
            // P3: imported codes are the OTHER app's library content — they
            // load and run, but are never re-saved as new saves here.
            status.text = "IMPORTED CODES ARE READ-ONLY"
            return
        }
        importedDesc ?: return
        val code = importedCode ?: return
        val suggested = autoCodeName()
        val input = EditText(this).apply {
            setText(suggested)
            setTextColor(C_CODE)
            setHintTextColor(C_DIM)
            textSize = 13f
            typeface = pixelTypeface
            usePixelTextRendering()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            setSingleLine(true)
            // Keep the real suffix visible while making the generated base name
            // immediately replaceable.
            setSelection(0, suggested.length - ChallengeCodeFileName.EXT.length)
        }
        val inputFrame = FrameLayout(this).apply {
            setPadding(dp(20), dp(4), dp(20), 0)
            addView(input, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("SAVE CHALLENGE CODE")
            .setMessage("FILE NAME  //  .chc IS ADDED IF OMITTED")
            .setView(inputFrame)
            .setNegativeButton("CANCEL", null)
            .setPositiveButton("SAVE", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = ChallengeCodeFileName.normalize(input.text.toString())
                if (name == null) {
                    input.error = "USE A FILE NAME UP TO ${ChallengeCodeFileName.MAX_LENGTH} CHARS"
                    return@setOnClickListener
                }
                if (store.codeExists(name)) {
                    AlertDialog.Builder(this)
                        .setTitle("OVERWRITE $name?")
                        .setMessage("A SAVED CHALLENGE WITH THIS NAME ALREADY EXISTS.")
                        .setNegativeButton("CANCEL", null)
                        .setPositiveButton("OVERWRITE") { _, _ ->
                            saveCodeAs(name, code)
                            dialog.dismiss()
                        }
                        .show()
                } else {
                    saveCodeAs(name, code)
                    dialog.dismiss()
                }
            }
            input.requestFocus()
            dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        }
        dialog.show()
    }

    private fun saveCodeAs(name: String, code: String) {
        if (!store.upsertCode(name, code)) {
            status.text = "SAVE FAILED  // $name"
            return
        }
        status.text = "SAVED  $name"
        printLine("SAVED CHALLENGE CODE  $name   //   ${code.length} CHARS")
        printLine("  $code")
    }

    /** Next free auto-name for a saved code (CODE1.chc, CODE2.chc, …). */
    private fun autoCodeName(): String {
        return ChallengeCodeFileName.nextDefault(store.listCodes().map { it.name })
    }

    /**
     * RUN a parsed challenge deterministically (eng=1): assemble the picked
     * firmware for each OPEN slot, build the exact fight via
     * [ChallengeRun.buildWorld] (pinned slots load the code's binary opcodes),
     * adopt it on the RUN screen and tick it. Identical code + identical picks
     * → identical result, matching the headless codec tests.
     */
    private fun doCodeRun(desc: ChallengeFormat.FightDescriptor) {
        resetRunResponseAction()
        if (Arena.byId(desc.layoutId) == null) {
            setMode(AppMode.CODES)
            status.text = ChallengeFormat.E_PRO_ARENA
            return
        }
        val resA = if (desc.slotAOpen) assemble(sourceFor(slots[codePickIdx[0]])) else null
        val resB = if (desc.slotBOpen) assemble(sourceFor(slots[codePickIdx[1]])) else null
        val bad = when {
            resA != null && !resA.ok -> "A  LINE ${resA.errors.first().line}: ${resA.errors.first().msg}"
            resB != null && !resB.ok -> "B  LINE ${resB.errors.first().line}: ${resB.errors.first().msg}"
            else -> null
        }
        if (bad != null) {
            setMode(AppMode.CODES)
            status.text = "ASSEMBLER ERROR  // $bad"
            return
        }
        val openA: Bot? = if (desc.slotAOpen) {
            val s = slots[codePickIdx[0]]
            Bot(s.name, s.glyph, s.color, 0, 0, DIR_E, sourceFor(s))
        } else null
        val openB: Bot? = if (desc.slotBOpen) {
            val s = slots[codePickIdx[1]]
            Bot(s.name, s.glyph, s.color, 0, 0, DIR_E, sourceFor(s))
        } else null
        val w = ChallengeRun.buildWorld(desc, openA, openB)
        val codes = arrayOf<RunController.BotCode?>(
            if (desc.slotAOpen) RunController.BotCode(sourceFor(slots[codePickIdx[0]]), resA!!.lineOf) else null,
            if (desc.slotBOpen) RunController.BotCode(sourceFor(slots[codePickIdx[1]]), resB!!.lineOf) else null,
        )
        currentChallenge = desc
        lastCodeText = importedCode   // R3: history key for this fight (canonical form)
        handler.removeCallbacks(tickLoop)
        controller.startDeterministic(w, codes)
        syncArenaToChallenge(desc)
        // Reflect the challenge on the A/B pickers: open slots show the picked
        // bot; pinned slots show HIDDEN so the opponent's identity stays hidden.
        btnA.text = if (desc.slotAOpen) "A  ${slots[codePickIdx[0]].name}  >" else "A  HIDDEN  ▒"
        btnB.text = if (desc.slotBOpen) "B  ${slots[codePickIdx[1]].name}  >" else "B  HIDDEN  ▒"
        autoRunning = true
        btnRun.isEnabled = false
        btnRun.text = "RUNNING"
        setMode(AppMode.RUN)
        hideKeyboard()
        updateStats()
        handler.postDelayed(tickLoop, 100L)
    }

    /**
     * R3 / spec §8 — SEND BACK: the player won the imported challenge, so the
     * counter-challenge builds straight from the fight that just finished:
     * same dials, the WINNING firmware pinned in slot B (source embedded when
     * it fits), slot A open, and the recorded result remapped so the pinned
     * slot is the winner. Then straight to the share sheet.
     */
    private fun doCodeRespond() {
        val (ch, outcome) = lastCodeOutcome ?: return
        if (!ChallengeResponse.isPlayerWin(ch, outcome)) return
        // The player's slot is the open one; its pick is the winning firmware.
        val openSlot = if (ch.slotAOpen) 0 else 1
        val slot = slots[codePickIdx[openSlot]]
        val src = sourceFor(slot)
        val asm = assemble(src)
        if (!asm.ok) {
            status.text = "ASSEMBLER ERROR  // ${asm.errors.first().msg}"
            return
        }
        val resp = ChallengeResponse.build(ch, asm.code, src, outcome)
        val code = ChallengeFormat.encode(resp)
        showCodesMode()
        importCode(code)          // preview the counter-challenge (remapped badge)
        shareCode(code)           // straight to the share sheet (§8)
    }

    /** R3: ACTION_SEND text/plain chooser with the code string. */
    private fun shareCode(code: String?) {
        if (code.isNullOrBlank()) {
            status.text = "NO CODE TO SHARE  // IMPORT OR BUILD ONE"
            return
        }
        try {
            startActivity(Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, code)
                },
                "SHARE CHALLENGE CODE"
            ))
        } catch (e: ActivityNotFoundException) {
            status.text = "NO SHARE TARGET  // COPY IT INSTEAD"
        }
    }

    /** R3: clipboard copy of the code string. */
    private fun copyCode(code: String?) {
        if (code.isNullOrBlank()) {
            status.text = "NO CODE TO COPY  // IMPORT OR BUILD ONE"
            return
        }
        val clip = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clip.setPrimaryClip(android.content.ClipData.newPlainText("RF1 code", code))
        status.text = "COPIED  // PASTE IT ANYWHERE"
    }

    /**
     * The execution monitor: two code panes side by side, one per combatant.
     * Each pane shows the original firmware source in a scrolling window
     * around the next/running line plus a one-line register readout. The number of
     * source lines adapts to the viewport height while retaining a small gap
     * above the registers. Built in both the portrait and landscape branches
     * of buildRunScreen(); content is refreshed by updateMonitor().
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
     * Render both code panes for the current frame. Between instructions the
     * marker points at the next instruction, so the register row is its input
     * state. During a multi-tick op the marker stays on that op until its final
     * tick completes. The instruction index is mapped to a source line via the
     * assembler's line map.
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
            if (world != null) {
                // A fight is running but this slot ships hidden firmware
                // (a pinned challenge opponent) — no source to window.
                head.text = "${if (side == 0) "A" else "B"} ▸ HIDDEN"
                head.setTextColor(C_DIM)
                code.text = "  ▒▒ FIRMWARE HIDDEN ▒▒"
            } else {
                head.text = if (side == 0) "A" else "B"
                code.text = "  (idle)"
            }
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
        val insn = vm.monitorInsn
        // Map the next/running instruction to its source line via the
        // assembler's line map. A bot that runs off the end of its program
        // clamps to the last line and folds an END marker onto it.
        val atEnd = insn !in 0 until bc.lineOf.size
        val lines = bc.source.split("\n")
        val curLine = if (atEnd) lines.size - 1 else bc.lineOf[insn]
        // Fill the measured code viewport instead of always rendering the old
        // fixed six-line window. Reserve a deliberate gap above the register
        // row, then bias the current instruction toward the upper third so
        // there is still useful look-ahead.
        val viewportHeight = (code.parent as? View)?.height ?: 0
        val lineHeight = code.lineHeight.coerceAtLeast(1)
        val windowLines = if (viewportHeight > 0) {
            ((viewportHeight - dp(MON_CODE_GAP_DP)).coerceAtLeast(lineHeight) / lineHeight)
                .coerceAtLeast(MON_MIN_CODE_LINES)
        } else {
            MON_MIN_CODE_LINES
        }
        code.maxLines = windowLines
        val linesBefore = maxOf(MON_MIN_LINES_BEFORE, windowLines / 3)
        val from = (curLine - linesBefore)
            .coerceAtMost((lines.size - windowLines).coerceAtLeast(0))
            .coerceAtLeast(0)
        val to = (from + windowLines - 1).coerceAtMost(lines.size - 1)
        val out = SpannableStringBuilder()
        for (ln in from..to) {
            val isCur = ln == curLine
            val marker = if (isCur) ">" else " "
            val text = "  $marker ${lines[ln]}${if (atEnd && isCur) " …END" else ""}"
            if (out.isNotEmpty()) out.append("\n")
            val start = out.length
            out.append(text)
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

    private fun weightedButton(startMargin: Int = 0, heightDp: Int = 42) =
        LinearLayout.LayoutParams(0, dp(heightDp), 1f).apply {
            marginStart = startMargin
        }

    /** Rev D: card paragraph lines get a 6 dp top gap (was 0 for some lines). */
    private fun cardLineParams() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(6) }

    /**
     * Free-to-paid prose keeps the terminal green and highlights only the
     * Circuit brand word in amber. Dedicated paid surfaces use their flavor
     * tier accent separately (masthead, icon, Arena Pack chip).
     */
    private fun circuitText(s: String): CharSequence {
        if (!s.contains("CIRCUIT")) return s
        val out = SpannableStringBuilder(s)
        var i = s.indexOf("CIRCUIT")
        while (i >= 0) {
            out.setSpan(
                ForegroundColorSpan(C_WARN), i, i + "CIRCUIT".length,
                SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            i = s.indexOf("CIRCUIT", i + 1)
        }
        return out
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
        private const val REQ_IMPORT_ZIP = 42      // P3: pro-only ZIP import picker
        private const val PREF_UI = "opcode_arena_ui"
        private const val PREF_MIGRATION_SEEN = "migration.hint.seen"
        // The execution monitor fills its measured viewport but deliberately
        // keeps a narrow blank strip before the register row.
        private const val MON_MIN_CODE_LINES = 6
        private const val MON_MIN_LINES_BEFORE = 2
        private const val MON_CODE_GAP_DP = 6
        // R3 builder: dial options + labels (spec §6/§6.1 named modes are UI
        // presets over exactly these dials — codes store dials, never names).
        val HP_OPTS = intArrayOf(50, 100, 150, 200)
        val MODE_LABELS = arrayOf("KO", "100", "150", "200", "300")
        val SPAWN_LABELS = arrayOf("SEPARATE", "CLASH", "SCATTER")
        private val C_BG = Color.rgb(1, 7, 3)
        private val C_BLACK = Color.rgb(0, 3, 1)
        private val C_PANEL = Color.rgb(2, 13, 6)
        private val C_DARK = Color.rgb(2, 18, 8)
        private val C_GREEN_DARK = Color.rgb(20, 92, 42)
        private val C_BORDER = Color.rgb(36, 132, 61)
        private val C_DIM = Color.rgb(48, 130, 67)
        private val C_GREEN = Color.rgb(52, 236, 91)
        private val C_BRIGHT = Color.rgb(180, 255, 190)
        private val C_CODE = Color.rgb(234, 255, 147)   // phosphor-yellow: saved challenge codes
        private val C_YELLOW = Color.rgb(248, 184, 0)   // #f8b800: the player's (open-slot) bot
        private val C_WARN = Color.rgb(248, 216, 120)   // #f8d878: non-fatal warnings
        private val C_ERR = Color.rgb(255, 90, 90)      // #ff5a5a: import / parse errors
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
