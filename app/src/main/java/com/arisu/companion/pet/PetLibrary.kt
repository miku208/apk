package com.arisu.companion.pet

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.util.UUID

/**
 * Persistent 3D character library.
 *
 * Storage layout:
 * ```
 * filesDir/pets/library.json          // profiles + active selection
 * filesDir/pets/<pet-id>/model.glb    // imported, app-private copy
 * ```
 * Imported models are validated by [PetModelInspector] and copied into private
 * storage, so the pet survives the SAF document being deleted. The bundled
 * Arisu character always exists and cannot be deleted.
 */
class PetLibrary(private val context: Context) {

    @Serializable
    data class Snapshot(
        val profiles: List<PetProfile> = emptyList(),
        val activeId: String = PetProfile.BUNDLED_ID,
    )

    sealed class ImportResult {
        data class Success(val profile: PetProfile) : ImportResult()
        data class Failure(val message: String) : ImportResult()
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Any()

    private val rootDir: File get() = File(context.filesDir, DIR).apply { mkdirs() }
    private val manifestFile: File get() = File(rootDir, MANIFEST)

    private val _profiles = MutableStateFlow<List<PetProfile>>(emptyList())
    private val _activeId = MutableStateFlow(PetProfile.BUNDLED_ID)
    private val _active = MutableStateFlow(PetProfile.bundled())

    val profiles: StateFlow<List<PetProfile>> = _profiles
    val activeId: StateFlow<String> = _activeId
    val active: StateFlow<PetProfile> = _active

    init {
        loadFromDisk()
    }

    // ------------------------------------------------------------------ reads

    fun profile(id: String): PetProfile? = _profiles.value.firstOrNull { it.id == id }

    fun activeProfile(): PetProfile = _active.value

    /** Raw GLB bytes of [profile]; the bundled character comes from assets. */
    fun modelBytes(profile: PetProfile): ByteArray? = try {
        if (profile.bundled) {
            context.assets.open(profile.modelFile).use { it.readBytes() }
        } else {
            val file = File(File(rootDir, profile.id), profile.modelFile)
            if (!file.isFile) null else file.readBytes()
        }
    } catch (e: Exception) {
        Log.e(TAG, "modelBytes failed for ${profile.id}", e)
        null
    }

    fun directoryOf(profile: PetProfile): File = File(rootDir, profile.id)

    // ------------------------------------------------------------- mutation

    fun setActive(id: String): Boolean {
        val exists = _profiles.value.any { it.id == id } || id == PetProfile.BUNDLED_ID
        if (!exists) return false
        synchronized(lock) {
            val snapshot = Snapshot(_profiles.value, id)
            persist(snapshot)
        }
        return true
    }

    fun rename(id: String, displayName: String): Boolean {
        val clean = displayName.trim().take(MAX_NAME)
        if (clean.isBlank()) return false
        val current = _profiles.value.firstOrNull { it.id == id } ?: return false
        synchronized(lock) {
            val updated = _profiles.value.map { if (it.id == id) current.copy(displayName = clean) else it }
            persist(Snapshot(updated, _activeId.value))
        }
        return true
    }

    /** Updates a profile (personality, animation mapping, speed). */
    fun update(profile: PetProfile): Boolean {
        if (_profiles.value.none { it.id == profile.id }) return false
        synchronized(lock) {
            val updated = _profiles.value.map { if (it.id == profile.id) profile else it }
            persist(Snapshot(updated, _activeId.value))
        }
        return true
    }

    /** Removes an imported character. The bundled character is protected. */
    fun delete(id: String): Boolean {
        val target = _profiles.value.firstOrNull { it.id == id } ?: return false
        if (target.bundled) return false
        synchronized(lock) {
            val remaining = _profiles.value.filterNot { it.id == id }
            val nextActive = if (_activeId.value == id) PetProfile.BUNDLED_ID else _activeId.value
            persist(Snapshot(remaining, nextActive))
            runCatching { directoryOf(target).deleteRecursively() }
        }
        return true
    }

    fun refresh() {
        loadFromDisk()
    }

    // --------------------------------------------------------------- import

    /** Imports a model picked through the Storage Access Framework. */
    fun importFromUri(uri: Uri): ImportResult {
        val name = queryDisplayName(uri) ?: "Model"
        val raw = try {
            readCapped(uri) ?: return ImportResult.Failure("File tidak bisa dibaca dari penyimpanan.")
        } catch (e: TooLargeException) {
            return ImportResult.Failure(
                "File terlalu besar. Batas impor ${PetModelInspector.MAX_MODEL_BYTES / (1024 * 1024)} MB.",
            )
        } catch (e: Exception) {
            return ImportResult.Failure("Gagal membaca file: ${e.message ?: e.javaClass.simpleName}")
        }
        return importBytes(raw, name)
    }

    /** Imports already-loaded bytes (also used by tests and direct shares). */
    fun importBytes(raw: ByteArray, displayName: String): ImportResult {
        when (val inspected = PetModelInspector.inspect(raw, displayName)) {
            is PetModelInspector.Result.Failure -> return ImportResult.Failure(inspected.message)
            is PetModelInspector.Result.Glb -> {
                val id = "pet-" + UUID.randomUUID().toString().replace("-", "").take(10)
                val dir = File(rootDir, id)
                if (!dir.mkdirs() && !dir.isDirectory) {
                    return ImportResult.Failure("Tidak bisa membuat folder karakter baru.")
                }
                val file = File(dir, MODEL_FILE)
                try {
                    file.writeBytes(inspected.bytes)
                } catch (e: Exception) {
                    runCatching { dir.deleteRecursively() }
                    return ImportResult.Failure("Gagal menyimpan model: ${e.message ?: e.javaClass.simpleName}")
                }
                val profile = PetProfile(
                    id = id,
                    displayName = sanitiseName(displayName),
                    modelFile = MODEL_FILE,
                    clipNames = inspected.info.animations,
                    modelBytes = inspected.bytes.size.toLong(),
                    importedAt = System.currentTimeMillis(),
                )
                synchronized(lock) {
                    persist(Snapshot(_profiles.value + profile, _activeId.value))
                }
                return ImportResult.Success(profile)
            }
        }
    }

    // ------------------------------------------------------------- internals

    private fun loadFromDisk() {
        val stored = try {
            if (manifestFile.isFile) json.decodeFromString<Snapshot>(manifestFile.readText()) else null
        } catch (e: Exception) {
            Log.w(TAG, "library.json rusak, memakai default", e)
            null
        }
        val bundled = stored?.profiles?.firstOrNull { it.bundled } ?: PetProfile.bundled()
        val imported = stored?.profiles?.filterNot { it.bundled }?.filter { profileExists(it) }.orEmpty()
        val profiles = listOf(bundled) + imported
        val activeId = stored?.activeId?.takeIf { id -> profiles.any { it.id == id } }
            ?: PetProfile.BUNDLED_ID
        publish(Snapshot(profiles, activeId))
    }

    private fun profileExists(profile: PetProfile): Boolean =
        profile.bundled || File(File(rootDir, profile.id), profile.modelFile).isFile

    private fun persist(snapshot: Snapshot) {
        publish(snapshot)
        runCatching {
            val tmp = File(rootDir, "$MANIFEST.tmp")
            tmp.writeText(json.encodeToString(snapshot))
            if (!tmp.renameTo(manifestFile)) {
                manifestFile.writeText(tmp.readText())
                tmp.delete()
            }
        }.onFailure { Log.e(TAG, "gagal menyimpan library.json", it) }
    }

    private fun publish(snapshot: Snapshot) {
        _profiles.value = snapshot.profiles
        _activeId.value = snapshot.activeId
        _active.value = snapshot.profiles.firstOrNull { it.id == snapshot.activeId }
            ?: snapshot.profiles.firstOrNull { it.bundled }
            ?: PetProfile.bundled()
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) cursor.getString(index) else null
                } else null
            }
    }.getOrNull()

    private fun readCapped(uri: Uri): ByteArray? {
        val stream: InputStream = context.contentResolver.openInputStream(uri) ?: return null
        stream.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val read = input.read(chunk)
                if (read <= 0) break
                total += read
                if (total > PetModelInspector.MAX_MODEL_BYTES) throw TooLargeException()
                out.write(chunk, 0, read)
            }
            return out.toByteArray()
        }
    }

    private class TooLargeException : Exception()

    private fun sanitiseName(raw: String): String {
        val base = raw.substringAfterLast('/').substringAfterLast('\\')
            .removeSuffix(".glb").removeSuffix(".GLB").removeSuffix(".gltf")
            .removeSuffix(".zip").trim()
        return base.take(MAX_NAME).ifBlank { "Karakter baru" }
    }

    companion object {
        private const val TAG = "PetLibrary"
        const val DIR = "pets"
        const val MODEL_FILE = "model.glb"
        const val MANIFEST = "library.json"
        /** Size cap shown in the UI (kept in sync with the inspector). */
        const val MAX_MB = 48
        private const val MAX_NAME = 40
    }
}
