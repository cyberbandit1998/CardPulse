package app.cardpulse.android.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A phone's screen, and the bars that sit over the camera on it, for the tests and pictures of the camera screen. The bars matter:
 * a test window has none, which is how the instructions ran into the card outline on a real phone without any picture showing it.
 */
internal class Phone(val width: Dp, val height: Dp, val statusBar: Dp, val navigationBar: Dp)

/** The phone the overlap was reported on: 1080 x 2340 pixels, with a status bar about 50 dp tall. */
internal val tallPhone = Phone(400.dp, 867.dp, statusBar = 50.dp, navigationBar = 24.dp)

/** A plainer one: 360 x 780 dp, with the usual 24 dp status bar. */
internal val plainPhone = Phone(360.dp, 780.dp, statusBar = 24.dp, navigationBar = 24.dp)
