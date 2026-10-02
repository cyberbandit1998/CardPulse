package io.github.cyberbandit1998.pokemonscanner.core

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Parsing and validation of the PokéCollector server address the user types in. */
object ServerUrl {
    /**
     * Returns the address as an `https://host[:port][/path]/` string with a trailing slash,
     * or null when the input is blank or not a usable HTTPS address.
     *
     * A missing scheme is assumed to be https, so "cards.example.com" works as typed.
     * Plain http, embedded credentials, query strings and fragments are rejected or dropped:
     * the address is stored on the device and every request is sent to it.
     */
    fun normalize(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null

        val candidate = if (trimmed.contains("://")) trimmed else "https://$trimmed"
        val url = candidate.toHttpUrlOrNull() ?: return null
        if (!url.isHttps) return null
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) return null

        val text = url.newBuilder().query(null).fragment(null).build().toString()
        return if (text.endsWith("/")) text else "$text/"
    }
}
