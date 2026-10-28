package com.arisu.companion.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class CryptoStoreTest {

    private fun key(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test
    fun `round trip preserves the api key`() {
        val store = CryptoStore(FakeVault(key()))
        val secret = "nvapi-abc123DEF-=+/whatever"
        val encoded = store.encrypt(secret)
        assertEquals(secret, store.decrypt(encoded))
        // Non-deterministic encryption (random IV), but both decode back.
        assertEquals(secret, store.decrypt(store.encrypt(secret)))
    }

    @Test
    fun `garble is rejected not crashed`() {
        val store = CryptoStore(FakeVault(key()))
        assertNull(store.decryptOrNull("not-base64!!!"))
        assertNull(store.decryptOrNull("AAAA"))
        assertNull(store.decryptOrNull(""))
    }

    @Test
    fun `wrong key fails`() {
        val store = CryptoStore(FakeVault(key()))
        val encoded = store.encrypt("hello")
        assertThrows(Exception::class.java) { CryptoStore(FakeVault(key())).decrypt(encoded) }
    }

    @Test
    fun encryption_hides_plaintext() {
        val store = CryptoStore(FakeVault(key()))
        val encoded = store.encrypt("super-secret-value")
        assertTrue("plaintext must not leak", !encoded.contains("super-secret-value"))
    }
}
