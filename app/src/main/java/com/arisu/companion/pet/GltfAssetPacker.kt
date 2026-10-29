package com.arisu.companion.pet

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

/**
 * Converts a `.gltf` + side-car assets (`.bin`, textures) into one self-contained
 * `.glb`, because Filament's `AssetLoader` only accepts a GLB buffer.
 *
 * All buffers are concatenated into the single BIN chunk and every
 * `bufferView.byteOffset` is rebased; images become base64 data URIs. Only JSON
 * structure is read — nothing from the imported file is ever executed.
 *
 * Pure JVM code: unit-tested without an emulator.
 */
object GltfAssetPacker {

    class PackException(message: String) : Exception(message)

    private const val GLB_MAGIC = 0x46546C67
    private const val CHUNK_JSON = 0x4E4F534A
    private const val CHUNK_BIN = 0x004E4942

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * @param resolve maps a (possibly URL-encoded, possibly nested) relative URI
     *                to its bytes, or null when the asset is missing.
     */
    fun toGlb(gltfJson: String, resolve: (String) -> ByteArray?): ByteArray {
        val root = try {
            json.parseToJsonElement(gltfJson).jsonObject
        } catch (e: Exception) {
            throw PackException("File .gltf tidak bisa dibaca: JSON rusak")
        }
        val version = root["asset"]?.jsonObject?.get("version")?.jsonPrimitive?.contentOrNull.orEmpty()
        if (!version.startsWith("2")) {
            throw PackException("Hanya glTF 2.0 yang didukung (file ini: ${version.ifBlank { "tidak diketahui" }})")
        }
        val meshes = (root["meshes"] as? JsonArray)?.size ?: 0
        if (meshes == 0) throw PackException("File glTF tidak berisi mesh")

        val buffers = root["buffers"] as? JsonArray ?: JsonArray(emptyList())
        val bases = IntArray(buffers.size)
        val bin = ByteArrayOutputStream()
        var written = 0
        buffers.forEachIndexed { index, element ->
            val obj = element.jsonObject
            val uri = obj["uri"]?.jsonPrimitive?.contentOrNull
            if (uri.isNullOrBlank()) {
                throw PackException("Buffer tertanam tanpa data tidak didukung; ekspor ulang sebagai .glb")
            }
            val data = if (uri.startsWith("data:")) {
                decodeDataUri(uri) ?: throw PackException("Buffer data-URI rusak")
            } else {
                resolve(uri) ?: throw PackException("Buffer \"$uri\" tidak ada di dalam ZIP")
            }
            bases[index] = written
            bin.write(data)
            written += data.size
            while (written % 4 != 0) {
                bin.write(0)
                written++
            }
        }
        val binBytes = bin.toByteArray()

        val patchedViews = (root["bufferViews"] as? JsonArray)?.map { element ->
            val map = element.jsonObject.toMutableMap()
            val bufferIndex = map["buffer"]?.jsonPrimitive?.intOrNull ?: 0
            val offset = map["byteOffset"]?.jsonPrimitive?.intOrNull ?: 0
            map["buffer"] = JsonPrimitive(0)
            map["byteOffset"] = JsonPrimitive(bases.getOrElse(bufferIndex) { 0 } + offset)
            JsonObject(map)
        }

        val patchedImages = (root["images"] as? JsonArray)?.map { element ->
            val map = element.jsonObject.toMutableMap()
            val uri = map["uri"]?.jsonPrimitive?.contentOrNull
            if (!uri.isNullOrBlank() && !uri.startsWith("data:")) {
                val data = resolve(uri)
                    ?: throw PackException("Gambar \"$uri\" tidak ada di dalam ZIP")
                map["uri"] = JsonPrimitive(
                    "data:" + mimeFor(uri) + ";base64," + Base64.getEncoder().encodeToString(data),
                )
            }
            JsonObject(map)
        }

        val patchedRoot = root.toMutableMap().apply {
            if (buffers.isNotEmpty()) {
                this["buffers"] = buildJsonArray {
                    add(buildJsonObject { put("byteLength", JsonPrimitive(binBytes.size)) })
                }
            }
            patchedViews?.let { this["bufferViews"] = JsonArray(it) }
            patchedImages?.let { this["images"] = JsonArray(it) }
        }

        return buildGlb(JsonObject(patchedRoot).toString().toByteArray(Charsets.UTF_8), binBytes)
    }

    /** Wraps a JSON document and an optional binary chunk into a GLB container. */
    fun buildGlb(jsonBytes: ByteArray, binBytes: ByteArray): ByteArray {
        val jsonLen = align4(jsonBytes.size)
        val binLen = align4(binBytes.size)
        val hasBin = binBytes.isNotEmpty()
        val total = 12 + 8 + jsonLen + if (hasBin) 8 + binLen else 0
        val buffer = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(GLB_MAGIC)
        buffer.putInt(2)
        buffer.putInt(total)
        buffer.putInt(jsonLen)
        buffer.putInt(CHUNK_JSON)
        buffer.put(jsonBytes)
        repeat(jsonLen - jsonBytes.size) { buffer.put(0x20) } // JSON chunks pad with spaces
        if (hasBin) {
            buffer.putInt(binLen)
            buffer.putInt(CHUNK_BIN)
            buffer.put(binBytes)
            repeat(binLen - binBytes.size) { buffer.put(0) }
        }
        return buffer.array()
    }

    /** Decodes a base64 `data:` URI, or returns null when it is not one. */
    fun decodeDataUri(uri: String): ByteArray? {
        if (!uri.startsWith("data:")) return null
        val comma = uri.indexOf(',')
        if (comma < 0) return null
        if (!uri.substring(5, comma).endsWith(";base64")) return null
        return try {
            Base64.getDecoder().decode(uri.substring(comma + 1))
        } catch (e: Exception) {
            null
        }
    }

    /** Decodes `%20`-style escapes without turning `+` into a space. */
    fun decodeUri(uri: String): String {
        if ('%' !in uri) return uri
        val out = StringBuilder(uri.length)
        var i = 0
        while (i < uri.length) {
            val c = uri[i]
            if (c == '%' && i + 2 < uri.length) {
                val hex = uri.substring(i + 1, i + 3)
                val value = hex.toIntOrNull(16)
                if (value != null) {
                    out.append(value.toChar())
                    i += 3
                    continue
                }
            }
            out.append(c)
            i++
        }
        return out.toString()
    }

    /** Last path segment of a URI, used as a case-insensitive ZIP lookup key. */
    fun baseName(uri: String): String = decodeUri(uri).substringAfterLast('/')

    private fun mimeFor(uri: String): String = when (baseName(uri).substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "ktx2" -> "image/ktx2"
        "basis" -> "image/basis"
        else -> "application/octet-stream"
    }

    private fun align4(size: Int): Int = (size + 3) and 3.inv()
}
