package app.cardpulse.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The four places the bottom bar leads to. */
enum class MainTab(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Default.Home),
    SCAN("Scan", Icons.Default.DocumentScanner),
    COLLECTION("Collection", Icons.Default.GridView),
    PORTFOLIO("Portfolio", Icons.Default.BarChart),
}

private val BarHeight = 64.dp

/** How far the round camera button rises above the bar. Screens that scroll leave this much room at their end. */
val BottomBarOverhang: Dp = 30.dp

/**
 * The bottom bar: Home and Scan on the left, Collection and Portfolio on the right, and between them a round camera
 * button that rises above the bar and opens the camera at once, from wherever the user is.
 */
@Composable
fun CardPulseBottomBar(
    selected: MainTab,
    onSelect: (MainTab) -> Unit,
    onScanNow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val barColor = MaterialTheme.colorScheme.surface
    val shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    // No clipping around the two: the button is drawn above the bar, outside its top edge.
    Box(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(barColor)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
                .navigationBarsPadding()
                .height(BarHeight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BarItem(MainTab.HOME, selected == MainTab.HOME) { onSelect(MainTab.HOME) }
            BarItem(MainTab.SCAN, selected == MainTab.SCAN) { onSelect(MainTab.SCAN) }
            Spacer(Modifier.weight(1f)) // the room under the round button
            BarItem(MainTab.COLLECTION, selected == MainTab.COLLECTION) { onSelect(MainTab.COLLECTION) }
            BarItem(MainTab.PORTFOLIO, selected == MainTab.PORTFOLIO) { onSelect(MainTab.PORTFOLIO) }
        }
        ScanNowButton(
            onClick = onScanNow,
            ring = barColor,
            modifier = Modifier.align(Alignment.TopCenter).offset(y = -BottomBarOverhang),
        )
    }
}

@Composable
private fun RowScope.BarItem(tab: MainTab, selected: Boolean, onClick: () -> Unit) {
    val tint = if (selected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        Modifier
            .weight(1f)
            .fillMaxHeight()
            .selectable(selected = selected, role = Role.Tab, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(tab.icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
        Text(tab.label, style = MaterialTheme.typography.labelSmall, color = tint, maxLines = 1)
        // A small dot under the open tab, as a second cue besides the colour.
        Box(
            Modifier
                .padding(top = 3.dp)
                .size(4.dp)
                .clip(CircleShape)
                .background(if (selected) tint else Color.Transparent),
        )
    }
}

@Composable
private fun ScanNowButton(onClick: () -> Unit, ring: Color, modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    // The ring is the bar's own colour, so the button looks set into the bar.
    Box(modifier.size(70.dp).clip(CircleShape).background(ring), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(58.dp)
                .shadow(10.dp, CircleShape, ambientColor = primary, spotColor = primary)
                .clip(CircleShape)
                .background(Brush.verticalGradient(listOf(Color(0xFFFF7A70), primary)))
                .clickable(role = Role.Button, onClickLabel = "Open the camera", onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.CameraAlt, contentDescription = "Scan cards with the camera", tint = Color.White, modifier = Modifier.size(28.dp))
        }
    }
}
