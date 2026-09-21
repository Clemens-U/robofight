package robofight.android

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import robofight.world.Presets

/**
 * Native Android front-end for RoboFight.
 *
 * Layout (per DESIGN.md §13): arena on top, control row, then a content area
 * that is either the FIRMWARE EDITOR or the FILE SHELL (top-line pseudo
 * buttons to switch), stats bar at the bottom.
 *
 * Shell commands: DIR, EDIT <name>, DEL <name>, NEW <name>, HELP — plus
 * tapping a file name in the DIR listing opens it in the editor.
 * Editor buttons: SAVE, NEW, SHELL.
 *
 * Files persist in SQLite (see BotFiles). All UI on the main thread; the
 * fight is advanced by a Handler that posts tick batches ~10×/s.
 */
class MainActivity : Activity() {

    private lateinit var arena: ArenaView
    private lateinit var btnA: Button
    private lateinit var btnB: Button
    private lateinit var btnRun: Button
    private lateinit var btnStep: Button
    private lateinit var btnReset: Button

    // editor panel
    private lateinit var editorPanel: LinearLayout
    private lateinit var editor: EditText
    private lateinit var btnSave: Button
    private lateinit var btnNew: Button
    private lateinit var btnShellFromEditor: Button

    // shell panel
    private lateinit var shellPanel: LinearLayout
    private lateinit var btnDir: Button
    private lateinit var btnHelp: Button
    private lateinit var btnGo: Button
    private lateinit var out: LinearLayout          // vertical: text rows + clickable file rows
    private lateinit var scroll: ScrollView
    private lateinit var cmd: EditText
    private var outText: TextView? = null           // current text-row buffer

    private lateinit var status: TextView

    private lateinit var controller: RunController
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var store: BotFiles

    /** Name of the file currently open in the editor (null = untitled). */
    private var currentFile: String? = null
    private var editorDirty = false

    // slots 0..4 = presets, slot 5 = MYBOT (uses whatever the editor holds)
    private val slots: List<BotSlot> by lazy {
        Presets.all().map { BotSlot(it.name, it.glyph, it.color, it.firmware) } +
            BotSlot("MYBOT", 'M', 12, "")
    }
    private var ai = 0
    private var bi = 1

    private val tickLoop = object : Runnable {
        override fun run() {
            val w = controller.world ?: return
            if (!w.finished) {
                controller.step(2)
                updateStats()
            }
            if (!w.finished) handler.postDelayed(this, 100L)
            else {
                status.text = controller.status
                btnRun.isEnabled = true
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildLayout())

        // Seed the DB + editor BEFORE the TextWatcher is attached, so the
        // programmatic setText does not mark the editor dirty.
        store = BotFiles(this)
        seedFromAssets()

        controller = RunController()
        arena.controller = controller
        controller.onFrame = { runOnUiThread { updateStats() } }

        editor.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { editorDirty = true }
        })

        btnA.setOnClickListener { ai = (ai + 1) % 6; updateBtnTexts() }
        btnB.setOnClickListener { bi = (bi + 1) % 6; updateBtnTexts() }
        btnRun.setOnClickListener { doRun() }
        btnStep.setOnClickListener { doStep() }
        btnReset.setOnClickListener { doReset() }

        btnSave.setOnClickListener { saveFile() }
        btnNew.setOnClickListener { doNew(autoName()) }
        btnShellFromEditor.setOnClickListener { showShell() }

        // shell quick-buttons: DIR + HELP need no argument; GO executes whatever
        // is typed in the command box ("EDIT BOT1", "DEL BOT1", "NEW BOT1", …)
        btnDir.setOnClickListener { runCmd("DIR") }
        btnHelp.setOnClickListener { runCmd("HELP") }
        btnGo.setOnClickListener {
            val line = cmd.text.toString().trim()
            cmd.text.clear()
            if (line.isNotEmpty()) runCmd(line)
        }
        // Enter / IME "Go" action executes the command — no need to tap GO
        // (which can be hidden under the soft keyboard).
        cmd.setOnEditorActionListener { _, _, _ ->
            val line = cmd.text.toString().trim()
            cmd.text.clear()
            if (line.isNotEmpty()) runCmd(line)
            true
        }

        updateBtnTexts()
        // start in the shell, with a DIR already on screen
        showShell()
        runCmd("DIR")
        updateStats()
    }

    override fun onDestroy() {
        handler.removeCallbacks(tickLoop)
        super.onDestroy()
    }

    // ================= file shell =================

    private fun autoName(): String {
        var n = 1
        while (store.exists("BOT${n}")) n++
        return "BOT$n"
    }

    private fun runCmd(line: String) {
        showShell()
        printLine("robofight> $line")
        val parts = line.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (parts.isNotEmpty()) dispatch(parts[0].uppercase(), if (parts.size > 1) parts[1] else "")
        commitText()
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun dispatch(cmdWord: String, arg: String) {
        when (cmdWord) {
            "DIR", "D", "LS" -> printDir()
            "EDIT", "E", "OPEN" -> {
                if (arg.isEmpty()) { printLine("usage: EDIT <name>   (or tap a name in DIR)"); return }
                openFile(arg)
            }
            "DEL", "DELETE", "RM" -> {
                if (arg.isEmpty()) { printLine("usage: DEL <name>"); return }
                if (store.exists(arg)) {
                    if (currentFile != null && currentFile.equals(arg, true)) {
                        currentFile = null
                        editor.text.clear()
                        editorDirty = false
                    }
                    if (store.delete(arg)) printLine("deleted $arg")
                    else printLine("delete failed: $arg")
                } else {
                    printLine("no such file: $arg")
                }
            }
            "NEW", "N" -> {
                if (arg.isEmpty()) { printLine("usage: NEW <name>"); return }
                doNew(arg)
            }
            "HELP", "?" -> printHelp()
            else -> printLine("unknown command: $cmdWord   (try HELP)")
        }
    }

    private fun openFile(name: String) {
        if (!store.exists(name)) { printLine("no such file: $name"); return }
        val text = store.read(name) ?: ""
        val open = currentFile
        if (editorDirty && open != null &&
            !open.equals(name, true) && !editor.text.toString().isEmpty()) {
            // don't silently lose unsaved work: save it first
            store.upsert(open, editor.text.toString())
            printLine("saved $open (unsaved changes kept)")
        }
        editor.setText(text)
        editorDirty = false
        currentFile = name
        status.text = "editing $name"
        showEditor()
    }

    private fun doNew(name: String) {
        if (store.exists(name)) {
            printLine("$name already exists — opening it (use DEL first to replace)")
            openFile(name)
            return
        }
        val open = currentFile
        if (editorDirty && open != null && !editor.text.toString().isEmpty()) {
            store.upsert(open, editor.text.toString())
            printLine("saved $open (unsaved changes kept)")
        }
        store.upsert(name, "")
        editor.text.clear()
        currentFile = name
        editorDirty = false
        printLine("created $name")
        status.text = "new file $name"
        showEditor()
    }

    private fun saveFile() {
        val name = currentFile
        if (name == null) {
            status.text = "no file open — press NEW first"
            showShell()
            printLine("no file open (use NEW <name>)")
            commitText()
            return
        }
        store.upsert(name, editor.text.toString())
        editorDirty = false
        status.text = "saved $name"
        showShell()
        printLine("saved $name   (${store.size(name)} bytes)")
        commitText()
    }

    // ---- terminal output (LinearLayout: text rows + clickable file rows) ----

    private fun newOutText(): TextView = TextView(this).apply {
        setTextColor(Color.parseColor("#3dff62"))
        textSize = 12f
        typeface = Typeface.MONOSPACE
        setPadding(0, 0, 0, 0)
    }

    private fun commitText() {
        val t = outText ?: return
        if (t.text.isEmpty()) {
            outText = null
            return
        }
        out.addView(t)
        outText = null
        scroll.requestLayout()
    }

    private fun printLine(s: String) {
        val t = outText ?: newOutText().also { outText = it }
        if (t.text.isNotEmpty()) t.append("\n")
        t.append(s)
    }

    private fun printDir() {
        commitText()
        val files = store.list()
        if (files.isEmpty()) {
            printLine("  (no files — NEW <name> to create one)")
            return
        }
        printLine("  NAME                BYTES   MODIFIED")
        for (f in files) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, px2(2), 0, px2(2))
                gravity = Gravity.CENTER_VERTICAL
            }
            val nameView = TextView(this).apply {
                val base = "  " + f.name
                val sp = android.text.SpannableStringBuilder(base)
                sp.setSpan(android.text.style.UnderlineSpan(), 2, base.length,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                text = sp
                textSize = 12f
                typeface = Typeface.MONOSPACE
                setTextColor(Color.parseColor("#b8ffb8"))
                setSingleLine(true)
                ellipsize = TextUtils.TruncateAt.END
                setOnClickListener {
                    printLine("opening ${f.name} ...")
                    openFile(f.name)
                }
            }
            val bytesView = TextView(this).apply {
                text = f.bytes.toString().padStart(6)
                textSize = 12f
                typeface = Typeface.MONOSPACE
                setTextColor(Color.parseColor("#3dff62"))
            }
            val modView = TextView(this).apply {
                text = " " + store.fmtTime(f.modified)
                textSize = 12f
                typeface = Typeface.MONOSPACE
                setTextColor(Color.parseColor("#1e7a38"))
            }
            row.addView(nameView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(bytesView)
            row.addView(modView)
            out.addView(row)
        }
        printLine("  (tap a name to open it)")
    }

    private fun px2(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun printHelp() {
        printLine("DIR                list files")
        printLine("EDIT <name>        open file in the editor")
        printLine("DEL <name>         delete a file")
        printLine("NEW <name>         create + open a new file")
        printLine("HELP               this text")
        printLine("buttons:          DIR  HELP  GO (runs the typed command)")
        printLine("editor:           SAVE  NEW  SHELL   (tap a DIR name to open)")
    }

    // ---- panel switching ----

    private fun showShell() {
        commitText()
        shellPanel.visibility = View.VISIBLE
        editorPanel.visibility = View.GONE
        cmd.clearFocus()
    }

    private fun showEditor() {
        commitText()
        shellPanel.visibility = View.GONE
        editorPanel.visibility = View.VISIBLE
        editor.requestFocus()
    }

    /** First launch: copy MYBOT.asm (or a preset) into the DB so the shell
     *  always has at least one file. */
    private fun seedFromAssets() {
        if (store.count() > 0) {
            val mybot = store.read("MYBOT")
            if (mybot != null && mybot.isNotEmpty()) {
                editor.setText(mybot)
                editorDirty = false
                currentFile = "MYBOT"
            }
            return
        }
        val src = loadAsset("MYBOT.asm") ?: slots[4].firmware
        store.upsert("MYBOT", src)
        editor.setText(src)
        editorDirty = false
        currentFile = "MYBOT"
    }

    // ================= fight control (unchanged) =================

    private fun updateBtnTexts() {
        btnA.text = "A: ${slots[ai].name}"
        btnB.text = "B: ${slots[bi].name}"
    }

    private fun doRun() {
        handler.removeCallbacks(tickLoop)
        val mybotSrc = editor.text.toString()
        slots[5].firmware = mybotSrc
        val ok = controller.start(slots[ai], slots[bi], mybotSrc)
        if (!ok) {
            status.text = controller.status
            return
        }
        btnRun.isEnabled = false
        updateStats()
        handler.postDelayed(tickLoop, 100L)
    }

    private fun doStep() {
        if (controller.world == null) doRun()
        val w = controller.world ?: return
        if (w.finished) return
        controller.step(1)
        status.text = if (w.finished) controller.status else "step ${w.tick}"
        updateStats()
    }

    private fun doReset() {
        handler.removeCallbacks(tickLoop)
        controller.reset()
        btnRun.isEnabled = true
        updateStats()
    }

    private fun updateStats() {
        val w = controller.world
        status.text = if (w == null) {
            "T:0   no fight yet — press RUN"
        } else {
            val a = w.bots[0]; val b = w.bots[1]
            "T:${w.tick}  ${a.name}: HP${a.hp} SH${a.shots} HIT${a.hits} D${a.dmgDealt}" +
                "   ${b.name}: HP${b.hp} SH${b.shots} HIT${b.hits} D${b.dmgDealt}"
        }
        arena.invalidate()   // repaint the arena whenever the world state changes
    }

    private fun loadAsset(name: String): String? = try {
        assets.open(name).bufferedReader().use { it.readText() }
    } catch (e: Exception) {
        null
    }

    // ---- layout (built in code; the build only needs strings/colors) ----
    private fun buildLayout(): ViewGroup {
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        root.addView(TextView(this).apply {
            text = "ROBOFIGHT"
            setTextColor(Color.parseColor("#FCFCFC"))
            textSize = 20f
            setPadding(px(12), px(6), px(12), px(4))
            typeface = Typeface.MONOSPACE
        })

        arena = ArenaView(this)
        // Arena is a *weighted* element (not a fixed px height) so it yields
        // space to the content panel below when the soft keyboard resizes the
        // window — otherwise the terminal + command box get crushed to ~2px and
        // become untappable (the GO button ended up hidden under the arena).
        root.addView(arena, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(px(6), px(6), px(6), px(6))
        }
        fun mkBtn(label: String, parent: LinearLayout): Button {
            val b = Button(this)
            b.text = label
            b.textSize = 12f
            b.setTextColor(Color.WHITE)
            b.setPadding(px(4), px(2), px(4), px(2))
            parent.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            return b
        }
        btnA = mkBtn("A: HUNTER", row)
        btnB = mkBtn("B: TURTLE", row)
        btnRun = mkBtn("RUN", row)
        btnStep = mkBtn("STEP", row)
        btnReset = mkBtn("RESET", row)
        root.addView(row)

        // ---- content area: editor panel XOR shell panel ----
        val content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // -- editor panel --
        editorPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val editorTop = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(px(6), px(0), px(6), px(4))
        }
        fun editorBtn(label: String): Button = mkBtn(label, editorTop).apply {
            isAllCaps = false
        }
        btnSave = editorBtn("SAVE")
        btnNew = editorBtn("NEW")
        btnShellFromEditor = editorBtn("SHELL")
        editorPanel.addView(editorTop)

        editor = EditText(this).apply {
            background = null
            hint = "edit firmware, then RUN"
            setHintTextColor(Color.parseColor("#1e6e33"))
            setTextColor(Color.parseColor("#3dff62"))
            textSize = 13f
            minLines = 3
            gravity = Gravity.TOP or Gravity.START
            typeface = Typeface.MONOSPACE
            // TYPE_CLASS_TEXT is REQUIRED: with only TYPE_TEXT_FLAG_MULTI_LINE set,
            // TextView.isSingleLine() returns true and the text renders as one
            // horizontally-scrolling line (newlines invisible).
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        val editorFrame = FrameLayout(this)
        editorFrame.background = GradientDrawable().apply {
            setColor(Color.parseColor("#020803"))
            setStroke((2 * dp).toInt(), Color.parseColor("#2e7d3a"))
            cornerRadius = 6f * dp
        }
        editorFrame.setPadding(px(6), px(6), px(6), px(6))
        editorFrame.addView(editor, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        editorFrame.addView(Scanlines(this), FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        editorPanel.addView(editorFrame, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        content.addView(editorPanel, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // -- shell panel --
        shellPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#020803"))
                setStroke((2 * dp).toInt(), Color.parseColor("#2e7d3a"))
                cornerRadius = 6f * dp
            }
            setPadding(px(6), px(4), px(6), px(6))
        }
        val shellTop = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(px(0), px(0), px(0), px(4))
        }
        fun shellBtn(label: String): Button = mkBtn(label, shellTop).apply {
            isAllCaps = true
        }
        btnDir = shellBtn("DIR")
        btnHelp = shellBtn("HELP")
        btnGo = shellBtn("GO")
        shellPanel.addView(shellTop)

        // output: a vertical LinearLayout so it can hold plain text rows AND
        // clickable file rows (a TextView can't host child views).
        out = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            setPadding(0, 0, px(4), px(4))
        }
        scroll = ScrollView(this)
        scroll.isFillViewport = true
        scroll.addView(out, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        shellPanel.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        cmd = EditText(this).apply {
            hint = "type: EDIT name · DEL name · NEW name → GO"
            setHintTextColor(Color.parseColor("#1e6e33"))
            setTextColor(Color.parseColor("#b8ffb8"))
            textSize = 13f
            typeface = Typeface.MONOSPACE
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_CAP_WORDS
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_GO
            setSingleLine(true)
        }
        shellPanel.addView(cmd)

        content.addView(shellPanel, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        status = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#99FF99"))
            setPadding(px(12), px(8), px(12), px(8))
            typeface = Typeface.MONOSPACE
        }
        root.addView(status)

        return root
    }
}

/** Faint horizontal CRT scanlines drawn over the editor (non-interactive). */
private class Scanlines(context: Context) : View(context) {
    private val paint = Paint().apply { color = Color.argb(28, 0, 0, 0) }
    init { isFocusable = false; isFocusableInTouchMode = false }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        var y = 0f
        val w = width.toFloat()
        while (y < height) {
            canvas.drawLine(0f, y, w, y, paint)
            y += 4f
        }
    }
}
