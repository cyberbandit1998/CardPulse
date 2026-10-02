package io.github.cyberbandit1998.pokemonscanner.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.cyberbandit1998.pokemonscanner.ui.AppState
import io.github.cyberbandit1998.pokemonscanner.ui.Banner

/**
 * The first screen: where to find the server, and who you are. There is no built-in server address; the one
 * you type is stored on this phone only.
 */
@Composable
fun LoginScreen(
    state: AppState,
    onTest: (server: String) -> Unit,
    onSignIn: (server: String, username: String, password: String) -> Unit,
    onDismissMessage: () -> Unit,
) {
    var server by rememberSaveable(state.serverUrl) { mutableStateOf(state.serverUrl) }
    var username by rememberSaveable { mutableStateOf("") }
    // Not rememberSaveable: a password shouldn't be written into the saved-state bundle.
    var password by remember { mutableStateOf("") }

    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("PokéCollector", style = MaterialTheme.typography.headlineLarge)
        Text(
            "An unofficial Android companion for your own self-hosted PokéCollector server.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = server,
            onValueChange = { server = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Server address") },
            placeholder = { Text("https://your-server.example") },
            supportingText = { Text("The https:// address of your own PokéCollector server. It is saved on this phone only.") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            singleLine = true,
        )
        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Username") },
            singleLine = true,
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Password") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            singleLine = true,
        )

        state.message?.let { Banner(it, isError = !it.startsWith("Connected"), onDismiss = onDismissMessage) }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = { onTest(server) },
                enabled = !state.busy && server.isNotBlank(),
                modifier = Modifier.weight(1f),
            ) { Text("Test connection") }
            Button(
                onClick = { onSignIn(server, username, password) },
                enabled = !state.busy && server.isNotBlank(),
                modifier = Modifier.weight(1f),
            ) {
                if (state.busy) {
                    CircularProgressIndicator(Modifier.padding(end = 8.dp).size(18.dp), strokeWidth = 2.dp)
                }
                Text("Sign in")
            }
        }

        Text(
            "If your server has no sign-in (single-user mode), leave the username and password empty.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
