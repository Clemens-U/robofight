package robofight.fireline.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import robofight.fireline.app.files.FirmwareFile
import robofight.fireline.app.files.FirmwareFileNames
import robofight.fireline.app.files.FirmwareRepository
import robofight.fireline.app.files.FirmwareSource
import robofight.fireline.app.files.SaveOutcome
import java.util.Locale

/**
 * FIRELINE firmware store: Android's built-in SQLite, FIRELINE-private, zero
 * extra dependencies - the same offline-toolchain reasoning that made the
 * Arena app use `botfiles` (BotFiles.kt) applies here, and the shape is
 * deliberately mirrored rather than shared: FIRELINE owns its own database
 * file and its own table, and never reads Arena's app data
 * (PLAN-RF8-MULTIAPP.md 9.4).
 *
 * Flat namespace, no folders: one table of unique case-insensitive name ->
 * RF-8 source text. All acceptance rules (name sanitization, duplicate
 * renaming, source validation) live in the pure `...app.files` layer, so this
 * class is only SQL.
 */
class FirelineFiles(
    context: Context,
    private val clock: () -> Long = System::currentTimeMillis,
) : FirmwareRepository {

    private val db = Helper(context.applicationContext).writableDatabase

    private class Helper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE files(" +
                    " id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    " name TEXT NOT NULL UNIQUE COLLATE NOCASE," +
                    " content TEXT NOT NULL," +
                    " created INTEGER NOT NULL," +
                    " updated INTEGER NOT NULL)"
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // v1 has no predecessor; future migrations append here like Arena's do.
        }
    }

    private fun cursorToFile(id: Long, name: String, content: String, created: Long, updated: Long) =
        FirmwareFile(id, name, content, created, updated)

    override fun list(): List<FirmwareFile> =
        db.query(
            "files", arrayOf("id", "name", "content", "created", "updated"),
            null, null, null, null, "name COLLATE NOCASE ASC", null,
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(cursorToFile(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3), c.getLong(4)))
                }
            }
        }

    override fun count(): Int =
        db.rawQuery("SELECT COUNT(*) FROM files", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    override fun find(name: String): FirmwareFile? =
        db.rawQuery(
            "SELECT id, name, content, created, updated FROM files WHERE name=? COLLATE NOCASE",
            arrayOf(name),
        ).use { c -> if (c.moveToFirst()) row(c) else null }

    override fun byId(id: Long): FirmwareFile? =
        db.rawQuery(
            "SELECT id, name, content, created, updated FROM files WHERE id=?",
            arrayOf(id.toString()),
        ).use { c -> if (c.moveToFirst()) row(c) else null }

    private fun row(c: android.database.Cursor) =
        cursorToFile(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3), c.getLong(4))

    override fun save(name: String, source: String): SaveOutcome {
        val finalName = FirmwareFileNames.sanitize(name)
            ?: return SaveOutcome.Rejected(listOf("no usable file name in '$name'"))
        val normalized = FirmwareSource.normalize(source)
        val problems = FirmwareSource.validate(normalized)
        if (problems.isNotEmpty()) return SaveOutcome.Rejected(problems)
        val unique = FirmwareFileNames.uniqueName(finalName, takenNames(exceptId = null))
        val now = clock()
        val values = ContentValues().apply {
            put("name", unique)
            put("content", normalized)
            put("created", now)
            put("updated", now)
        }
        val id = db.insert("files", null, values)
        if (id < 0) return SaveOutcome.Rejected(listOf("could not store '$unique'"))
        return SaveOutcome.Saved(
            FirmwareFile(id, unique, normalized, now, now),
            requestedName = if (unique != finalName) finalName else null,
        )
    }

    override fun update(id: Long, source: String): SaveOutcome {
        val existing = byId(id) ?: return SaveOutcome.Rejected(listOf("file $id does not exist"))
        val normalized = FirmwareSource.normalize(source)
        val problems = FirmwareSource.validate(normalized)
        if (problems.isNotEmpty()) return SaveOutcome.Rejected(problems)
        val now = clock()
        val values = ContentValues().apply {
            put("content", normalized)
            put("updated", now)
        }
        if (db.update("files", values, "id=?", arrayOf(id.toString())) != 1) {
            return SaveOutcome.Rejected(listOf("could not update '${existing.name}'"))
        }
        return SaveOutcome.Saved(existing.copy(source = normalized, updatedMillis = now))
    }

    override fun rename(id: Long, newName: String): SaveOutcome {
        val existing = byId(id) ?: return SaveOutcome.Rejected(listOf("file $id does not exist"))
        val finalName = FirmwareFileNames.sanitize(newName)
            ?: return SaveOutcome.Rejected(listOf("no usable file name in '$newName'"))
        val unique = FirmwareFileNames.uniqueName(finalName, takenNames(exceptId = id))
        val values = ContentValues().apply {
            put("name", unique)
            put("updated", clock())
        }
        if (db.update("files", values, "id=?", arrayOf(id.toString())) != 1) {
            return SaveOutcome.Rejected(listOf("could not rename '${existing.name}'"))
        }
        return SaveOutcome.Saved(
            existing.copy(name = unique, updatedMillis = clock()),
            requestedName = if (unique != finalName) finalName else null,
        )
    }

    override fun duplicate(id: Long): SaveOutcome {
        val existing = byId(id) ?: return SaveOutcome.Rejected(listOf("file $id does not exist"))
        return save(FirmwareFileNames.duplicateName(existing.name), existing.source)
    }

    override fun delete(id: Long): Boolean = db.delete("files", "id=?", arrayOf(id.toString())) > 0

    private fun takenNames(exceptId: Long?): Set<String> =
        db.query(
            "files", arrayOf("name"),
            if (exceptId == null) null else "id<>?",
            if (exceptId == null) null else arrayOf(exceptId.toString()),
            null, null, null,
        ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }.toSet()

    companion object {
        /** FIRELINE's own filename: never shared with Arena's `botfiles`. */
        const val DATABASE_NAME = "fireline.db"
        const val DATABASE_VERSION = 1
    }
}

/** Display helper for card timestamps (house pattern from the Arena app). */
fun formatFileTime(millis: Long): String =
    java.text.SimpleDateFormat("MM-dd HH:mm", Locale.US).format(java.util.Date(millis))
