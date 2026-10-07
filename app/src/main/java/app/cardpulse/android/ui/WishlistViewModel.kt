package app.cardpulse.android.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.cardpulse.android.PokeApp
import app.cardpulse.android.core.CardDto
import app.cardpulse.android.core.ChecklistCardDto
import app.cardpulse.android.core.CollectionItemDto
import app.cardpulse.android.core.SetDto
import app.cardpulse.android.core.WishlistFilter
import app.cardpulse.android.core.WishlistPriority
import app.cardpulse.android.core.WishlistSort
import app.cardpulse.android.data.wishlist.WishlistItem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The wishlist screen's state, and the heart toggles shown on cards everywhere else. Entries live in Room (see
 * WishlistRepository), so everything here is a view of the database that updates by itself when it changes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WishlistViewModel(app: Application) : AndroidViewModel(app) {
    private val repository = getApplication<PokeApp>().container.wishlistRepository

    private val sortFlow = MutableStateFlow(WishlistSort.RECENT)
    val sort: StateFlow<WishlistSort> = sortFlow.asStateFlow()

    private val filterFlow = MutableStateFlow(WishlistFilter.ALL)
    val filter: StateFlow<WishlistFilter> = filterFlow.asStateFlow()

    /** The entries in the chosen order, narrowed by the chosen filter. Null until the database first answers. */
    val items: StateFlow<List<WishlistItem>?> = combine(sortFlow, filterFlow) { sort, filter -> sort to filter }
        .flatMapLatest { (sort, filter) -> repository.observe(sort, filter) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Every card id on the list, whatever the filter: what the badges and toggles elsewhere go by. */
    val wishlistedIds: StateFlow<Set<String>> =
        repository.cardIds.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    fun isWishlisted(cardId: String?): Boolean = cardId != null && cardId in wishlistedIds.value

    fun setSort(sort: WishlistSort) {
        sortFlow.value = sort
    }

    fun setFilter(filter: WishlistFilter) {
        filterFlow.value = filter
    }

    /** Adds the card, or takes it off when it is already on the list. */
    fun toggle(card: CardDto, priceField: String) = toggleById(card.id) { repository.add(card, priceField) }

    fun toggle(item: CollectionItemDto, priceField: String) {
        val cardId = item.cardId ?: item.card?.id ?: return
        toggleById(cardId) { repository.add(item, priceField) }
    }

    fun toggle(card: ChecklistCardDto, set: SetDto?, priceField: String) =
        toggleById(card.id) { repository.add(card, set, priceField) }

    private fun toggleById(cardId: String, add: suspend () -> Unit) {
        viewModelScope.launch {
            // Asks the database rather than wishlistedIds, which may lag a quick double tap by a frame.
            if (repository.contains(cardId)) repository.remove(cardId) else add()
        }
    }

    fun remove(cardId: String) {
        viewModelScope.launch { repository.remove(cardId) }
    }

    /** Puts back an entry just removed (the snackbar's Undo), with its target price, priority and date. */
    fun restore(item: WishlistItem) {
        viewModelScope.launch { repository.restore(item) }
    }

    /** Saves what the edit dialog was given: a target price in euros (null for none) and a priority. */
    fun update(cardId: String, targetPrice: Double?, priority: WishlistPriority) {
        viewModelScope.launch {
            repository.setTargetPrice(cardId, targetPrice)
            repository.setPriority(cardId, priority)
        }
    }

    /** Marks entries owned from the collection the server sent; called whenever the collection loads or changes. */
    fun syncWithCollection(collection: List<CollectionItemDto>, priceField: String) {
        viewModelScope.launch { repository.syncWithCollection(collection, priceField) }
    }
}
