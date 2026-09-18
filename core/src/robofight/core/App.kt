package robofight.core

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.ApplicationAdapter
import com.badlogic.gdx.Input
import com.badlogic.gdx.InputProcessor
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.badlogic.gdx.math.Rectangle
import robofight.assembler.assemble
import robofight.world.*
import java.io.File

class RoboFightApp : ApplicationAdapter(), InputProcessor {
    private lateinit var batch: SpriteBatch
    private lateinit var shape: ShapeRenderer
    private lateinit var font: BitmapFont

    private val W = 480f
    private val topY = 450f
    private val arenaX = 120f
    private val arenaY = 210f
    private val cell = 12f
    private val edY = 40f
    private val edH = 170f
    private val lineH = 14f
    private val visLines = 11

    private val presets = Presets.all()
    private var ai = 0
    private var bi = 1
    private val MYBOT = 5

    private val lines = ArrayList<String>()
    private var curLine = 0
    private var curCol = 0
    private var scroll = 0
    private var errorLine = -1
    private var status = "edit firmware, then RUN"
    private var blink = 0f

    private var world: World? = null
    private var running = false
    private var tickAccum = 0f

    private val btnA = Rectangle(96f, topY, 92f, 26f)
    private val btnB = Rectangle(196f, topY, 92f, 26f)
    private val btnRun = Rectangle(300f, topY, 44f, 26f)
    private val btnStep = Rectangle(350f, topY, 48f, 26f)
    private val btnQuit = Rectangle(404f, topY, 46f, 26f)

    override fun create() {
        font = BitmapFont()
        batch = SpriteBatch()
        shape = ShapeRenderer()
        Gdx.input.setInputProcessor(this)
        val f = File("firmware/MYBOT.asm")
        val src = if (f.isFile) f.readText() else presets[4].firmware
        lines.addAll(src.lines())
        if (lines.isEmpty()) lines.add("WAIT")
    }

    private fun maxScroll() = (lines.size - visLines).coerceAtLeast(0)
    private fun fixScroll() {
        if (curLine < scroll) scroll = curLine
        if (curLine >= scroll + visLines) scroll = curLine - visLines + 1
        scroll = scroll.coerceIn(0, maxScroll())
    }
    private fun botName(i: Int) = if (i == MYBOT) "MYBOT" else presets[i].name
    private fun botSpec(i: Int): Triple<Char, Int, String> =
        if (i == MYBOT) Triple('M', 12, lines.joinToString("\n"))
        else { val p = presets[i]; Triple(p.glyph, p.color, p.firmware) }

    private fun doRun() {
        val r = assemble(lines.joinToString("\n"))
        if (!r.ok) {
            val e = r.errors.first()
            errorLine = e.line
            status = "line ${e.line}: ${e.msg}"
            curLine = (e.line - 1).coerceIn(0, lines.size - 1)
            fixScroll()
            return
        }
        errorLine = -1
        try {
            val w = World().apply { setSeed(12345) }
            val (ag, ac, afw) = botSpec(ai)
            val (bg, bc, bfw) = botSpec(bi)
            w.add(Bot(botName(ai), ag, ac, 10, 15, 2, afw))
            w.add(Bot(botName(bi), bg, bc, 10, 4, 3, bfw))
            world = w
            running = true
            tickAccum = 0f
            status = "running..."
        } catch (e: Exception) {
            status = "asm error: ${e.message?.lineSequence()?.first() ?: e}"
        }
    }

    private fun doStep() {
        if (world == null) { doRun(); if (world == null) return }
        val w = world ?: return
        if (w.finished) return
        w.tickOnce()
        if (w.finished) { running = false; status = w.winner?.let { "WINNER: ${it.name}" } ?: "DRAW" }
        else status = "step ${w.tick}"
    }

    private fun fillRect(r: Rectangle, c: Color) {
        shape.begin(ShapeRenderer.ShapeType.Filled); shape.setColor(c); shape.rect(r.x, r.y, r.width, r.height); shape.end()
    }

    // ---- input ----
    override fun touchDown(x: Int, y: Int, pointer: Int, buttons: Int): Boolean {
        val rx = x.toFloat(); val ry = y.toFloat()
        if (btnA.contains(rx, ry)) { ai = (ai + 1) % 6; status = "A = ${botName(ai)}"; return true }
        if (btnB.contains(rx, ry)) { bi = (bi + 1) % 6; status = "B = ${botName(bi)}"; return true }
        if (btnRun.contains(rx, ry)) { doRun(); return true }
        if (btnStep.contains(rx, ry)) { doStep(); return true }
        if (btnQuit.contains(rx, ry)) { Gdx.app.exit(); return true }
        if (ry >= edY && ry <= edY + edH) {
            val l = ((edY + edH - ry) / lineH).toInt() + scroll
            curLine = l.coerceIn(0, lines.size - 1); curCol = 0; fixScroll()
            return true
        }
        return false
    }
    override fun touchUp(x: Int, y: Int, pointer: Int, buttons: Int) = false
    override fun touchDragged(x: Int, y: Int, pointer: Int) = false
    override fun touchCancelled(x: Int, y: Int, pointer: Int, buttons: Int) = false
    override fun mouseMoved(x: Int, y: Int) = false
    override fun scrolled(amountX: Float, amountY: Float) = false

    override fun keyDown(keyCode: Int): Boolean {
        val line = lines[curLine]
        when (keyCode) {
            Input.Keys.BACKSPACE -> if (curCol > 0) { lines[curLine] = line.substring(0, curCol - 1); curCol-- }
            else if (curLine > 0) { lines[curLine - 1] += line; lines.removeAt(curLine); curLine--; curCol = lines[curLine].length }
            Input.Keys.LEFT -> if (curCol > 0) curCol--
            Input.Keys.RIGHT -> if (curCol < line.length) curCol++
            Input.Keys.UP -> { if (curLine > 0) { curLine--; curCol = curCol.coerceAtMost(lines[curLine].length) }; fixScroll() }
            Input.Keys.DOWN -> { if (curLine < lines.size - 1) { curLine++; curCol = curCol.coerceAtMost(lines[curLine].length) }; fixScroll() }
            Input.Keys.PAGE_UP -> scroll = (scroll - visLines).coerceIn(0, maxScroll())
            Input.Keys.PAGE_DOWN -> scroll = (scroll + visLines).coerceIn(0, maxScroll())
            Input.Keys.ENTER -> {
                if (curCol < line.length) { lines.add(curLine + 1, line.substring(curCol)); lines[curLine] = line.substring(0, curCol) }
                else lines.add(curLine + 1, "")
                curLine++; curCol = 0; fixScroll()
            }
        }
        return true
    }
    override fun keyUp(keyCode: Int) = false

    override fun keyTyped(character: Char): Boolean {
        if (character.isLetterOrDigit() || " ,();:#._-".contains(character)) {
            val line = lines[curLine]
            if (line.length < 60) { lines[curLine] = line.substring(0, curCol) + character + line.substring(curCol); curCol++ }
        }
        return true
    }

    // ---- render ----
    override fun render() {
        Gdx.gl.glClearColor(0.05f, 0.05f, 0.09f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
        blink += Gdx.graphics.getDeltaTime()

        val w = world
        if (running && w != null && !w.finished) {
            tickAccum += Gdx.graphics.getDeltaTime()
            while (tickAccum >= 0.1f) { w.tickOnce(); tickAccum -= 0.1f; if (w.finished) break }
            if (w.finished) { running = false; status = w.winner?.let { "WINNER: ${it.name}" } ?: "DRAW" }
        }

        // solid backgrounds (ShapeRenderer)
        fillRect(Rectangle(0f, topY, W, 30f), Color(0.13f, 0.13f, 0.2f, 1f))
        fillRect(Rectangle(0f, 0f, W, 40f), Color(0.13f, 0.13f, 0.2f, 1f))
        fillRect(Rectangle(0f, edY, W, edH), Color(0.09f, 0.09f, 0.14f, 1f))
        fillRect(Rectangle(arenaX - 2f, arenaY - 2f, 20 * cell + 4f, 20 * cell + 4f), Color(0.2f, 0.2f, 0.3f, 1f))
        fillRect(Rectangle(arenaX, arenaY, 20 * cell, 20 * cell), Color(0.07f, 0.07f, 0.11f, 1f))

        batch.begin()
        // arena entities
        if (w != null) {
            for (b in w.bots) if (b.alive) fillRect(Rectangle(arenaX + b.px * cell, arenaY + (GRID - 1 - b.py) * cell, cell, cell), Color.valueOf(Palette.HEX[b.color % 15]))
            for (p in w.projectiles) fillRect(Rectangle(arenaX + p.x * cell + 2f, arenaY + (GRID - 1 - p.y) * cell + 2f, 8f, 8f), Color.WHITE)
            for (b in w.bots) if (b.alive) { font.setColor(Color.WHITE); font.draw(batch, b.glyph.toString(), arenaX + b.px * cell + 2f, arenaY + (GRID - 1 - b.py) * cell + 10f) }
        }

        font.setColor(Color.WHITE); font.draw(batch, "ROBOFIGHT", 8f, topY + 10f)
        drawBtn(btnA, "A: ${botName(ai)}", Color(0.25f, 0.15f, 0.35f, 1f))
        drawBtn(btnB, "B: ${botName(bi)}", Color(0.12f, 0.25f, 0.15f, 1f))
        drawBtn(btnRun, "RUN", Color(0.3f, 0.18f, 0.18f, 1f))
        drawBtn(btnStep, "STEP", Color(0.2f, 0.2f, 0.3f, 1f))
        drawBtn(btnQuit, "QUIT", Color(0.32f, 0.1f, 0.1f, 1f))

        // editor
        val white = Color.WHITE; val yellow = Color(1f, 0.85f, 0.4f, 1f); val green = Color(0.5f, 0.9f, 0.5f, 1f); val gray = Color(0.55f, 0.55f, 0.55f, 1f)
        for (i in scroll until scroll + visLines) {
            if (i >= lines.size) break
            val y = edY + edH - (i - scroll + 1) * lineH + 11f
            val line = lines[i]
            if (i == errorLine) fillRect(Rectangle(0f, y - 12f, W, lineH), Color(0.45f, 0.08f, 0.08f, 1f))
            font.setColor(gray); font.draw(batch, "%4d".format(i + 1), 2f, y)
            var x = 58f
            val ci = line.indexOf(';')
            val body = if (ci >= 0) line.substring(0, ci) else line
            var j = 0
            while (j < body.length) {
                val ch = body[j]
                if (ch.isWhitespace()) { x += font.spaceXadvance; j++; continue }
                var k = j; while (k < body.length && !body[k].isWhitespace()) k++
                val tok = body.substring(j, k)
                font.setColor(if (tok.startsWith("#")) green else if (tok.endsWith(":")) yellow else white)
                x = font.draw(batch, tok, x, y).width + font.spaceXadvance
                j = k
            }
            if (ci >= 0) { font.setColor(gray); font.draw(batch, line.substring(ci), x, y) }
        }
        if ((blink % 0.9f) < 0.5f && curLine in scroll until scroll + visLines) {
            val y = edY + edH - (curLine - scroll + 1) * lineH + 11f
            val prefix = lines[curLine].substring(0, curCol.coerceAtMost(lines[curLine].length))
            val pw = if (prefix.isEmpty()) 0f else com.badlogic.gdx.graphics.g2d.GlyphLayout(font, prefix).width
            fillRect(Rectangle(58f + pw, y - 9f, 6f, 10f), Color.WHITE)
        }

        val w2 = world
        val s1 = if (w2 == null) "T:0   no fight yet — press RUN"
        else {
            val a = w2.bots[0]; val b = w2.bots[1]
            "T:${w2.tick}  ${a.name}: HP${a.hp} SH${a.shots} HIT${a.hits} D${a.dmgDealt}   ${b.name}: HP${b.hp} SH${b.shots} HIT${b.hits} D${b.dmgDealt}"
        }
        font.setColor(Color.WHITE); font.draw(batch, s1, 8f, 26f)
        font.setColor(if (errorLine >= 0) Color(1f, 0.5f, 0.4f, 1f) else Color(0.6f, 0.8f, 0.6f, 1f))
        font.draw(batch, status, 8f, 10f)
        batch.end()
    }

    private fun drawBtn(r: Rectangle, text: String, bg: Color) {
        fillRect(r, bg)
        font.setColor(Color.WHITE)
        font.draw(batch, text, r.x + 6f, r.y + 10f)
    }

    override fun dispose() {
        batch.dispose(); shape.dispose(); font.dispose()
    }
}
