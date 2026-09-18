package robofight.core

import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration

fun main(args: Array<String>) {
    val cfg = Lwjgl3ApplicationConfiguration()
    cfg.setTitle("ROBOFIGHT")
    cfg.setWindowedMode(480, 480)
    cfg.setResizable(false)
    Lwjgl3Application(RoboFightApp(), cfg)
}
