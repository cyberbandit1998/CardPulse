package app.cardpulse.android.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.cardpulse.android.core.ChartPoint
import app.cardpulse.android.core.CollectionItemDto
import app.cardpulse.android.core.MoneyFormatter
import app.cardpulse.android.core.ServerUrls
import app.cardpulse.android.core.SetProgress
import app.cardpulse.android.core.TopCardDto
import app.cardpulse.android.core.monogram
import app.cardpulse.android.ui.AppState
import app.cardpulse.android.ui.CARD_ASPECT
import app.cardpulse.android.ui.CardArt
import app.cardpulse.android.ui.theme.extras
import coil3.compose.SubcomposeAsyncImage

// The pieces the Home screen is made of. They hold no data of their own: everything is passed in.

// ---------------------------------------------------------------------------------------------
// Header
// ---------------------------------------------------------------------------------------------

/** A heartbeat line: the app's mark. */
@Composable
internal fun PulseMark(modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.secondary) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val line = Path().apply {
            moveTo(0f, h * 0.55f)
            lineTo(w * 0.26f, h * 0.55f)
            lineTo(w * 0.38f, h * 0.20f)
            lineTo(w * 0.56f, h * 0.88f)
            lineTo(w * 0.68f, h * 0.40f)
            lineTo(w * 0.76f, h * 0.55f)
            lineTo(w, h * 0.55f)
        }
        drawPath(line, color, style = Stroke(width = 2.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** A round button with a thin outline, as in the header. */
@Composable
internal fun RoundIconButton(icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(
        onClick = onClick,
        modifier = modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
    ) {
        Icon(icon, contentDescription = description, modifier = Modifier.size(22.dp))
    }
}

/** The app's name with whether the server answered, and the two buttons: refresh and settings. */
@Composable
internal fun HomeHeader(
    connected: Boolean,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val status = if (connected) MaterialTheme.extras.positive else MaterialTheme.extras.negative
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        PulseMark(Modifier.size(width = 36.dp, height = 30.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("CardPulse", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 1)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(status))
                Spacer(Modifier.width(6.dp))
                Text(if (connected) "Connected" else "Offline", style = MaterialTheme.typography.labelLarge, color = status)
            }
        }
        RoundIconButton(Icons.Default.Refresh, "Refresh", onRefresh)
        Spacer(Modifier.width(8.dp))
        RoundIconButton(Icons.Default.Settings, "Settings", onOpenSettings)
    }
}

// ---------------------------------------------------------------------------------------------
// Collection value
// ---------------------------------------------------------------------------------------------

/**
 * The collection's value with what it has gained, a line of how it got there, and, when some cards have no purchase
 * price, a one-line warning that opens the explanation.
 */
@Composable
internal fun SummaryCard(
    value: String,
    gain: String,
    gainLabel: String,
    gainColor: Color,
    costBasis: String,
    history: List<ChartPoint>,
    cardsMissingCost: Int,
    onOpenPortfolio: () -> Unit,
    onExplainCost: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val extras = MaterialTheme.extras
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val shape = RoundedCornerShape(24.dp)
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val valueStyle = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold)
    // How wide the figure is, to know how much room is left to its right for the chart.
    val valueWidth = remember(value, valueStyle, density) { with(density) { measurer.measure(value, valueStyle).size.width.toDp() } }
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.linearGradient(listOf(extras.heroTop, extras.heroBottom)))
            .border(1.dp, extras.heroBorder, shape),
    ) {
        // The chart sits to the right, under the arrow and above the gain line, in whatever room the figure leaves
        // (none, on a narrow screen with a big number), so it never runs through the text.
        val chartWidth = minOf(112.dp, maxWidth - 20.dp - valueWidth - 12.dp - 20.dp)
        if (history.size >= 2 && chartWidth >= 56.dp) {
            Sparkline(
                history,
                MaterialTheme.colorScheme.secondary,
                Modifier.align(Alignment.TopEnd).padding(top = 54.dp, end = 20.dp).width(chartWidth).height(32.dp),
            )
        }
        OpenPortfolioButton(onOpenPortfolio, Modifier.align(Alignment.TopEnd).padding(top = 14.dp, end = 14.dp))
        Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
            Text("Collection value", style = MaterialTheme.typography.bodyLarge, color = muted)
            Text(value, style = valueStyle, maxLines = 1)
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = gainColor, fontWeight = FontWeight.SemiBold)) { append("$gain $gainLabel") }
                    withStyle(SpanStyle(color = muted)) { append("  •  Cost basis $costBasis") }
                },
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 2.dp),
            )
            if (cardsMissingCost > 0) {
                Spacer(Modifier.height(14.dp))
                MissingCostRow(cardsMissingCost, onExplainCost)
            }
        }
    }
}

/** A thin outlined circle with an arrow that points up and to the right. */
@Composable
private fun OpenPortfolioButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.secondary
    Box(
        modifier
            .size(38.dp)
            .clip(CircleShape)
            .border(1.5.dp, accent.copy(alpha = 0.6f), CircleShape)
            .semantics { contentDescription = "Open the portfolio" }
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(16.dp)) {
            val w = size.width
            val h = size.height
            val arrow = Path().apply {
                moveTo(w * 0.10f, h * 0.90f)
                lineTo(w * 0.90f, h * 0.10f)
                moveTo(w * 0.30f, h * 0.10f)
                lineTo(w * 0.90f, h * 0.10f)
                lineTo(w * 0.90f, h * 0.70f)
            }
            drawPath(arrow, accent, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

@Composable
private fun MissingCostRow(cards: Int, onClick: () -> Unit) {
    val extras = MaterialTheme.extras
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(extras.heroRow)
            .border(1.dp, extras.heroBorder, shape)
            .clickable(role = Role.Button, onClickLabel = "Explain", onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Default.Error, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(22.dp))
        Text(
            if (cards == 1) "1 card missing cost basis" else "$cards cards missing cost basis",
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
        )
        Chevron()
    }
}

/** A small line chart with a soft fill under it: no axes, just which way the value went. */
@Composable
internal fun Sparkline(points: List<ChartPoint>, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        if (points.size < 2) return@Canvas
        val values = points.map { it.value }
        val low = values.min()
        val high = values.max()
        val flat = high - low < 1e-9
        val span = if (flat) 1.0 else high - low
        val start = points.first().time.toEpochMilli()
        val duration = (points.last().time.toEpochMilli() - start).coerceAtLeast(1L)
        val pad = 3.dp.toPx()
        val plotHeight = size.height - 2 * pad

        fun xOf(point: ChartPoint) = size.width * (point.time.toEpochMilli() - start).toFloat() / duration
        fun yOf(point: ChartPoint) =
            if (flat) size.height / 2 else pad + plotHeight - ((point.value - low) / span * plotHeight).toFloat()

        val line = Path()
        val area = Path()
        points.forEachIndexed { index, point ->
            val x = xOf(point)
            val y = yOf(point)
            if (index == 0) {
                line.moveTo(x, y)
                area.moveTo(x, size.height)
                area.lineTo(x, y)
            } else {
                line.lineTo(x, y)
                area.lineTo(x, y)
            }
        }
        area.lineTo(xOf(points.last()), size.height)
        area.close()

        drawPath(area, brush = Brush.verticalGradient(listOf(color.copy(alpha = 0.30f), Color.Transparent)))
        drawPath(line, color = color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

// ---------------------------------------------------------------------------------------------
// Numbers at a glance
// ---------------------------------------------------------------------------------------------

/**
 * One of the three small tiles: an icon, a figure, and what it counts. With [onClick] it is a button: it ripples where it
 * is pressed and sinks a little under the finger, so it plainly answers. [actionLabel] says what pressing does, for a
 * screen reader ("Show all cards"). Without [onClick] it is just a tile.
 */
@Composable
internal fun HomeStat(
    icon: ImageVector,
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    actionLabel: String? = null,
) {
    val shape = RoundedCornerShape(18.dp)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.95f else 1f, label = "tile press")
    Row(
        modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            // The clip comes before the click so the ripple is held inside the rounded corners.
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .then(
                if (onClick == null) {
                    // Read out as one thing ("12 Cards"), the way it is when it can be pressed.
                    Modifier.semantics(mergeDescendants = true) {}
                } else {
                    Modifier.clickable(
                        interactionSource = interaction,
                        indication = LocalIndication.current,
                        role = Role.Button,
                        onClickLabel = actionLabel,
                        onClick = onClick,
                    )
                },
            )
            .padding(horizontal = 10.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f)) {
            FitText(value, MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
            FitText(
                label,
                MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                minSize = 9.sp,
            )
        }
    }
}

/** One line of text that gets smaller, down to a limit, until it fits the room it has instead of being cut off. */
@Composable
internal fun FitText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    minSize: TextUnit = MIN_FIT_SIZE,
) {
    var size by remember(text, style.fontSize) { mutableStateOf(style.fontSize) }
    var settled by remember(text, style.fontSize) { mutableStateOf(false) }
    Text(
        text,
        // Hidden while it is still being shrunk, so the user never sees it jump.
        modifier = modifier.drawWithContent { if (settled) drawContent() },
        style = style.copy(fontSize = size),
        color = color,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Clip,
        onTextLayout = { result ->
            if (result.didOverflowWidth && size > minSize) size *= 0.92f else settled = true
        },
    )
}

private val MIN_FIT_SIZE = 11.sp

// ---------------------------------------------------------------------------------------------
// Sections
// ---------------------------------------------------------------------------------------------

/** A section's title, with "See all" at the far end when there is more to see. */
@Composable
internal fun SectionHeader(title: String, modifier: Modifier = Modifier, onSeeAll: (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        if (onSeeAll != null) {
            val accent = MaterialTheme.colorScheme.secondary
            Row(
                Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(role = Role.Button, onClick = onSeeAll)
                    .heightIn(min = 40.dp)
                    .padding(start = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("See all", style = MaterialTheme.typography.labelLarge, color = accent)
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = accent, modifier = Modifier.size(20.dp))
            }
        }
    }
}

/** The rounded container the list sections sit in. */
@Composable
internal fun ListCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape),
        content = content,
    )
}

/** A hairline between two rows of a [ListCard], starting where the text starts. */
@Composable
internal fun RowDivider(startIndent: Dp = 64.dp) {
    HorizontalDivider(Modifier.padding(start = startIndent), thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
internal fun Chevron() {
    Icon(
        Icons.AutoMirrored.Filled.KeyboardArrowRight,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(22.dp),
    )
}

private fun Modifier.clickableIf(onClick: (() -> Unit)?): Modifier =
    if (onClick == null) this else this.clickable(role = Role.Button, onClick = onClick)

/** A card of the "most valuable" list: a small picture, its name and how it is kept, what it is worth, and a chevron. */
@Composable
internal fun ValuableRow(
    top: TopCardDto,
    entry: CollectionItemDto?,
    state: AppState,
    money: MoneyFormatter,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth().clickableIf(onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (entry != null) {
            CardArt(entry, state.serverUrl, state.prefs, Modifier.width(40.dp).aspectRatio(CARD_ASPECT))
        } else {
            Spacer(Modifier.width(40.dp).aspectRatio(CARD_ASPECT))
        }
        Column(Modifier.weight(1f)) {
            Text(top.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "×${top.quantity} · ${top.condition} · ${top.variant}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(money.format(top.totalValue), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1)
        Chevron()
    }
}

/**
 * A set of the "set progress" list: its logo, its name over a slim bar, how many of its cards are owned, and a chevron.
 * The name keeps to [nameLines] lines and is cut with "…" after that; the Sets tab gives it two, so that sets whose names
 * start alike can be told apart.
 */
@Composable
internal fun SetProgressRow(
    set: SetProgress,
    serverUrl: String,
    modifier: Modifier = Modifier,
    nameLines: Int = 1,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth().clickableIf(onClick).padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SetLogo(ServerUrls.setLogo(serverUrl, set.id), set.name, Modifier.width(52.dp).height(34.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(set.name, style = MaterialTheme.typography.titleMedium, maxLines = nameLines, overflow = TextOverflow.Ellipsis)
            SlimProgress(set.fraction, color = if (set.isComplete) MaterialTheme.extras.positive else MaterialTheme.colorScheme.secondary)
        }
        // A fixed width, so the bars of different rows end in the same place.
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)) { append("${set.owned}") }
                withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) { append(" / ${set.totalText}") }
            },
            modifier = Modifier.widthIn(min = 64.dp),
            style = MaterialTheme.typography.titleSmall,
            textAlign = TextAlign.End,
            maxLines = 1,
        )
        Chevron()
    }
}

/** A set's logo from the server, or, while there is none to show (it loads, or the set has no logo), its initials. */
@Composable
internal fun SetLogo(url: String, name: String, modifier: Modifier = Modifier) {
    SubcomposeAsyncImage(
        model = url,
        contentDescription = null,
        modifier = modifier,
        contentScale = ContentScale.Fit,
        loading = { Box(Modifier.fillMaxSize()) },
        error = { SetMonogram(name, Modifier.fillMaxSize()) },
    )
}

@Composable
private fun SetMonogram(name: String, modifier: Modifier = Modifier) {
    Box(
        modifier.clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name.monogram(),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/** A bar 6 dp thick, filled from the start by [fraction] (0 to 1). A little is always shown once something is owned. */
@Composable
internal fun SlimProgress(fraction: Float, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.secondary) {
    val accent = color
    Box(modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(MaterialTheme.colorScheme.outlineVariant)) {
        if (fraction > 0f) {
            Box(
                Modifier
                    .fillMaxWidth(fraction.coerceIn(0.03f, 1f))
                    .fillMaxHeight()
                    .clip(CircleShape)
                    .background(Brush.horizontalGradient(listOf(accent.copy(alpha = 0.8f), accent))),
            )
        }
    }
}
