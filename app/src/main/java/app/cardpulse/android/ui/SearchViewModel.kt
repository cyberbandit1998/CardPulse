package app.cardpulse.android.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.cardpulse.android.PokeApp
import app.cardpulse.android.core.CatalogSearchSession
import app.cardpulse.android.core.userMessage

/**
 * The search of the whole catalogue. All of it lives in the [session], which holds no Android types and is tested on the JVM; this
 * only gives it a scope that ends with the screen's owner and the real server.
 */
class SearchViewModel(app: Application) : AndroidViewModel(app) {
    private val container = getApplication<PokeApp>().container

    val session = CatalogSearchSession(
        backend = container.repository,
        scope = viewModelScope,
        describe = { it.userMessage() },
    )
}
