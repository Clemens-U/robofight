package robofight.android

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import robofight.world.Presets

/**
 * Native Android front-end for RoboFight.
 *
 * Layout (per DESIGN.md §13): arena on top, control row, editor below, stats bar.
 * All UI on the main thread; the fight is advanced by a Handler that posts
 * tick batches ~10×/s so the arena animates smoothly without a render thread.
 */
class MainActivity : Activity() {

    private lateinit var arena: ArenaView
    private lateinit var btnA: Button
    private lateinit var btnB: Button
    private lateinit var btnRun: Button
    private lateinit var btnStep: Button
    private lateinit var btnReset: Button
    private lateinit var editor: EditText
    private lateinit var status: TextView

    private lateinit var controller: RunController
    private val handler = Handler(Looper.getMainLooper())

    // slots 0..4 = presets, slot 5 = MYBOT (editable)
    private val slots: List<BotSlot> by lazy {
        Presets.all().map { BotSlot(it.name, it.glyph, it.color, it.firmware) } +
            BotSlot("MYBOT", 'M', 12, "")   // firmware filled in from assets
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

        controller = RunController()
        arena.controller = controller
        controller.onFrame = { runOnUiThread { updateStats() } }

        // Load MYBOT firmware from assets (fall back to a preset).
        val mybotSrc = loadAsset("MYBOT.asm") ?: slots[4].firmware
        slots[5].firmware = mybotSrc
        editor.setText(mybotSrc)

        btnA.setOnClickListener { ai = (ai + 1) % 6; updateBtnTexts() }
        btnB.setOnClickListener { bi = (bi + 1) % 6; updateBtnTexts() }
        btnRun.setOnClickListener { doRun() }
        btnStep.setOnClickListener { doStep() }
        btnReset.setOnClickListener { doReset() }

        updateBtnTexts()
        updateStats()
    }

    override fun onDestroy() {
        handler.removeCallbacks(tickLoop)
        super.onDestroy()
    }

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
        root.addView(arena, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(300)))

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(px(6), px(6), px(6), px(6))
        }
        fun mkBtn(label: String): Button {
            val b = Button(this)
            b.text = label
            b.textSize = 12f
            b.setTextColor(Color.WHITE)
            b.setPadding(px(4), px(2), px(4), px(2))
            row.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            return b
        }
        btnA = mkBtn("A: HUNTER")
        btnB = mkBtn("B: TURTLE")
        btnRun = mkBtn("RUN")
        btnStep = mkBtn("STEP")
        btnReset = mkBtn("RESET")
        root.addView(row)

        editor = EditText(this).apply {
            hint = "edit firmware, then RUN"
            textSize = 13f
            setPadding(px(8), px(8), px(8), px(8))
            typeface = Typeface.MONOSPACE
            inputType = android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        root.addView(editor, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(220)))

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
