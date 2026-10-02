package io.github.cyberbandit1998.pokemonscanner.core

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Builds URLs for images the server hosts. `base` is the normalized server address ("https://host/"). */
object ServerUrls {
    fun cardImage(base: String, cardId: String, large: Boolean = false): String =
        build(base, "api", "images", "card", cardId, if (large) "large" else "small")

    fun ownPhoto(base: String, collectionItemId: Int): String =
        build(base, "api", "collection", collectionItemId.toString(), "photo")

    fun scanItemImage(base: String, jobId: Int, itemId: Int): String =
        build(base, "api", "cards", "recognize", "jobs", jobId.toString(), "items", itemId.toString(), "image")

    fun candidateImage(base: String, jobId: Int, itemId: Int, index: Int): String =
        build(
            base, "api", "cards", "recognize", "jobs", jobId.toString(), "items", itemId.toString(),
            "candidates", index.toString(), "image",
        )

    private fun build(base: String, vararg segments: String): String {
        val url = base.toHttpUrlOrNull() ?: return ""
        val builder = url.newBuilder()
        // The base ends with "/", which HttpUrl models as an empty last segment; addPathSegment replaces it.
        segments.forEach { builder.addPathSegment(it) }
        return builder.build().toString()
    }
}

enum class ArtSource { OFFICIAL, OWN_PHOTO }

fun CardDto?.hasCatalogueImage(): Boolean =
    this != null && (!imagesSmall.isNullOrBlank() || !imagesLarge.isNullOrBlank() || !customImageUrl.isNullOrBlank())

/** Mirrors the PokéCollector web app: your own photo is a fallback unless you chose to prefer it. */
fun defaultArtSource(item: CollectionItemDto, preferOwnPhotos: Boolean): ArtSource =
    if (item.hasScanPhoto && (preferOwnPhotos || !item.card.hasCatalogueImage())) ArtSource.OWN_PHOTO
    else ArtSource.OFFICIAL
