package io.github.cyberbandit1998.pokemonscanner.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import coil3.compose.AsyncImage
import io.github.cyberbandit1998.pokemonscanner.api.ScanMatch
import io.github.cyberbandit1998.pokemonscanner.ui.AppState
import java.io.File

@Composable
fun ScanScreen(
    state: AppState,
    onScan: (Uri) -> Unit,
    onAdd: (ScanMatch) -> Unit,
    onClear: () -> Unit
) {
    val context = LocalContext.current
    var pendingUri by remember { mutableStateOf<Uri?>(null) }

    val takePicture = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { ok ->
        if (ok) pendingUri?.let(onScan)
    }

    val cameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            val uri = createTempUri(context)
            pendingUri = uri
            takePicture.launch(uri)
        }
    }

    fun startCamera() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            val uri = createTempUri(context)
            pendingUri = uri
            takePicture.launch(uri)
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Scan a card", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Take a clear photo. Your PokéCollector server handles recognition.",
            style = MaterialTheme.typography.bodyMedium
        )

        Button(
            onClick = { startCamera() },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.CameraAlt, null)
            Spacer(Modifier.width(8.dp))
            Text("Take card photo")
        }

        state.scanImageUri?.let {
            AsyncImage(
                model = it,
                contentDescription = "Scanned card",
                modifier = Modifier.fillMaxWidth().height(280.dp),
                contentScale = ContentScale.Fit
            )
        }

        if (state.scanMatches.isNotEmpty()) {
            Text("Matches", style = MaterialTheme.typography.titleLarge)

            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(state.scanMatches) { match ->
                    ElevatedCard(Modifier.width(190.dp)) {
                        Column(Modifier.padding(12.dp)) {
                            AsyncImage(
                                model = match.image ?: match.imagesSmall ?: match.imagesLarge,
                                contentDescription = match.name,
                                modifier = Modifier.fillMaxWidth().aspectRatio(0.716f),
                                contentScale = ContentScale.Fit
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(match.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                listOfNotNull(match.setAbbreviation, match.number)
                                    .joinToString(" "),
                                style = MaterialTheme.typography.bodySmall
                            )
                            Spacer(Modifier.height(8.dp))
                            Button(
                                onClick = { onAdd(match) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Add to collection")
                            }
                        }
                    }
                }
            }

            TextButton(onClick = onClear) { Text("Scan another") }
        }

        state.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
        }
    }
}

private fun createTempUri(context: android.content.Context): Uri {
    val file = File.createTempFile("poke-scan-", ".jpg", context.cacheDir)
    return FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file
    )
}
