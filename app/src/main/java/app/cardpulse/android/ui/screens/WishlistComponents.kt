package app.cardpulse.android.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The heart that puts a card on the wishlist or takes it off. It fills in the accent colour when the card is on the list,
 * and gives a small springy bounce each time it changes, so the tap is felt as well as seen.
 *
 * [onImage] draws it on a dark round backdrop, for use on top of card art where an outline alone would be lost.
 */
@Composable
internal fun WishlistToggleButton(
    wishlisted: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    onImage: Boolean = false,
    iconSize: Dp = 22.dp,
) {
    val bounce = remember { Animatable(1f) }
    // Skips the first composition: only a change made while the button is on screen bounces.
    val first = remember { booleanArrayOf(true) }
    LaunchedEffect(wishlisted) {
        if (first[0]) {
            first[0] = false
            return@LaunchedEffect
        }
        bounce.snapTo(0.7f)
        bounce.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium))
    }
    val tint = if (wishlisted) MaterialTheme.colorScheme.secondary else if (onImage) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
    IconButton(
        onClick = onToggle,
        modifier = modifier
            .then(if (onImage) Modifier.size(iconSize + 14.dp) else Modifier)
            .then(if (onImage) Modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)) else Modifier),
    ) {
        AnimatedContent(
            targetState = wishlisted,
            transitionSpec = { (scaleIn(initialScale = 0.6f) + fadeIn()) togetherWith fadeOut() },
            label = "wishlist heart",
        ) { on ->
            Icon(
                if (on) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                contentDescription = if (on) "Remove from wishlist" else "Add to wishlist",
                tint = tint,
                modifier = Modifier.size(iconSize).scale(bounce.value),
            )
        }
    }
}

/** The small filled heart on a card tile whose card is on the wishlist. Purely a marker: it does nothing when tapped. */
@Composable
internal fun WishlistBadge(modifier: Modifier = Modifier, size: Dp = 14.dp) {
    Box(
        modifier
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.Favorite,
            contentDescription = "On your wishlist",
            tint = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.size(size),
        )
    }
}
