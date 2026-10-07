package app.cardpulse.android.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.cardpulse.android.PokeApp
import app.cardpulse.android.core.FriendsSession
import app.cardpulse.android.core.userMessage

/**
 * Friends, requests, what the user shares and their For Trade marks. All of it lives in the [session], which holds no Android types
 * and is tested on the JVM; this only gives it a scope that ends with the screen's owner and the real server.
 */
class FriendsViewModel(app: Application) : AndroidViewModel(app) {
    private val container = getApplication<PokeApp>().container

    val session = FriendsSession(
        backend = container.repository,
        scope = viewModelScope,
        describe = { it.userMessage() },
    )
}
