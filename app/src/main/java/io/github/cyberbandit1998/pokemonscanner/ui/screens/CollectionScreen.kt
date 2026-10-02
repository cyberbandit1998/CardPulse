package io.github.cyberbandit1998.pokemonscanner.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.cyberbandit1998.pokemonscanner.api.CollectionItem
import io.github.cyberbandit1998.pokemonscanner.ui.AppState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

@Composable
fun CollectionScreen(
    state: AppState,
    onRefresh: () -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Collection", style = MaterialTheme.typography.headlineMedium)
            TextButton(onClick = onRefresh) { Text("Refresh") }
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(145.dp),
            contentPadding = PaddingValues(10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(state.collection, key = { it.id }) { item ->
                ElevatedCard {
                    Column {
                        CardArtwork(
                            item = item,
                            serverUrl = state.serverUrl,
                            token = state.token,
                            modifier = Modifier.fillMaxWidth().aspectRatio(0.716f)
                        )
                        Column(Modifier.padding(10.dp)) {
                            Text(
                                item.card?.name ?: "Unknown card",
                                maxLines = 2,
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                "Qty ${item.quantity} • ${item.condition ?: ""}",
                                style = MaterialTheme.typography.bodySmall
                            )
                            if (item.hasScanPhoto) {
                                Text(
                                    "Your scanned photo",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CardArtwork(
    item: CollectionItem,
    serverUrl: String,
    token: String?,
    modifier: Modifier = Modifier
) {
    var privatePhoto by remember(item.id, item.hasScanPhoto) {
        mutableStateOf<android.graphics.Bitmap?>(null)
    }
    var privatePhotoFailed by remember(item.id, item.hasScanPhoto) {
        mutableStateOf(false)
    }

    LaunchedEffect(item.id, item.hasScanPhoto, serverUrl, token) {
        privatePhoto = null
        privatePhotoFailed = false
        if (item.hasScanPhoto && !token.isNullOrBlank()) {
            privatePhoto = withContext(Dispatchers.IO) {
                runCatching {
                    val base = if (serverUrl.endsWith("/")) serverUrl else "$serverUrl/"
                    val request = Request.Builder()
                        .url("${base}api/collection/${item.id}/photo")
                        .header("Authorization", "Bearer $token")
                        .build()
                    OkHttpClient().newCall(request).execute().use { response ->
                        if (!response.isSuccessful) error("Photo request failed")
                        val bytes = response.body.bytes()
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    }
                }.getOrElse {
                    privatePhotoFailed = true
                    null
                }
            }
        }
    }

    when {
        privatePhoto != null -> Image(
            bitmap = privatePhoto!!.asImageBitmap(),
            contentDescription = item.card?.name,
            modifier = modifier,
            contentScale = ContentScale.Crop
        )
        else -> AsyncImage(
            model = item.card?.imagesSmall ?: item.card?.imagesLarge,
            contentDescription = item.card?.name,
            modifier = modifier,
            contentScale = ContentScale.Crop
        )
    }
}
