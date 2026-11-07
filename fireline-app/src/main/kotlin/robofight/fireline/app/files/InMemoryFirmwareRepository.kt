package robofight.fireline.app.files

import java.util.Locale

/**
 * Fake repository: the same rules as the Android SQLite store, held in a map,
 * so the FILES surface and every naming/validation rule are testable without a
 * device. [clock] is injectable so created/updated behaviour is deterministic
 * in tests.
 */
class InMemoryFirmwareRepository(
    private val clock: () -> Long = System::currentTimeMillis,
) : FirmwareRepository {

    private val rows = LinkedHashMap<Long, FirmwareFile>()
    private var nextId = 1L

    private fun takenNames(exceptId: Long? = null): Set<String> =
        rows.values.filter { it.id != exceptId }.map { it.name.uppercase(Locale.US) }.toSet()

    override fun list(): List<FirmwareFile> =
        rows.values.sortedWith(compareBy({ it.name.lowercase(Locale.US) }, { it.id }))

    override fun count(): Int = rows.size

    override fun find(name: String): FirmwareFile? {
        val wanted = name.uppercase(Locale.US)
        return rows.values.firstOrNull { it.name.uppercase(Locale.US) == wanted }
    }

    override fun byId(id: Long): FirmwareFile? = rows[id]

    override fun save(name: String, source: String): SaveOutcome {
        val finalName = FirmwareFileNames.sanitize(name)
            ?: return SaveOutcome.Rejected(listOf("no usable file name in '$name'"))
        val normalized = FirmwareSource.normalize(source)
        val problems = FirmwareSource.validate(normalized)
        if (problems.isNotEmpty()) return SaveOutcome.Rejected(problems)
        val unique = FirmwareFileNames.uniqueName(finalName, takenNames())
        val now = clock()
        val file = FirmwareFile(nextId++, unique, normalized, now, now)
        rows[file.id] = file
        return SaveOutcome.Saved(file, requestedName = if (unique != finalName) finalName else null)
    }

    override fun update(id: Long, source: String): SaveOutcome {
        val existing = rows[id] ?: return SaveOutcome.Rejected(listOf("file $id does not exist"))
        val normalized = FirmwareSource.normalize(source)
        val problems = FirmwareSource.validate(normalized)
        if (problems.isNotEmpty()) return SaveOutcome.Rejected(problems)
        rows[id] = existing.copy(source = normalized, updatedMillis = clock())
        return SaveOutcome.Saved(rows.getValue(id))
    }

    override fun rename(id: Long, newName: String): SaveOutcome {
        val existing = rows[id] ?: return SaveOutcome.Rejected(listOf("file $id does not exist"))
        val finalName = FirmwareFileNames.sanitize(newName)
            ?: return SaveOutcome.Rejected(listOf("no usable file name in '$newName'"))
        val unique = FirmwareFileNames.uniqueName(finalName, takenNames(exceptId = id))
        rows[id] = existing.copy(name = unique, updatedMillis = clock())
        return SaveOutcome.Saved(rows.getValue(id), requestedName = if (unique != finalName) finalName else null)
    }

    override fun duplicate(id: Long): SaveOutcome {
        val existing = rows[id] ?: return SaveOutcome.Rejected(listOf("file $id does not exist"))
        return save(FirmwareFileNames.duplicateName(existing.name), existing.source)
    }

    override fun delete(id: Long): Boolean = rows.remove(id) != null
}
