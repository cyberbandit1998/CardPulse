package io.github.cyberbandit1998.pokemonscanner.data

import android.app.Application
import io.github.cyberbandit1998.pokemonscanner.BuildConfig
import io.github.cyberbandit1998.pokemonscanner.api.HttpClientFactory
import io.github.cyberbandit1998.pokemonscanner.api.PokeApi
import io.github.cyberbandit1998.pokemonscanner.api.SessionHolder
import io.github.cyberbandit1998.pokemonscanner.core.AppJson
import io.github.cyberbandit1998.pokemonscanner.core.CollectionItemDto
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import okhttp3.OkHttpClient
import java.io.File

enum class SessionEvent { EXPIRED }

/** The app's long-lived objects, created once. Kept by hand instead of a DI framework: there are few of them. */
class AppContainer(app: Application) {
    val session = SessionHolder()
    val store = SessionStore(app, TokenCipher())

    private val sessionEventFlow = MutableSharedFlow<SessionEvent>(extraBufferCapacity = 4)
    val sessionEvents: SharedFlow<SessionEvent> = sessionEventFlow.asSharedFlow()

    /** Rows added or changed elsewhere (the scanner), so the collection screen can show them without a refetch. */
    val collectionUpdates = MutableSharedFlow<CollectionItemDto>(extraBufferCapacity = 64)

    val httpClient: OkHttpClient =
        HttpClientFactory.create(session, BuildConfig.DEBUG) { sessionEventFlow.tryEmit(SessionEvent.EXPIRED) }

    val repository = Repository(
        HttpClientFactory.retrofit(httpClient, AppJson).create(PokeApi::class.java),
        session,
    )

    /** Photos taken but not yet sent. Survives the app being closed or killed mid-batch. */
    val pendingScansDir: File = File(app.filesDir, "pending_scans").apply { mkdirs() }
}
