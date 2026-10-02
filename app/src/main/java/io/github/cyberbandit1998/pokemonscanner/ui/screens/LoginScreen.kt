package io.github.cyberbandit1998.pokemonscanner.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun LoginScreen(
    initialServer: String,
    loading: Boolean,
    error: String?,
    onLogin: (String, String, String) -> Unit
) {
    var server by remember { mutableStateOf(initialServer) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("PokéCollector", style = MaterialTheme.typography.headlineLarge)
            Text(
                "Android companion",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedTextField(
                value = server,
                onValueChange = { server = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Server address") },
                placeholder = { Text("https://your-server.example") },
                supportingText = {
                    Text("The https:// address of your own PokéCollector server. It is saved on this phone only.")
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                singleLine = true
            )

            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Username") },
                singleLine = true
            )

            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Password") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                singleLine = true
            )

            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }

            Button(
                onClick = { onLogin(server, username, password) },
                enabled = !loading && server.isNotBlank() && username.isNotBlank() && password.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (loading) "Signing in…" else "Sign in")
            }
        }
    }
}
