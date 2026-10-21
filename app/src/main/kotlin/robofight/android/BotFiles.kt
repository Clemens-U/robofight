package robofight.android

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import robofight.challenge.ChallengeCodeFileName

/**
 * Persistent store for robot firmware files, backed by Android's built-in
 * SQLite (zero extra dependencies — the offline toolchain can't pull Room's
 * annotation processor, and a plain table is trivially dumpable for backups).
 *
 * Flat namespace, no folders: one table of name -> source. A `path` column
 * could be added later without breaking v1 clients.
 */
class BotFiles(context: Context) {

    data class FileRec(val id: Long, val name: String, val bytes: Long, val modified: Long)

    private val db = Helper(context.applicationContext).writableDatabase

    private class Helper(context: Context) : SQLiteOpenHelper(context, "botfiles", null, 4) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE files(" +
                    " id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    " name TEXT NOT NULL UNIQUE COLLATE NOCASE," +
                    " source TEXT NOT NULL," +
                    " created INTEGER NOT NULL," +
                    " modified INTEGER NOT NULL)"
            )
            db.execSQL(
                "CREATE TABLE codes(" +
                    " name TEXT PRIMARY KEY COLLATE NOCASE," +
                    " code TEXT NOT NULL," +
                    " created INTEGER NOT NULL," +
                    " modified INTEGER NOT NULL)"
            )
            db.execSQL(
                "CREATE TABLE history(" +
                    " code TEXT PRIMARY KEY COLLATE NOCASE," +
                    " layout TEXT NOT NULL," +
                    " played INTEGER NOT NULL DEFAULT 0," +
                    " wins INTEGER NOT NULL DEFAULT 0," +
                    " losses INTEGER NOT NULL DEFAULT 0," +
                    " draws INTEGER NOT NULL DEFAULT 0," +
                    " last INTEGER NOT NULL)"
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // v2 — add the saved-challenge-code store (R2). Firmware stays in `files`.
            if (oldVersion < 2) {
                db.execSQL(
                    "CREATE TABLE codes(" +
                        " name TEXT PRIMARY KEY COLLATE NOCASE," +
                        " code TEXT NOT NULL," +
                        " created INTEGER NOT NULL," +
                        " modified INTEGER NOT NULL)"
                )
            }
            // v3 — local fight history (R3): one row per played challenge code,
            // per-arena wins rolled up from `layout`. App feature only — the
            // format never stores outcomes (§4 display policy).
            if (oldVersion < 3) {
                db.execSQL(
                    "CREATE TABLE history(" +
                        " code TEXT PRIMARY KEY COLLATE NOCASE," +
                        " layout TEXT NOT NULL," +
                        " played INTEGER NOT NULL DEFAULT 0," +
                        " wins INTEGER NOT NULL DEFAULT 0," +
                        " losses INTEGER NOT NULL DEFAULT 0," +
                        " draws INTEGER NOT NULL DEFAULT 0," +
                        " last INTEGER NOT NULL)"
                )
            }
            // v4 — saved challenge names visibly carry their real `.chc`
            // extension. Preserve every legacy row; add _2, _3, ... if a
            // user already has an extension-bearing name that would collide.
            if (oldVersion < 4) {
                val names = db.query(
                    "codes", arrayOf("name"), null, null, null, null,
                    "name COLLATE NOCASE ASC",
                ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
                val used = names.toMutableSet()
                for (name in names) {
                    if (name.endsWith(ChallengeCodeFileName.EXT, ignoreCase = true)) continue
                    used.remove(name)
                    val migrated = ChallengeCodeFileName.migratedLegacyName(name, used)
                    val values = ContentValues().apply { put("name", migrated) }
                    db.update("codes", values, "name=?", arrayOf(name))
                    used.add(migrated)
                }
            }
        }
    }

    /** All files, sorted by name (case-insensitive). */
    fun list(): List<FileRec> =
        db.query("files", arrayOf("id", "name", "length(source)", "modified"),
                null, null, null, null, "name COLLATE NOCASE ASC", null)
            .use { c ->
                buildList {
                    while (c.moveToNext()) {
                        add(FileRec(c.getLong(0), c.getString(1), c.getLong(2), c.getLong(3)))
                    }
                }
            }

    fun read(name: String): String? =
        db.rawQuery("SELECT source FROM files WHERE name=?", arrayOf(name))
            .use { c -> if (c.moveToFirst()) c.getString(0) else null }

    fun size(name: String): Long = (read(name)?.length ?: 0).toLong()

    /** All files as stored name -> source, in name order (case-insensitive). */
    fun all(): Map<String, String> =
        db.query("files", arrayOf("name", "source"), null, null, null, null,
                "name COLLATE NOCASE ASC", null)
            .use { c -> buildMap { while (c.moveToNext()) put(c.getString(0), c.getString(1)) } }

    fun count(): Int =
        db.rawQuery("SELECT COUNT(*) FROM files", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    fun exists(name: String): Boolean = read(name) != null

    /** Insert or replace a file by name (case-insensitive); returns the row id. */
    fun upsert(name: String, source: String): Long {
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put("name", name)
            put("source", source)
            put("created", now)
            put("modified", now)
        }
        val existing = db.rawQuery("SELECT id FROM files WHERE name=?", arrayOf(name))
            .use { c -> if (c.moveToFirst()) c.getLong(0) else null }
        return if (existing == null) {
            db.insert("files", null, values)
        } else {
            db.update("files", values, "id=?", arrayOf(existing.toString()))
            existing
        }
    }

    fun delete(name: String): Boolean = db.delete("files", "name=?", arrayOf(name)) > 0

    /**
     * Rename a file (case-insensitive match) to [newName].
     * Returns true when a rename happened, false when the file or the target
     * name didn't exist / collided.
     */
    fun rename(oldName: String, newName: String): Boolean {
        if (exists(newName)) return false
        val id = db.rawQuery("SELECT id FROM files WHERE name=?", arrayOf(oldName))
            .use { c -> if (c.moveToFirst()) c.getLong(0) else null } ?: return false
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put("name", newName)
            put("modified", now)
        }
        return db.update("files", values, "id=?", arrayOf(id.toString())) > 0
    }

    // ---- Saved challenge codes (R2) ------------------------------------------------
    // Separate table from `files`: a code is a canonical base32 challenge string,
    // NOT robot firmware — so it must never be fed to refreshSlots()/the bot cycle.
    // The stored name carries the visible ".chc" extension (e.g. "MYCODE.chc").

    data class CodeRec(val name: String, val bytes: Long, val modified: Long)

    /** All saved codes, sorted by name (case-insensitive). */
    fun listCodes(): List<CodeRec> =
        db.query("codes", arrayOf("name", "length(code)", "modified"),
                null, null, null, null, "name COLLATE NOCASE ASC", null)
            .use { c ->
                buildList {
                    while (c.moveToNext()) {
                        add(CodeRec(c.getString(0), c.getLong(1), c.getLong(2)))
                    }
                }
            }

    fun readCode(name: String): String? =
        db.rawQuery("SELECT code FROM codes WHERE name=?", arrayOf(name))
            .use { c -> if (c.moveToFirst()) c.getString(0) else null }

    fun codeExists(name: String): Boolean = readCode(name) != null

    fun countCodes(): Int =
        db.rawQuery("SELECT COUNT(*) FROM codes", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    /** Insert or replace a saved code by name (case-insensitive). */
    fun upsertCode(name: String, code: String): Boolean {
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put("name", name)
            put("code", code)
            put("created", now)
            put("modified", now)
        }
        return if (codeExists(name)) {
            db.update("codes", values, "name=?", arrayOf(name)) > 0
        } else {
            db.insert("codes", null, values) >= 0
        }
    }

    fun deleteCode(name: String): Boolean = db.delete("codes", "name=?", arrayOf(name)) > 0

    // ---- Local fight history (R3) -------------------------------------------------
    // App-side only (spec §4 display policy — outside the format): one row per
    // played challenge code (keyed by its canonical string) with outcome counts,
    // plus a per-arena roll-up. Written after each finished challenge fight.

    data class HistoryRec(
        val layout: String,
        val played: Int,
        val wins: Int,
        val losses: Int,
        val draws: Int,
        val last: Long,
    )

    /**
     * Record one finished challenge fight. [win] = the player's (open-slot)
     * side won — i.e. a SEND-BACK-eligible win (§8); [draw] = neither side won.
     */
    fun recordOutcome(code: String, layout: String, win: Boolean, draw: Boolean) {
        val now = System.currentTimeMillis()
        val cur = db.rawQuery(
            "SELECT played, wins, losses, draws FROM history WHERE code=?",
            arrayOf(code),
        ).use { c -> if (c.moveToFirst()) intArrayOf(c.getInt(0), c.getInt(1), c.getInt(2), c.getInt(3)) else null }
        val values = ContentValues().apply {
            put("code", code)
            put("layout", layout)
            put("played", (cur?.get(0) ?: 0) + 1)
            put("wins", (cur?.get(1) ?: 0) + if (win) 1 else 0)
            put("losses", (cur?.get(2) ?: 0) + if (win || draw) 0 else 1)
            put("draws", (cur?.get(3) ?: 0) + if (draw) 1 else 0)
            put("last", now)
        }
        if (cur == null) db.insert("history", null, values)
        else db.update("history", values, "code=?", arrayOf(code))
    }

    /** This code's outcome row, or null when it has never been played. */
    fun codeHistory(code: String): HistoryRec? =
        db.rawQuery(
            "SELECT layout, played, wins, losses, draws, last FROM history WHERE code=?",
            arrayOf(code),
        ).use { c ->
            if (c.moveToFirst()) HistoryRec(c.getString(0), c.getInt(1), c.getInt(2), c.getInt(3), c.getInt(4), c.getLong(5))
            else null
        }

    /** Per-arena roll-up: (arena name, wins, played), arena-name order. */
    fun arenaHistory(): List<Triple<String, Int, Int>> =
        db.rawQuery(
            "SELECT layout, SUM(wins), SUM(played) FROM history GROUP BY layout ORDER BY layout",
            null,
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(Triple(c.getString(0), c.getInt(1), c.getInt(2)))
            }
        }

    private val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.US)
    fun fmtTime(ms: Long): String = fmt.format(Date(ms))
}
