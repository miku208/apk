package com.arisu.companion.pet

import com.arisu.companion.renderer.GlbInfo
import com.arisu.companion.renderer.GlbParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

/**
 * Validates an imported model and normalises it to a single GLB byte array.
 *
 * Accepted inputs:
 *  - `.glb` (glTF binary) — used as-is after a container check;
 *  - `.zip` containing exactly one `.gltf` plus its `.bin`/textures — packed
 *    into a GLB with [GltfAssetPacker];
 *  - `.zip` containing exactly one `.glb`;
 *  - a raw `.gltf` whose resources are all data URIs.
 *
 * Everything else is rejected with a message the UI can show. Imported files
 * are only ever parsed as data: no scripts, plugins or shaders are executed.
 *
 * Pure JVM code, unit-tested without a device.
 */
object PetModelInspector {

    /** Hard cap for one imported model; keeps memory use predictable. */
    const val MAX_MODEL_BYTES: Long = 48L * 1024 * 1024
    private const val MAX_UNCOMPRESSED_BYTES: Long = 160L * 1024 * 1024
    private const val MAX_ENTRY_BYTES: Long = 64L * 1024 * 1024
    private const val MAX_ENTRIES = 512

    sealed class Result {
        /** [bytes] is always a valid GLB ready for Filament. */
        data class Glb(val bytes: ByteArray, val info: GlbInfo) : Result()
        data class Failure(val message: String) : Result()
    }

    fun inspect(raw: ByteArray, fileName: String = "model"): Result {
        if (raw.isEmpty()) return Result.Failure("File kosong.")
        if (raw.size > MAX_MODEL_BYTES) {
            return Result.Failure(
                "File terlalu besar (${raw.size / (1024 * 1024)} MB). Batas impor " +
                    "${MAX_MODEL_BYTES / (1024 * 1024)} MB.",
            )
        }
        val label = fileName.substringAfterLast('/').ifBlank { "model" }
        return when {
            isGlb(raw) -> finishGlb(raw, label)
            isZip(raw) -> fromZip(raw, label)
            isJson(raw) -> {
                val text = String(raw, Charsets.UTF_8)
                val glb = try {
                    GltfAssetPacker.toGlb(text) { null }
                } catch (e: GltfAssetPacker.PackException) {
                    return Result.Failure(e.message ?: "File .gltf tidak valid.")
                }
                finishGlb(glb, label)
            }
            else -> Result.Failure(
                "\"$label\" bukan GLB/glTF yang dikenali. Pilih file .glb, atau .zip berisi .gltf + asetnya.",
            )
        }
    }

    private fun fromZip(raw: ByteArray, label: String): Result {
        val files = try {
            unzip(raw)
        } catch (e: GltfAssetPacker.PackException) {
            return Result.Failure(e.message ?: "ZIP tidak bisa dibaca.")
        }
        val glbs = files.keys.filter { it.lowercase().endsWith(".glb") }
        val gltfs = files.keys.filter { it.lowercase().endsWith(".gltf") }
        if (glbs.size == 1 && gltfs.isEmpty()) {
            return finishGlb(files.getValue(glbs.first()), glbs.first())
        }
        if (gltfs.isEmpty()) {
            return Result.Failure("ZIP tidak berisi file .gltf atau .glb.")
        }
        if (gltfs.size > 1) {
            return Result.Failure("ZIP berisi ${gltfs.size} file .gltf; paket hanya satu model per ZIP.")
        }
        val path = gltfs.first()
        val directory = path.substringBeforeLast('/', "")
        val byLower = files.entries.associate { it.key.lowercase() to it.value }
        val resolve: (String) -> ByteArray? = { uri ->
            val decoded = GltfAssetPacker.decodeUri(uri).trimStart('/')
            val joined = if (directory.isBlank()) decoded else "$directory/$decoded"
            files[joined]
                ?: byLower[normalise(joined).lowercase()]
                ?: byLower[GltfAssetPacker.baseName(uri).lowercase()]
        }
        val glb = try {
            GltfAssetPacker.toGlb(String(files.getValue(path), Charsets.UTF_8), resolve)
        } catch (e: GltfAssetPacker.PackException) {
            return Result.Failure(e.message ?: "Paket glTF tidak valid.")
        } catch (e: Exception) {
            return Result.Failure("Paket glTF gagal diproses: ${e.message ?: e.javaClass.simpleName}")
        }
        return finishGlb(glb, label)
    }

    private fun finishGlb(bytes: ByteArray, label: String): Result {
        val info = try {
            GlbParser.parse(bytes)
        } catch (e: IllegalArgumentException) {
            return Result.Failure("\"$label\" bukan GLB yang valid: ${e.message}")
        } catch (e: Exception) {
            return Result.Failure("\"$label\" gagal dibaca: ${e.message ?: e.javaClass.simpleName}")
        }
        if (info.version != 2) {
            return Result.Failure("Versi glTF ${info.version} tidak didukung; gunakan glTF 2.0.")
        }
        if (info.meshCount == 0) {
            return Result.Failure("Model tidak berisi mesh apa pun.")
        }
        return Result.Glb(bytes, info)
    }

    private fun unzip(raw: ByteArray): Map<String, ByteArray> {
        val files = LinkedHashMap<String, ByteArray>()
        var total = 0L
        ZipInputStream(ByteArrayInputStream(raw)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val name = normalise(entry.name)
                if (name.isBlank()) continue
                if (name.split('/').any { it == ".." }) {
                    throw GltfAssetPacker.PackException("ZIP berisi path yang tidak aman ($name).")
                }
                if (files.size >= MAX_ENTRIES) {
                    throw GltfAssetPacker.PackException("ZIP berisi terlalu banyak file (maks $MAX_ENTRIES).")
                }
                if (entry.size > MAX_ENTRY_BYTES) {
                    throw GltfAssetPacker.PackException("File \"$name\" di dalam ZIP terlalu besar.")
                }
                val bytes = readLimited(zip, MAX_ENTRY_BYTES)
                total += bytes.size
                if (total > MAX_UNCOMPRESSED_BYTES) {
                    throw GltfAssetPacker.PackException("Isi ZIP melebihi batas setelah diekstrak.")
                }
                files[name] = bytes
            }
        }
        return files
    }

    private fun readLimited(stream: java.io.InputStream, limit: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(16 * 1024)
        while (true) {
            val read = stream.read(chunk)
            if (read <= 0) break
            out.write(chunk, 0, read)
            if (out.size() > limit) {
                throw GltfAssetPacker.PackException("File di dalam ZIP terlalu besar.")
            }
        }
        return out.toByteArray()
    }

    private fun normalise(name: String): String =
        name.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }.joinToString("/")

    private fun isGlb(raw: ByteArray): Boolean =
        raw.size >= 12 && raw[0] == 'g'.code.toByte() && raw[1] == 'l'.code.toByte() &&
            raw[2] == 'T'.code.toByte() && raw[3] == 'F'.code.toByte()

    private fun isZip(raw: ByteArray): Boolean =
        raw.size >= 4 && raw[0] == 'P'.code.toByte() && raw[1] == 'K'.code.toByte() &&
            (raw[2] == 3.toByte() || raw[2] == 5.toByte())

    private fun isJson(raw: ByteArray): Boolean {
        var index = 0
        // Skip a UTF-8 BOM (EF BB BF) and leading whitespace.
        if (raw.size >= 3 && raw[0] == 0xEF.toByte() && raw[1] == 0xBB.toByte() && raw[2] == 0xBF.toByte()) {
            index = 3
        }
        while (index < raw.size && (raw[index] == ' '.code.toByte() || raw[index] == '\n'.code.toByte() ||
                raw[index] == '\r'.code.toByte() || raw[index] == '\t'.code.toByte())
        ) {
            index++
        }
        return index < raw.size && raw[index] == '{'.code.toByte()
    }
}
