package robofight.android

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.sin
import robofight.world.HitEvent
import robofight.world.Palette
import robofight.world.World

/**
 * Canvas renderer for the 20×20 arena with 8-bit hit effects.
 *
 * Visual effects on hit:
 *  - Particle burst (pixel sparks radiating from the impact cell)
 *  - Expanding ring at the impact point
 *  - Screen shake (magnitude scales with damage)
 *  - KO: large burst + white flash + strong shake
 *
 * Block (shield): cyan sparks + cyan ring, lighter shake.
 *
 * Effects are self-animating: a lightweight handler loop updates particles /
 * shake / flash at ~60 fps and stops itself when all effects are done, so it
 * works even when the game loop has ended (KO moment).
 */
class ArenaView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val pixelTypeface: Typeface =
        Typeface.createFromAsset(context.assets, "fonts/PressStart2P-Regular.ttf")

    // --- base paints ---
    private val bgPaint = Paint().apply { color = Color.parseColor("#000502") }
    private val cellPaint = Paint().apply { color = Color.parseColor("#020c05") }
    private val gridPaint = Paint().apply { color = Color.parseColor("#0b2b14"); strokeWidth = 1f }
    private val emptyDotPaint = Paint().apply {
        color = Color.parseColor("#174525")
        textSize = 6f
        textAlign = Paint.Align.CENTER
        typeface = pixelTypeface
    }
    private val glyphPaint = Paint().apply { textAlign = Paint.Align.CENTER; typeface = pixelTypeface }
    private val projGlyphPaint = Paint().apply {
        color = Color.parseColor("#eaff93")
        textAlign = Paint.Align.CENTER
        typeface = pixelTypeface
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#34ec5b")
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    // --- FX paints ---
    private val sparkPaint = Paint().apply { style = Paint.Style.FILL }
    private val ringPaint = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 2f }
    private val flashPaint = Paint()

    private val GRID = 20
    private val DIR_GLYPH = arrayOf('<', '^', '>', 'v')
    private var cellSize: Float = 0f

    // --- FX state ---
    private data class Particle(
        var x: Float, var y: Float,
        var vx: Float, var vy: Float,
        var age: Float = 0f,
        val life: Float, val size: Float, val color: Int,
    )
    private val particles = ArrayList<Particle>()

    private data class Ring(
        val x: Float, val y: Float,
        var age: Float = 0f,
        val life: Float, val color: Int,
    )
    private val rings = ArrayList<Ring>()

    private var shakeMag: Float = 0f
    private var flashAlpha: Float = 0f
    private var flashColor: Int = Color.WHITE

    private var fxRunning = false
    private var lastFxTime: Long = 0L
    private val fxHandler = Handler(Looper.getMainLooper())
    private val fxTick = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            val dt = ((now - lastFxTime) / 1000f).coerceIn(0f, 0.1f)
            lastFxTime = now
            updateFx(dt)
            if (particles.isNotEmpty() || rings.isNotEmpty() || shakeMag > 0.1f || flashAlpha > 0.01f) {
                invalidate()
                fxHandler.postDelayed(this, 16L)
            } else {
                fxRunning = false
            }
        }
    }

    // --- FX public API ---

    /** Call on the UI thread when a hit event fires. Coordinates are grid cells (0..19). */
    fun onHitEvent(e: HitEvent) {
        if (cellSize <= 0f) return
        val cx = (e.x + 0.5f) * cellSize
        val cy = (e.y + 0.5f) * cellSize
        if (e.blocked) {
            spawnBlockFX(cx, cy)
        } else {
            spawnHitFX(cx, cy, e.damage)
            if (!e.victim.alive) spawnKoFX(cx, cy)
        }
        startFxLoop()
    }

    // --- FX spawning ---

    private fun hitSparkColor(): Int {
        val c = intArrayOf(
            Color.parseColor("#FCFCFC"), // white
            Color.parseColor("#F8B800"), // yellow
            Color.parseColor("#FC9838"), // orange
            Color.parseColor("#BC0000"), // red
        )
        return c[(Math.random() * c.size).toInt()]
    }

    private fun koColor(): Int {
        val c = intArrayOf(
            Color.parseColor("#FCFCFC"),
            Color.parseColor("#F8B800"),
            Color.parseColor("#FC9838"),
            Color.parseColor("#BC0000"),
            Color.parseColor("#F8D878"),
        )
        return c[(Math.random() * c.size).toInt()]
    }

    private fun spawnHitFX(cx: Float, cy: Float, dmg: Int) {
        val count = (6 + dmg).coerceAtMost(20)
        val scale = cellSize / 20f
        for (i in 0 until count) {
            val angle = (Math.random() * 2.0 * Math.PI).toFloat()
            val speed = (50f + Math.random().toFloat() * 120f) * scale
            particles.add(Particle(
                x = cx, y = cy,
                vx = cos(angle) * speed, vy = sin(angle) * speed,
                life = 0.15f + Math.random().toFloat() * 0.2f,
                size = (1.5f + Math.random().toFloat() * 2f) * scale,
                color = hitSparkColor(),
            ))
        }
        rings.add(Ring(cx, cy, life = 0.25f, color = Color.parseColor("#FCFCFC")))
        shakeMag = (3f + dmg * 0.3f).coerceAtMost(9f)
    }

    private fun spawnBlockFX(cx: Float, cy: Float) {
        val scale = cellSize / 20f
        for (i in 0 until 6) {
            val angle = (Math.random() * 2.0 * Math.PI).toFloat()
            val speed = (40f + Math.random().toFloat() * 90f) * scale
            particles.add(Particle(
                x = cx, y = cy,
                vx = cos(angle) * speed, vy = sin(angle) * speed,
                life = 0.12f + Math.random().toFloat() * 0.12f,
                size = (1.5f + Math.random().toFloat() * 1.5f) * scale,
                color = Color.parseColor("#00E8D8"),
            ))
        }
        rings.add(Ring(cx, cy, life = 0.2f, color = Color.parseColor("#00E8D8")))
        shakeMag = 2f
    }

    private fun spawnKoFX(cx: Float, cy: Float) {
        val scale = cellSize / 20f
        for (i in 0 until 28) {
            val angle = (Math.random() * 2.0 * Math.PI).toFloat()
            val speed = (70f + Math.random().toFloat() * 200f) * scale
            particles.add(Particle(
                x = cx, y = cy,
                vx = cos(angle) * speed, vy = sin(angle) * speed,
                life = 0.25f + Math.random().toFloat() * 0.4f,
                size = (2f + Math.random().toFloat() * 2.5f) * scale,
                color = koColor(),
            ))
        }
        rings.add(Ring(cx, cy, life = 0.4f, color = Color.parseColor("#FCFCFC")))
        shakeMag = 12f
        flashAlpha = 0.4f
        flashColor = Color.WHITE
    }

    // --- FX animation loop ---

    private fun startFxLoop() {
        if (fxRunning) return
        fxRunning = true
        lastFxTime = System.currentTimeMillis()
        fxHandler.removeCallbacks(fxTick)
        fxHandler.postDelayed(fxTick, 16L)
    }

    private fun updateFx(dt: Float) {
        // particles: move + age
        val pit = particles.iterator()
        while (pit.hasNext()) {
            val p = pit.next()
            p.age += dt
            if (p.age >= p.life) { pit.remove(); continue }
            p.x += p.vx * dt
            p.y += p.vy * dt
            p.vy += 300f * dt // slight gravity
        }
        // rings: age only
        val rit = rings.iterator()
        while (rit.hasNext()) {
            val r = rit.next()
            r.age += dt
            if (r.age >= r.life) rit.remove()
        }
        // shake decay
        if (shakeMag > 0f) {
            shakeMag *= (1f - dt * 8f).coerceAtLeast(0f)
            if (shakeMag < 0.1f) shakeMag = 0f
        }
        // flash decay
        if (flashAlpha > 0f) flashAlpha = (flashAlpha - dt * 3f).coerceAtLeast(0f)
    }

    // --- View lifecycle ---

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxWidth = MeasureSpec.getSize(widthMeasureSpec)
        val maxHeight = MeasureSpec.getSize(heightMeasureSpec)
        val size = when {
            maxWidth == 0 -> maxHeight
            maxHeight == 0 -> maxWidth
            else -> minOf(maxWidth, maxHeight)
        }
        setMeasuredDimension(size, size)
    }

    override fun onDetachedFromWindow() {
        fxHandler.removeCallbacks(fxTick)
        super.onDetachedFromWindow()
    }

    // --- Drawing ---

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        cellSize = w / GRID

        // Screen shake (applied before all content)
        if (shakeMag > 0f) {
            canvas.translate(
                (Math.random().toFloat() - 0.5f) * 2f * shakeMag,
                (Math.random().toFloat() - 0.5f) * 2f * shakeMag,
            )
        }

        // Background (slightly oversized so shake doesn't reveal edges)
        canvas.drawRect(-8f, -8f, w + 8f, h + 8f, bgPaint)

        // Cells + idle dots
        emptyDotPaint.textSize = cellSize * 0.38f
        for (py in 0 until GRID) {
            for (px in 0 until GRID) {
                val left = px * cellSize
                val top = py * cellSize
                canvas.drawRect(left, top, left + cellSize, top + cellSize, cellPaint)
                canvas.drawText("·", left + cellSize / 2f, top + cellSize * 0.64f, emptyDotPaint)
            }
        }

        // Grid lines
        for (i in 0..GRID) {
            canvas.drawLine(i * cellSize, 0f, i * cellSize, h, gridPaint)
            canvas.drawLine(0f, i * cellSize, w, i * cellSize, gridPaint)
        }

        val world = controller?.world
        if (world == null) {
            glyphPaint.color = Color.parseColor("#34ec5b")
            glyphPaint.textSize = cellSize * 0.75f
            canvas.drawText("SYSTEM IDLE", w / 2f, h / 2f, glyphPaint)
            canvas.drawRect(1.5f, 1.5f, w - 1.5f, h - 1.5f, borderPaint)
            drawFx(canvas, w, h)
            return
        }

        // Projectiles (under bots)
        for (p in world.projectiles) {
            projGlyphPaint.textSize = cellSize * 0.72f
            canvas.drawText("*", (p.x + 0.5f) * cellSize, (p.y + 0.72f) * cellSize, projGlyphPaint)
        }

        // Bots
        for (b in world.bots) {
            if (!b.alive) continue
            glyphPaint.color = Color.parseColor(Palette.HEX[b.color % 15])
            glyphPaint.textSize = cellSize * 0.52f
            canvas.drawText(
                "${b.glyph}${DIR_GLYPH[b.facing % 4]}",
                (b.px + 0.5f) * cellSize,
                (b.py + 0.69f) * cellSize,
                glyphPaint,
            )
        }

        // Border
        canvas.drawRect(1.5f, 1.5f, w - 1.5f, h - 1.5f, borderPaint)

        // FX layer (particles, rings, flash) on top of everything
        drawFx(canvas, w, h)
    }

    private fun drawFx(canvas: Canvas, w: Float, h: Float) {
        // Expanding rings
        for (r in rings) {
            val t = (r.age / r.life).coerceIn(0f, 1f)
            val radius = cellSize * 0.4f + t * cellSize * 1.2f
            ringPaint.color = r.color
            ringPaint.alpha = ((1f - t) * 180).toInt().coerceIn(0, 255)
            canvas.drawCircle(r.x, r.y, radius, ringPaint)
        }
        // Pixel sparks
        for (p in particles) {
            val t = (p.age / p.life).coerceIn(0f, 1f)
            sparkPaint.color = p.color
            sparkPaint.alpha = ((1f - t) * 255).toInt().coerceIn(0, 255)
            val s = p.size * (1f - t * 0.5f)
            canvas.drawRect(p.x - s, p.y - s, p.x + s, p.y + s, sparkPaint)
        }
        // KO white flash overlay
        if (flashAlpha > 0f) {
            flashPaint.color = flashColor
            flashPaint.alpha = (flashAlpha * 255).toInt().coerceIn(0, 255)
            canvas.drawRect(-8f, -8f, w + 8f, h + 8f, flashPaint)
        }
    }

    // --- Wiring ---
    var controller: RunController? = null
        set(value) {
            field = value
            invalidate()
        }
}
