package robofight.android

/**
 * FREE flavor twin of robofight.android.ProArenas (src/pro/kotlin).
 *
 * Shared startup code (MainActivity.onCreate) calls [registerAll] in both
 * flavors; this variant is a compile-time no-op, so the free APK contains no
 * Pro arena/generator code at all — the two-app gate of CHALLENGE-CODES.md
 * A1 / PLAN-PRO §6 is enforced by the flavor source sets, not by a runtime
 * if. BuildConfig.PRO_BUILD == false for this flavor.
 */
object ProArenas {
    fun registerAll() {
        // The free app ships the five free generators ONLY (Arena.all()).
        // Nothing is registered; Pro ids 0x10–0x1F stay locked previews.
    }
}
