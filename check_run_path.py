"""Headless check of the EXACT code path MainActivity.doRun() uses:
  - read MYBOT.asm (the app's asset)
  - assemble() it (what RunController.start does)
  - build a World with HUNTER vs MYBOT (same Bot() calls)
  - tick to completion and print stats
This proves the app's RUN flow won't hit an assembler error or engine crash.
"""
import subprocess, os

ROOT = os.path.dirname(os.path.abspath(__file__))
# compile a tiny driver against engine + the app's RunController/BotSlot
driver = r'''
import robofight.assembler.assemble
import robofight.world.World
import robofight.world.Bot
import robofight.world.Presets
import robofight.world.Simulator
import java.io.File

fun main() {
    val mybot = File("app/src/main/assets/MYBOT.asm").readText()
    val r = assemble(mybot)
    println("MYBOT assembles: ok=${r.ok}  insns=${r.instructionCount}  errors=${r.errors}")
    if (!r.ok) kotlin.system.exitProcess(1)

    // exactly what MainActivity/RunController does on RUN
    val h = Bot("HUNTER", 'H', Presets.all()[0].color, 10, 15, 2, Presets.HUNTER)
    val m = Bot("MYBOT",  'M', 12,                       10, 4,  3, mybot)
    val w = World(); w.add(h); w.add(m)
    while (!w.finished && w.tick < 200) w.tickOnce()
    println("RESULT: " + Simulator.stats(w))
    println("final arena:")
    println(robofight.world.TextGrid.render(w).trimEnd())
    // also a full melee to exercise all 5 presets + MYBOT
    val melee = Presets.all().map { Bot(it.name, it.glyph, it.color, it.x, it.y, 1, it.firmware) }.toMutableList()
    melee.add(Bot("MYBOT", 'M', 12, 10, 10, 1, mybot))
    val w2 = World(); melee.forEach { w2.add(it) }
    while (!w2.finished) w2.tickOnce()
    println("MELEE RESULT: " + Simulator.stats(w2))
    if (!w2.finished) { println("melee did not finish"); kotlin.system.exitProcess(1) }
    println("APP-RUN-PATH OK")
}
'''
open("build/Driver.kt", "w").write(driver)

env = dict(os.environ, JAVA_HOME=r'C:\Program Files\Android\Android Studio\jbr')
J = r'C:\Program Files\Android\Android Studio\jbr\bin\java.exe'
KJAR = r'C:\Users\Clemens\tools\kotlinc\lib\kotlin-compiler.jar'
KSTD = r'C:\Users\Clemens\tools\kotlinc\lib\kotlin-stdlib.jar'
srcs = [
    "engine/src/robofight/isa/ISA.kt",
    "engine/src/robofight/assembler/Assembler.kt",
    "engine/src/robofight/vm/Vm.kt",
    "engine/src/robofight/world/World.kt",
    "engine/src/robofight/world/TextGrid.kt",
    "engine/src/robofight/world/Presets.kt",
    "engine/src/robofight/world/Simulator.kt",
    "build/Driver.kt",
]
c = subprocess.run([J, "-cp", KJAR, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler"] + srcs + ["-d", "build/driver-cls"],
                   env=env, cwd=ROOT, capture_output=True, text=True)
if c.returncode != 0:
    print("COMPILE FAIL:\n", c.stderr); raise SystemExit(1)
r = subprocess.run([J, "-cp", "build/driver-cls;" + KSTD, "DriverKt"],
                   env=env, cwd=ROOT, capture_output=True, text=True)
print(r.stdout)
print(r.stderr, end="")
raise SystemExit(r.returncode)
