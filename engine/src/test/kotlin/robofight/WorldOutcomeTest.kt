package robofight

import org.junit.jupiter.api.Test
import robofight.world.Bot
import robofight.world.Simulator

class WorldOutcomeTest {
    private fun bot(name: String, hp: Int = 100) =
        Bot(name, name.first(), 1, 0, 0, 0, "WAIT", hp)

    @Test
    fun maxTickHpTieIsDrawRegardlessOfBotOrder() {
        val aFirst = Simulator.runFight(
            listOf(bot("A"), bot("B")),
            maxTicks = 1,
        )
        val bFirst = Simulator.runFight(
            listOf(bot("B"), bot("A")),
            maxTicks = 1,
        )

        check(aFirst.finished && aFirst.winner == null)
        check(bFirst.finished && bFirst.winner == null)
    }

    @Test
    fun maxTickUniqueHpLeaderWins() {
        val low = bot("LOW", hp = 50)
        val high = bot("HIGH", hp = 100)
        val world = Simulator.runFight(listOf(low, high), maxTicks = 1)

        check(world.winner === high)
    }
}
