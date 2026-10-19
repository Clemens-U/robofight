package robofight

import org.junit.jupiter.api.Test
import robofight.challenge.ChallengeFormat.FightDescriptor
import robofight.challenge.ChallengeRun
import robofight.world.Arena
import robofight.world.Bot
import robofight.world.Presets

class ChallengeRunTest {
    @Test
    fun openSlotsUseDescriptorHpAndKeepItOnReset() {
        val desc = FightDescriptor(
            eng = 1,
            layoutId = Arena.OPEN.id,
            slots = 0b11,
            seed = 2,
            mode = 3,
            spawn = 0,
            hpA = 50,
            hpB = 200,
            result = null,
            fwA = ByteArray(0),
            fwB = ByteArray(0),
            srcA = null,
            srcB = null,
        )
        val localA = Bot("LOCAL-A", 'A', 2, 0, 0, 0, Presets.HUNTER)
        val localB = Bot("LOCAL-B", 'B', 4, 0, 0, 0, Presets.TURTLE)

        val world = ChallengeRun.buildWorld(desc, localA, localB)

        check(world.bots[0].hp == 50 && world.bots[0].startHp == 50)
        check(world.bots[1].hp == 200 && world.bots[1].startHp == 200)

        localA.reset(localA.px, localA.py, localA.facing)
        localB.reset(localB.px, localB.py, localB.facing)
        check(localA.hp == 50 && localB.hp == 200)
    }
}
