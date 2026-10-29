package com.arisu.companion.pet

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PetModelInspectorTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun gltf(multiBuffer: Boolean = false, imageUri: String = "tex.png"): String =
        if (!multiBuffer) {
            """
            {
              "asset": {"version": "2.0"},
              "buffers": [{"uri": "model.bin", "byteLength": 36}],
              "bufferViews": [{"buffer": 0, "byteOffset": 0, "byteLength": 36}],
              "images": [{"uri": "$imageUri"}],
              "meshes": [{"primitives": [{"attributes": {"POSITION": 0}}]}],
              "accessors": [{"bufferView": 0, "componentType": 5126, "count": 3, "type": "VEC3"}],
              "animations": [{"name": "Wave", "channels": [], "samplers": []}]
            }
            """.trimIndent()
        } else {
            """
            {
              "asset": {"version": "2.0"},
              "buffers": [
                {"uri": "a.bin", "byteLength": 36},
                {"uri": "tex%20b.bin", "byteLength": 8}
              ],
              "bufferViews": [
                {"buffer": 0, "byteOffset": 0, "byteLength": 36},
                {"buffer": 1, "byteOffset": 0, "byteLength": 8}
              ],
              "meshes": [{"primitives": [{"attributes": {"POSITION": 0}}]}],
              "animations": [{"name": "Idle", "channels": [], "samplers": []}]
            }
            """.trimIndent()
        }

    private fun zip(entries: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun jsonChunkOf(glb: ByteArray): String {
        val length = (glb[12].toInt() and 0xFF) or ((glb[13].toInt() and 0xFF) shl 8) or
            ((glb[14].toInt() and 0xFF) shl 16) or ((glb[15].toInt() and 0xFF) shl 24)
        return String(glb, 20, length, Charsets.UTF_8)
    }

    @Test
    fun `packs a zipped gltf with its bin and texture into one glb`() {
        val raw = zip(
            mapOf(
                "character/model.gltf" to gltf().toByteArray(),
                "character/model.bin" to ByteArray(36) { 1 },
                "character/tex.png" to byteArrayOf(1, 2, 3, 4),
            ),
        )
        val result = PetModelInspector.inspect(raw, "character.zip")
        assertTrue("expected success, got $result", result is PetModelInspector.Result.Glb)
        val glb = (result as PetModelInspector.Result.Glb).bytes
        assertEquals("Wave", result.info.animations.single())
        assertEquals(1, result.info.meshCount)

        val packed = json.parseToJsonElement(jsonChunkOf(glb)).jsonObject
        val buffer = packed["buffers"]!!.jsonArray.single().jsonObject
        assertEquals(36, buffer["byteLength"]!!.jsonPrimitive.content.toInt())
        assertFalse("uri must be dropped so the BIN chunk is used", buffer.containsKey("uri"))
        val image = packed["images"]!!.jsonArray.single().jsonObject["uri"]!!.jsonPrimitive.content
        assertTrue("texture inlined as data URI", image.startsWith("data:image/png;base64,"))
    }

    @Test
    fun `rebases buffer offsets when the gltf has several buffers`() {
        val raw = zip(
            mapOf(
                "scene.gltf" to gltf(multiBuffer = true).toByteArray(),
                "a.bin" to ByteArray(36),
                "tex b.bin" to ByteArray(8), // referenced as tex%20b.bin
            ),
        )
        val result = PetModelInspector.inspect(raw, "scene.zip")
        assertTrue("expected success, got $result", result is PetModelInspector.Result.Glb)
        val packed = json.parseToJsonElement(jsonChunkOf((result as PetModelInspector.Result.Glb).bytes)).jsonObject
        val views = packed["bufferViews"]!!.jsonArray.map { it.jsonObject }
        assertEquals(0, views[0]["byteOffset"]!!.jsonPrimitive.content.toInt())
        assertEquals("second buffer starts after the first", 36, views[1]["byteOffset"]!!.jsonPrimitive.content.toInt())
        assertEquals(44, packed["buffers"]!!.jsonArray.single().jsonObject["byteLength"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `reports a missing side-car asset instead of producing a broken glb`() {
        val raw = zip(mapOf("model.gltf" to gltf().toByteArray(), "tex.png" to ByteArray(4)))
        val result = PetModelInspector.inspect(raw, "broken.zip")
        assertTrue(result is PetModelInspector.Result.Failure)
        val message = (result as PetModelInspector.Result.Failure).message
        assertTrue("mentions the missing buffer: $message", message.contains("model.bin"))
    }

    @Test
    fun `accepts a glb inside a zip`() {
        val inner = GltfAssetPacker.buildGlb(gltf().toByteArray(), ByteArray(36))
        val raw = zip(mapOf("model.glb" to inner, "readme.txt" to "hi".toByteArray()))
        val result = PetModelInspector.inspect(raw, "bundle.zip")
        assertTrue(result is PetModelInspector.Result.Glb)
    }

    @Test
    fun `accepts a raw gltf whose resources are data uris`() {
        val document = """
            {
              "asset": {"version": "2.0"},
              "buffers": [{"uri": "data:application/octet-stream;base64,AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", "byteLength": 36}],
              "bufferViews": [{"buffer": 0, "byteOffset": 0, "byteLength": 36}],
              "meshes": [{"primitives": [{"attributes": {"POSITION": 0}}]}],
              "animations": [{"name": "Hi", "channels": [], "samplers": []}]
            }
        """.trimIndent()
        val result = PetModelInspector.inspect(document.toByteArray(), "inline.gltf")
        assertTrue("expected success, got $result", result is PetModelInspector.Result.Glb)
        assertEquals(listOf("Hi"), (result as PetModelInspector.Result.Glb).info.animations)
    }

    @Test
    fun `rejects garbage, empty files and zip path traversal`() {
        assertTrue(PetModelInspector.inspect(ByteArray(0)) is PetModelInspector.Result.Failure)
        assertTrue(PetModelInspector.inspect(ByteArray(64) { 7 }) is PetModelInspector.Result.Failure)

        val traversal = zip(mapOf("../evil.gltf" to gltf().toByteArray(), "model.bin" to ByteArray(36)))
        val result = PetModelInspector.inspect(traversal, "evil.zip")
        assertTrue(result is PetModelInspector.Result.Failure)
        assertTrue(
            (result as PetModelInspector.Result.Failure).message.contains("path"),
        )
    }

    @Test
    fun `rejects a glb without meshes`() {
        val noMesh = """
            {"asset": {"version": "2.0"}, "buffers": [{"byteLength": 4}]}
        """.trimIndent()
        val glb = GltfAssetPacker.buildGlb(noMesh.toByteArray(), ByteArray(4))
        val result = PetModelInspector.inspect(glb, "empty.glb")
        assertTrue(result is PetModelInspector.Result.Failure)
        assertTrue((result as PetModelInspector.Result.Failure).message.contains("mesh"))
    }

    @Test
    fun `accepts the real bundled arisu model unchanged`() {
        val file = sequenceOf(
            java.io.File("src/main/assets/Arisu (Maid).glb"),
            java.io.File("app/src/main/assets/Arisu (Maid).glb"),
        ).firstOrNull { it.exists() }
        assertNotNull("sample asset present", file)
        val result = PetModelInspector.inspect(file!!.readBytes(), "Arisu (Maid).glb")
        assertTrue(result is PetModelInspector.Result.Glb)
        val info = (result as PetModelInspector.Result.Glb).info
        assertTrue(info.animations.contains("Cafe_Idle"))
        assertTrue(info.meshCount > 0)
    }
}
