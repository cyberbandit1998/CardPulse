package app.cardpulse.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/**
 * What the screens can do with the wishlist, handed to all of them at once ([LocalWishlist]) so that a heart can sit on any card
 * without every screen in between passing a callback along. Until the wishlist has loaded ([ready]) no heart is drawn: nothing
 * honest can be said about a card before that, and adding one that is already listed would raise its quantity on the server.
 */
@Immutable
class WishlistControls(
    val ready: Boolean = false,
    /** The ids of the cards on the wishlist. */
    val listed: Set<String> = emptySet(),
    /** Cards whose adding or removing is on its way to the server. */
    val pending: Set<String> = emptySet(),
    /** Puts the card on the wishlist, or takes it off. */
    val toggle: (cardId: String) -> Unit = {},
) {
    /** Whether to draw the card as on the wishlist: as the server last said, turned over while a change is on its way. */
    fun shownOn(cardId: String): Boolean = (cardId in listed) != (cardId in pending)

    companion object {
        /** No wishlist: no heart anywhere. What a screen gets when nothing provides one. */
        val None = WishlistControls()
    }
}

val LocalWishlist = staticCompositionLocalOf { WishlistControls.None }

/**
 * A heart for a card: filled when the card is on the wishlist, an outline when it is not. Pressing it puts the card on the
 * wishlist or takes it off. [onPicture] gives it a pale round backing so that it can be seen on a card's art.
 */
@Composable
fun WishlistHeart(cardId: String?, cardName: String, modifier: Modifier = Modifier, onPicture: Boolean = false) {
    val wishlist = LocalWishlist.current
    if (!wishlist.ready || cardId.isNullOrBlank()) return
    val on = wishlist.shownOn(cardId)
    val name = cardName.ifBlank { "this card" }
    IconButton(
        onClick = { wishlist.toggle(cardId) },
        modifier = modifier
            .size(36.dp)
            .then(if (onPicture) Modifier.background(MaterialTheme.colorScheme.surface.copy(alpha = 0.88f), CircleShape) else Modifier),
    ) {
        Icon(
            imageVector = if (on) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
            contentDescription = if (on) "Remove $name from the wishlist" else "Add $name to the wishlist",
            tint = if (on) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** A small filled heart for the corner of a card's picture when the card is on the wishlist; nothing for any other card. */
@Composable
fun WishlistBadge(cardId: String?, modifier: Modifier = Modifier) {
    val wishlist = LocalWishlist.current
    if (!wishlist.ready || cardId.isNullOrBlank() || !wishlist.shownOn(cardId)) return
    Box(
        modifier.size(22.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.Favorite,
            contentDescription = "On your wishlist",
            tint = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.size(14.dp),
        )
    }
}

/** The button of a card's details: "Add to wishlist", or "On your wishlist · Remove" for a card that is on it. */
@Composable
fun WishlistToggleButton(cardId: String?, modifier: Modifier = Modifier) {
    val wishlist = LocalWishlist.current
    if (!wishlist.ready || cardId.isNullOrBlank()) return
    val on = wishlist.shownOn(cardId)
    // The accent colour, not the brand red the theme gives outlined buttons: that one is too dark for text on the dark theme.
    OutlinedButton(
        onClick = { wishlist.toggle(cardId) },
        modifier = modifier.fillMaxWidth(),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.secondary),
    ) {
        Icon(if (on) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(if (on) "On your wishlist · Remove" else "Add to wishlist")
    }
}
