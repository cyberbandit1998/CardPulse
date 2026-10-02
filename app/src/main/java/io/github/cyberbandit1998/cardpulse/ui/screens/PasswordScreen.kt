package io.github.cyberbandit1998.cardpulse.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.cyberbandit1998.cardpulse.ui.AppState
import io.github.cyberbandit1998.cardpulse.ui.Banner

private const val MIN_PASSWORD_LENGTH = 8

/** PokéCollector can require a new password at first sign-in (for example for an account an admin created). */
@Composable
fun PasswordScreen(
    state: AppState,
    onChange: (String) -> Unit,
    onSignOut: () -> Unit,
    onDismissMessage: () -> Unit,
) {
    // Not rememberSaveable: passwords shouldn't be written into the saved-state bundle.
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    val tooShort = first.isNotEmpty() && first.length < MIN_PASSWORD_LENGTH
    val mismatch = second.isNotEmpty() && first != second

    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Choose a new password", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Your PokéCollector account needs a new password before you can continue.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = first,
            onValueChange = { first = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("New password") },
            isError = tooShort,
            supportingText = { Text("At least $MIN_PASSWORD_LENGTH characters.") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            singleLine = true,
        )
        OutlinedTextField(
            value = second,
            onValueChange = { second = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Repeat it") },
            isError = mismatch,
            supportingText = { if (mismatch) Text("The two passwords don't match.") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            singleLine = true,
        )
        state.message?.let { Banner(it, isError = true, onDismiss = onDismissMessage) }
        Button(
            onClick = { onChange(first) },
            enabled = !state.busy && first.length >= MIN_PASSWORD_LENGTH && first == second,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (state.busy) "Saving…" else "Save password") }
        TextButton(onClick = onSignOut, modifier = Modifier.fillMaxWidth()) { Text("Sign out") }
    }
}
