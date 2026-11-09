package robofight.fireline.app.mission

import android.content.SharedPreferences

/**
 * Tiny key/value seam so the MISSION persistence rules are testable on a plain
 * JVM (same reasoning as the `...app.files` layer in milestone 2): the stores
 * below know only this interface, and the app adapts `SharedPreferences` to it.
 */
interface KeyValues {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun remove(key: String)
}

/** The one Android-aware adapter: FIRELINE's own `fireline_ui` preferences. */
class SharedPreferencesValues(private val prefs: SharedPreferences) : KeyValues {
    override fun get(key: String): String? = prefs.getString(key, null)
    override fun put(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }
}

/** Which stored file is the mission firmware (same key milestone 3 persists). */
class FirmwareSelection(private val values: KeyValues) {
    fun get(): String? = values.get(KEY)
    fun set(name: String) = values.put(KEY, name)
    fun clear() = values.remove(KEY)

    companion object {
        const val KEY = "firmware"
    }
}

/** Which authored mission was last opened (FIRELINE-DESIGN 12.1: persist the
 *  selected mission, not only the firmware). */
class MissionSelection(private val values: KeyValues) {
    fun get(): Int? = values.get(KEY)?.toIntOrNull()
    fun set(missionId: Int) = values.put(KEY, missionId.toString())
    fun clear() = values.remove(KEY)

    companion object {
        const val KEY = "mission"
    }
}

/**
 * Most recent finished run (12.1: "most recent result"). Plain data only; a
 * replay always reconstructs the world from the mission tuple.
 */
class RunRecordStore(private val values: KeyValues) {
    fun put(summary: RunSummary) = values.put(KEY, summary.encode())
    fun get(): RunSummary? = values.get(KEY)?.let(RunSummary::decode)
    fun clear() = values.remove(KEY)

    companion object {
        const val KEY = "last_run"
    }
}

/**
 * Best result per authored mission per engine version (12.1). The version is
 * part of the key because "best result per engine version" must not compare
 * results produced by different rule sets.
 *
 * Update rule (documented choice for milestone 4): a finished run replaces the
 * stored badge only when the engine's own §7.5 ranking says the candidate is
 * strictly better (`compareToRank < 0`); equal or worse runs keep the badge.
 * The first run of a mission+engine-version pair always writes.
 */
class BestBadgeStore(private val values: KeyValues) {

    /** @return true when the candidate became the new badge. */
    fun update(candidate: RunSummary): Boolean {
        val current = get(candidate.missionId, candidate.engineVersion)
        if (current == null) return write(candidate)
        if (candidate.toFirelineResult().compareToRank(current.toFirelineResult()) < 0) return write(candidate)
        return false
    }

    fun get(missionId: Int, engineVersion: Int): RunSummary? =
        values.get(key(missionId, engineVersion))?.let(RunSummary::decode)

    private fun write(summary: RunSummary): Boolean {
        values.put(key(summary.missionId, summary.engineVersion), summary.encode())
        return true
    }

    private fun key(missionId: Int, engineVersion: Int) = "best.$missionId.$engineVersion"
}
