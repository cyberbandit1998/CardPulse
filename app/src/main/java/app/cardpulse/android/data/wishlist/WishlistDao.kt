package app.cardpulse.android.data.wishlist

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.cardpulse.android.core.WishlistPriority
import kotlinx.coroutines.flow.Flow

@Dao
interface WishlistDao {
    /** Adds the card; a card already on the list keeps its entry (and its target price, priority and date). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(item: WishlistItem): Long

    @Query("DELETE FROM wishlist WHERE cardId = :cardId")
    suspend fun deleteByCardId(cardId: String)

    @Query("SELECT * FROM wishlist WHERE cardId = :cardId LIMIT 1")
    suspend fun find(cardId: String): WishlistItem?

    /**
     * The list in the order [sort] names (a WishlistSort key: RECENT, NAME, SET or PRICE), filtered on the owned column when
     * [owned] isn't null. Ties, and RECENT itself, fall back to the newest first; cards without a price sort last by price.
     */
    @Query(
        """
        SELECT * FROM wishlist
        WHERE (:owned IS NULL OR isOwned = :owned)
        ORDER BY
            CASE WHEN :sort = 'NAME' THEN cardName END COLLATE NOCASE ASC,
            CASE WHEN :sort = 'SET' THEN setName END COLLATE NOCASE ASC,
            CASE WHEN :sort = 'SET' THEN collectorNumber END COLLATE NOCASE ASC,
            CASE WHEN :sort = 'PRICE' THEN IFNULL(cachedPrice, -1) END DESC,
            dateAdded DESC
        """,
    )
    fun observe(sort: String, owned: Boolean?): Flow<List<WishlistItem>>

    /** Every card id on the list, for the badges and toggles elsewhere in the app. */
    @Query("SELECT cardId FROM wishlist")
    fun observeCardIds(): Flow<List<String>>

    @Query("UPDATE wishlist SET targetPrice = :targetPrice WHERE cardId = :cardId")
    suspend fun updateTargetPrice(cardId: String, targetPrice: Double?)

    @Query("UPDATE wishlist SET priority = :priority WHERE cardId = :cardId")
    suspend fun updatePriority(cardId: String, priority: WishlistPriority)

    /** Marks the given cards owned and every other one not owned. Never deletes anything. */
    @Query("UPDATE wishlist SET isOwned = (cardId IN (:ownedCardIds))")
    suspend fun syncOwned(ownedCardIds: List<String>)

    @Query("UPDATE wishlist SET isOwned = 0")
    suspend fun clearOwned()

    @Query("UPDATE wishlist SET cachedPrice = :price WHERE cardId = :cardId")
    suspend fun updateCachedPrice(cardId: String, price: Double)
}
