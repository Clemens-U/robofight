package robofight.android

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

    private class Helper(context: Context) : SQLiteOpenHelper(context, "botfiles", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE files(" +
                    " id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    " name TEXT NOT NULL UNIQUE COLLATE NOCASE," +
                    " source TEXT NOT NULL," +
                    " created INTEGER NOT NULL," +
                    " modified INTEGER NOT NULL)"
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // v1 — nothing to migrate yet.
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

    private val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.US)
    fun fmtTime(ms: Long): String = fmt.format(Date(ms))
}
