package com.arisu.companion.renderer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.booleanOrNull

/**
 * Minimal, allocation-friendly GLB container inspector, usable both on the JVM
 * (tests) and on-device (About/settings diagnostics). It reads the header and
 * JSON chunk only — the binary buffer chunk is never copied.
 */
data class GlbInfo(
    val version: Int,
    val totalBytes: Long,
    val jsonBytes: Long,
    val animations: List<String>,
    val meshCount: Int,
    val materialCount: Int,
    val skinCount: Int,
    val imageCount: Int,
    val hasEmbeddedTextures: Boolean,
)

object GlbParser {

    private const val MAGIC = 0x46546C67 // 'glTF'
    private const val JSON_CHUNK = 0x4E4F534A
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(bytes: ByteArray, announce: (String) -> Unit = {}): GlbInfo {
        val buffer = bytes
        require(buffer.size >= 12) { "Too small for a GLB header" }
        val magic = readInt(buffer, 0)
        require(magic == MAGIC) { "Not a GLB file (magic=0x${Integer.toHexString(magic)})" }
        val version = readInt(buffer, 4)
        require(version == 2) { "Unsupported glTF version $version" }

        var offset = 12
        var jsonBytes = 0L
        var info: GlbInfo? = null
        while (offset + 8 <= buffer.size) {
            val chunkLength = readInt(buffer, offset)
            val chunkType = readInt(buffer, offset + 4)
            if (chunkType == JSON_CHUNK) {
                jsonBytes = chunkLength.toLong()
                info = parseJson(String(buffer, offset + 8, chunkLength, Charsets.UTF_8), version, buffer.size, chunkLength)
                break
            }
            offset += 8 + chunkLength
        }
        return requireNotNull(info) { "GLB has no JSON chunk" }
    }

    private fun parseJson(jsonText: String, version: Int, totalBytes: Int, jsonChunkLen: Int): GlbInfo {
        val root = json.parseToJsonElement(jsonText).jsonObject
        val animations = root["animations"]?.jsonArray?.mapNotNull { el ->
            el.jsonObject["name"]?.jsonPrimitive?.content
        } ?: emptyList()
        fun count(key: String): Int = root[key]?.jsonArray?.size ?: 0
        // A texture image is embedded (points into the binary buffer) when it has no URI.
        val images = root["images"]?.jsonArray ?: emptyList()
        val hasEmbeddedTextures = images.isNotEmpty() && images.all { it.jsonObject["uri"] == null }
        return GlbInfo(
            version = version,
            totalBytes = totalBytes.toLong(),
            jsonBytes = jsonChunkLen.toLong(),
            animations = animations,
            meshCount = count("meshes"),
            materialCount = count("materials"),
            skinCount = count("skins"),
            imageCount = count("images"),
            hasEmbeddedTextures = hasEmbeddedTextures,
        )
    }

    private fun readInt(b: ByteArray, offset: Int): Int =
        (b[offset].toInt() and 0xFF) or ((b[offset + 1].toInt() and 0xFF) shl 8) or
            ((b[offset + 2].toInt() and 0xFF) shl 16) or ((b[offset + 3].toInt() and 0xFF) shl 24)
}
