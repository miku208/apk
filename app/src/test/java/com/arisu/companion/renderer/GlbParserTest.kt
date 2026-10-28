package com.arisu.companion.renderer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class GlbParserTest {

    private fun realGlb(): ByteArray =
        java.io.File("src/main/assets/Arisu (Maid).glb").takeIf { it.exists() }?.readBytes()
            ?: java.io.File("app/src/main/assets/Arisu (Maid).glb").readBytes()

    @Test
    fun `parses the real Arisu maid model`() {
        val info = GlbParser.parse(realGlb())
        assertTrue(info.totalBytes > 10_000_000)
        assertTrue(info.version == 2)
        assertTrue("meshes present", info.meshCount in 1..100)
        assertTrue("materials present", info.materialCount > 0)
        assertTrue("has embedded textures", info.hasEmbeddedTextures)
        // Known clips in this asset (from the JSON chunk).
        val expected = listOf("Cafe_Idle", "Cafe_Walk", "Cafe_Reaction", "Normal_Idle", "Vital_Panic")
        expected.forEach { name ->
            assertTrue("expects clip $name", info.animations.contains(name))
        }
        // The real asset's JSON chunk measures ~2.65 MB (measured); sanity-check size only.
        assertTrue("gltf json chunk is small", info.jsonBytes < 3_500_000)
    }

    @Test
    fun `rejects garbage`() {
        val bytes = ByteArray(64) { 3 }
        assertThrows(IllegalArgumentException::class.java) { GlbParser.parse(bytes) }
    }
}
