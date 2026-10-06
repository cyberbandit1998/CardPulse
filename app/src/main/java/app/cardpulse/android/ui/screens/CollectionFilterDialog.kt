package app.cardpulse.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cardpulse.android.core.CollectionFilter
import app.cardpulse.android.core.CollectionFilterOptions
import app.cardpulse.android.core.CopiesFilter
import app.cardpulse.android.core.MoneyFormatter
import app.cardpulse.android.ui.AccentTextButton

/** Keeps a [CollectionFilter] across a turn of the phone, or the app being closed in the background. */
val CollectionFilterSaver: Saver<CollectionFilter, Any> = listSaver(
    save = { filter ->
        listOf(filter.rarities.toList(), filter.conditions.toList(), filter.variants.toList(), filter.copies.name, filter.minValue, filter.missingPrice)
    },
    restore = { saved ->
        @Suppress("UNCHECKED_CAST")
        CollectionFilter(
            rarities = (saved[0] as List<String>).toSet(),
            conditions = (saved[1] as List<String>).toSet(),
            variants = (saved[2] as List<String>).toSet(),
            copies = CopiesFilter.valueOf(saved[3] as String),
            minValue = saved[4] as Double,
            missingPrice = saved[5] as Boolean,
        )
    },
)

/**
 * The Filter sheet of the Collection tab: a dialog over the collection with [CollectionFilterContent], and a button that says
 * how many entries ([shownCount]) the collection now shows. Each change applies at once.
 */
@Composable
internal fun CollectionFilterDialog(
    options: CollectionFilterOptions,
    filter: CollectionFilter,
    shownCount: Int,
    currency: MoneyFormatter,
    onChange: (CollectionFilter) -> Unit,
    onClose: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Filter") },
        text = { CollectionFilterContent(options, filter, currency, onChange) },
        confirmButton = {
            AccentTextButton(onClick = onClose) { Text(if (shownCount == 1) "Show 1 entry" else "Show $shownCount entries") }
        },
        dismissButton = {
            TextButton(onClick = { onChange(CollectionFilter()) }, enabled = filter.isActive) { Text("Clear all") }
        },
    )
}

/**
 * The choices: rarity, condition, variant (holo and the rest), how much a card is worth, how many copies, and the entries
 * with no purchase price. It offers only what the collection has ([options]), plus anything already chosen, so a choice can
 * always be switched off.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CollectionFilterContent(
    options: CollectionFilterOptions,
    filter: CollectionFilter,
    currency: MoneyFormatter,
    onChange: (CollectionFilter) -> Unit,
) {
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (options.rarities.size > 1 || filter.rarities.isNotEmpty()) {
            FilterGroup("Rarity") {
                Choices(options.rarities, filter.rarities, { it }) { onChange(filter.copy(rarities = filter.rarities.toggled(it))) }
            }
        }
        if (options.conditions.size > 1 || filter.conditions.isNotEmpty()) {
            FilterGroup("Condition") {
                Choices(options.conditions, filter.conditions, { it }) { onChange(filter.copy(conditions = filter.conditions.toggled(it))) }
            }
        }
        if (options.variants.size > 1 || filter.variants.isNotEmpty()) {
            FilterGroup("Variant (holo, reverse holo…)") {
                Choices(options.variants, filter.variants, { it }) { onChange(filter.copy(variants = filter.variants.toggled(it))) }
            }
        }
        if (options.valueSteps.isNotEmpty() || filter.minValue > 0.0) {
            FilterGroup("Worth at least, each") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = filter.minValue <= 0.0, onClick = { onChange(filter.copy(minValue = 0.0)) }, label = { Text("Any") })
                    // A step chosen earlier stays visible even if a change in the collection took it off the list.
                    (options.valueSteps + listOfNotNull(filter.minValue.takeIf { it > 0.0 })).distinct().sorted().forEach { step ->
                        FilterChip(
                            selected = filter.minValue == step,
                            onClick = { onChange(filter.copy(minValue = step)) },
                            label = { Text("${currency.wholeDisplayAmount(step)}+") },
                        )
                    }
                }
            }
        }
        if (options.hasMultiples || filter.copies != CopiesFilter.ANY) {
            FilterGroup("Copies") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CopiesFilter.entries.forEach { choice ->
                        FilterChip(selected = filter.copies == choice, onClick = { onChange(filter.copy(copies = choice)) }, label = { Text(choice.label) })
                    }
                }
            }
        }
        if (options.missingPriceCount > 0 || filter.missingPrice) {
            FilterGroup("Cost basis") {
                FilterChip(
                    selected = filter.missingPrice,
                    onClick = { onChange(filter.copy(missingPrice = !filter.missingPrice)) },
                    label = { Text("No purchase price (${options.missingPriceCount})") },
                )
            }
        }
    }
}

@Composable
private fun FilterGroup(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        content()
    }
}

/** Chips for several values of one kind, any number of which can be on. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Choices(options: List<String>, selected: Set<String>, label: (String) -> String, onToggle: (String) -> Unit) {
    // A value chosen earlier stays on the list even if the collection no longer has one, so it can be switched off.
    val shown = options + selected.filter { it !in options }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        shown.forEach { option ->
            FilterChip(selected = option in selected, onClick = { onToggle(option) }, label = { Text(label(option)) })
        }
    }
}

private fun Set<String>.toggled(value: String): Set<String> = if (value in this) this - value else this + value
