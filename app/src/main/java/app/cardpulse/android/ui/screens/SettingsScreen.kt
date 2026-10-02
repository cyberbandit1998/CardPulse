package app.cardpulse.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cardpulse.android.BuildConfig
import app.cardpulse.android.ui.AccentTextButton
import app.cardpulse.android.ui.AppState
import app.cardpulse.android.ui.ScanState

@Composable
fun SettingsScreen(
    app: AppState,
    scan: ScanState,
    onBack: () -> Unit,
    onSignOut: () -> Unit,
    onSavePhotos: (Boolean) -> Unit,
    onLookUpPrices: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmSignOut by remember { mutableStateOf(false) }

    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Text("Settings", style = MaterialTheme.typography.titleLarge)
        }
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Section("Server") {
                Fact("Address", app.serverUrl)
                Fact("Signed in as", app.user?.username ?: if (app.noLogin) "no sign-in (single-user server)" else "—")
                OutlinedButton(onClick = { confirmSignOut = true }) { Text("Sign out or change server") }
            }

            Section("Adding cards") {
                ToggleRow(
                    "Keep my photo with every card",
                    "Otherwise your photo is kept only for cards that have no official artwork.",
                    scan.prefs.savePhotos,
                    onSavePhotos,
                )
                ToggleRow(
                    "Look up prices after adding a new card",
                    "Asks your server to fetch prices for new cards, then shows them. Needs an admin account on the server.",
                    scan.prefs.lookUpPrices,
                    onLookUpPrices,
                )
            }

            Section("From your PokéCollector account") {
                Text(
                    "These come from your account's settings on the server. Change them in PokéCollector, then tap Refresh on the Home tab.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Fact("Currency", app.prefs.currency)
                Fact("Price used for values", app.prefs.priceField.removePrefix("price_"))
                Fact("Prefer my own photos", if (app.prefs.preferOwnPhotos) "yes" else "no (official art first)")
            }

            Section("About") {
                Fact("Version", "${BuildConfig.VERSION_NAME}${if (BuildConfig.DEBUG) " (debug build)" else ""}")
                Text(
                    "CardPulse is an unofficial companion for the open-source PokéCollector server. Your sign-in token is stored encrypted on this phone, " +
                        "and photos are sent only to the server address you entered.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("Sign out?") },
            text = { Text("You'll need to sign in again. Photos waiting to be sent stay on this phone. Cached card images are cleared.") },
            confirmButton = { AccentTextButton(onClick = { confirmSignOut = false; onSignOut() }) { Text("Sign out") } },
            dismissButton = { AccentTextButton(onClick = { confirmSignOut = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun Fact(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun ToggleRow(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
