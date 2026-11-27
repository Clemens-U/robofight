package robofight.fireline.app.files

/**
 * The one runnable file placed in a brand-new FIRELINE library.
 *
 * It deliberately teaches only the smallest useful RF-8 pattern: read a
 * sensor, branch, act, loop.  That is enough to complete NOZZLE CHECK and get
 * a new player to the run/debug loop; navigation, water, wind and rescue stay
 * for the later missions to teach through play.
 */
object StarterFirmware {
    const val NAME = "NOZZLE-CHECK.ASM"

    val SOURCE: String = """
        ; START HERE: mission 01 // NOZZLE CHECK
        ; The fire is straight ahead. Spray until its level reaches zero.
        ; RUN this once, then try STEP and watch FIRE_LEVEL + HEAT.

        scan:  IN     FIRE_LEVEL
               JZ     safe
               SHOOT
               JMP    scan

        safe:  IN     HEAT
               WAIT
               JMP    safe
    """.trimIndent() + "\n"

    /** Seed only an actually empty library; existing user files always win. */
    fun seedIfEmpty(store: FirmwareRepository): FirmwareFile? {
        if (store.count() != 0) return null
        return (store.save(NAME, SOURCE) as? SaveOutcome.Saved)?.file
    }
}
