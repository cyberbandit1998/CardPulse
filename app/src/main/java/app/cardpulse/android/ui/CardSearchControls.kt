package app.cardpulse.android.ui

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * What the screens can do to start a search, handed to all of them at once ([LocalCardSearch]) so that a card's details can make
 * the artist's name a way into the search for every card they drew, without every screen in between passing a callback along.
 * Where nothing provides any ([None]) the artist is shown as plain text.
 */
@Immutable
class CardSearchControls(
    val enabled: Boolean = false,
    /** Opens the search for every card [artist] drew. */
    val byArtist: (artist: String) -> Unit = {},
) {
    companion object {
        /** No search: the artist is not pressable. What a screen gets when nothing provides one. */
        val None = CardSearchControls()
    }
}

val LocalCardSearch = staticCompositionLocalOf { CardSearchControls.None }
