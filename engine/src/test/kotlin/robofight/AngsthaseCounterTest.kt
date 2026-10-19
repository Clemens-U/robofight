package robofight

import java.io.File
import kotlin.math.abs
import kotlin.random.Random
import org.junit.jupiter.api.Test
import robofight.world.*

class AngsthaseCounterTest {
    private val angsthase = """
        main:
          IN DIST
          CMP #10
          JL flee
          IN ANGLE
          JZ fire
        flee:
          MOV X,A
          MOVE
          MOVE
          MOVE
          IN DIST
          CMP #15
          JG main
          CMP X
          JL flee
          TURN R
          JMP flee
        fire:
          SHOOT
          JMP main
    """.trimIndent()

    @Test
    fun harehunterAvoidsLossesAcrossStarts() {
        val counter = File("../firmware/MYBOT.asm").readText()
        val random = Random(0x48415245)
        var wins = 0
        var draws = 0
        var losses = 0

        repeat(500) { trial ->
            var ax: Int
            var ay: Int
            var bx: Int
            var by: Int
            do {
                ax = random.nextInt(GRID)
                ay = random.nextInt(GRID)
                bx = random.nextInt(GRID)
                by = random.nextInt(GRID)
            } while (ax == bx && ay == by)

            fun faceToward(x: Int, y: Int, tx: Int, ty: Int): Int {
                val dx = tx - x
                val dy = ty - y
                return if (abs(dx) >= abs(dy)) {
                    if (dx >= 0) DIR_E else DIR_W
                } else {
                    if (dy >= 0) DIR_S else DIR_N
                }
            }

            val hare = Bot("HAREHUNTER", 'H', 2, ax, ay, faceToward(ax, ay, bx, by), counter)
            val angst = Bot("ANGSTHASE", 'A', 4, bx, by, faceToward(bx, by, ax, ay), angsthase)
            val ordered = if (trial % 2 == 0) listOf(hare, angst) else listOf(angst, hare)
            val world = Simulator.runFight(ordered, maxTicks = 2_000)
            when (world.winner) {
                hare -> wins++
                angst -> {
                    losses++
                    if (losses <= 20) {
                        println("loss trial=$trial hare=($ax,$ay) angst=($bx,$by) " + Simulator.stats(world))
                    }
                }
                else -> draws++
            }
        }

        println("HAREHUNTER vs ANGSTHASE: wins=$wins draws=$draws losses=$losses")
        // HAREHUNTER predates terrain-aware line-of-sight sensors. Under the
        // current rules most unaligned starts remain equal-HP draws; the
        // regression baseline is that it never loses and still converts at
        // least 20% of this deterministic corpus into wins.
        check(losses == 0 && wins >= 100) {
            "post-LOS baseline missed: wins=$wins draws=$draws losses=$losses"
        }
    }
}
