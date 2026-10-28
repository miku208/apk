package com.arisu.companion.ai

import com.arisu.companion.animation.PetState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SseParserTest {

    @Test
    fun `extracts deltas from data lines`() {
        val parser = SseParser()
        assertEquals("Hello", parser.feed("""data: {"choices":[{"delta":{"content":"Hello"}}]}"""))
        assertEquals(" world", parser.feed("""data: {"choices":[{"delta":{"content":" world"}}]}"""))
        assertNull(parser.feed("\n"))
        assertFalse(parser.isDone)
        assertNull(parser.feed("data: [DONE]"))
        assertTrue(parser.isDone)
        assertNull(parser.feed("""data: {"choices":[{"delta":{"content":"late"}}]}"""))
    }

    @Test
    fun `ignores comments and unknown fields`() {
        val parser = SseParser()
        assertNull(parser.feed(": keep-alive"))
        assertNull(parser.feed("""data: {"usage":{"tokens":12}}"""))
        assertEquals("x", parser.feed("""data: {"choices":[{"delta":{"content":"x","role":null}}]}"""))
    }

    @Test
    fun `non-streaming body parse`() {
        val body = """{"choices":[{"message":{"role":"assistant","content":"Full answer"},"finish_reason":"stop"}]}"""
        assertEquals("Full answer", SseParser.fullMessage(body))
    }

    @Test
    fun provider_error_extraction() {
        assertEquals("bad key", SseParser.errorMessage("""{"error":{"message":"bad key"}}"""))
        assertEquals("oops", SseParser.errorMessage("""{"message":"oops"}"""))
        assertNull(SseParser.errorMessage("plain not json"))
    }
}

class EmotionTagFilterTest {

    @Test
    fun `releases normal text immediately`() {
        val f = EmotionTagFilter()
        val (text, state) = f.feed("Hi there ")
        assertEquals("Hi there ", text)
        assertEquals(null, state)
        val (text2, _) = f.feed("friend")
        assertEquals("friend", text2)
    }

    @Test
    fun `keeps partial marker out of display until resolved`() {
        val f = EmotionTagFilter()
        val (a, _) = f.feed("Beep [[em")
        assertEquals("Beep ", a)
        val (b, _) = f.feed("otion:happy]] whee")
        assertEquals(" whee", b)
    }

    @Test
    fun `prose starting with brackets survives`() {
        val f = EmotionTagFilter()
        val (text, _) = f.feed("Look [[ 1 + 2 ]] and 64+ characters off tag limit exceeded here ok")
        assertEquals("Look [[ 1 + 2 ]] and 64+ characters off tag limit exceeded here ok", text)
    }

    @Test
    fun `detects finished tags as states`() {
        val f = EmotionTagFilter()
        val (t1, s1) = f.feed("Yay!! [[emotion:sorry]] oh no")
        assertEquals("Yay!! oh no", t1)
        assertEquals(PetState.PANIC, s1)
        assertEquals("end", f.feed("end").first)
        // "end" was fully released (no buffered partial marker), so nothing remains.
        assertEquals("", f.flush())
    }
}
