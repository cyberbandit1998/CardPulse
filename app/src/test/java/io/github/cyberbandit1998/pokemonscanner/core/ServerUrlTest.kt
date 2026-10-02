package io.github.cyberbandit1998.pokemonscanner.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerUrlTest {
    @Test
    fun `adds https and a trailing slash`() {
        assertEquals("https://cards.example.com/", ServerUrl.normalize("cards.example.com"))
        assertEquals("https://cards.example.com/", ServerUrl.normalize("https://cards.example.com"))
    }

    @Test
    fun `keeps port and path`() {
        assertEquals("https://host:8443/poke/", ServerUrl.normalize("https://host:8443/poke"))
    }

    @Test
    fun `lowercases scheme and host and trims whitespace`() {
        assertEquals("https://host.example/", ServerUrl.normalize("  HTTPS://Host.Example  "))
    }

    @Test
    fun `rejects plain http`() {
        assertNull(ServerUrl.normalize("http://host.example"))
    }

    @Test
    fun `rejects blank and malformed input`() {
        assertNull(ServerUrl.normalize(""))
        assertNull(ServerUrl.normalize("   "))
        assertNull(ServerUrl.normalize("https://"))
    }

    @Test
    fun `rejects embedded credentials`() {
        assertNull(ServerUrl.normalize("https://user:secret@host.example"))
    }

    @Test
    fun `drops query and fragment`() {
        assertEquals("https://host.example/", ServerUrl.normalize("https://host.example/?a=1#top"))
    }
}
