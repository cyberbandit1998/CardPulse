package app.cardpulse.android.core

import java.util.Locale
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The wishlist as the server sends it (`wishlist.json` is what its own router returned) and the collection of `collection.json`. */
private val wishlist = Fixtures.decode<List<WishlistItemDto>>("wishlist")
private val collection = Fixtures.decode<List<CollectionItemDto>>("collection")
private val usd = MoneyFormatter("USD", 1.1, Locale.US)

private fun List<WishlistEntry>.ids(): List<String> = map { it.cardId }

private fun entries(
    items: List<WishlistItemDto> = wishlist,
    owned: List<CollectionItemDto> = collection,
    loaded: Boolean = true,
    priceField: String = "price_trend",
    priorities: Map<String, WishlistPriority> = emptyMap(),
): List<WishlistEntry> = items.entries(owned, loaded, priceField, priorities)

private fun entry(cardId: String): WishlistEntry = entries().first { it.cardId == cardId }

class WishlistDecodeTest {
    @Test
    fun theServersListDecodesWithItsCardsAndSets() {
        assertEquals(6, wishlist.size)
        val first = wishlist.first()
        assertEquals("sv3-125_en", first.cardId)
        assertEquals(2, first.quantity)
        assertEquals("Charizard ex", first.card?.name)
        assertEquals("Obsidian Flames", first.card?.setRef?.name)
        assertEquals("125", first.card?.number)
    }

    @Test
    fun theAlertsComeAcrossAsTheServerHasThem() {
        val byCard = wishlist.associateBy { it.cardId }
        assertEquals(7.5, byCard.getValue("sv3-125_en").priceAlertBelow!!, 0.0)
        assertNull(byCard.getValue("sv3-125_en").priceAlertAbove)
        assertEquals(9.0, byCard.getValue("sv1-198_en").priceAlertAbove!!, 0.0)
        assertNull(byCard.getValue("sv2-001_en").priceAlertBelow)
    }

    @Test
    fun aCardAddedTwiceHasItsQuantityRaisedByTheServer() {
        val first = Fixtures.decode<WishlistItemDto>("wishlist_added")
        val again = Fixtures.decode<WishlistItemDto>("wishlist_added_again")
        assertEquals(1, first.quantity)
        assertEquals(2, again.quantity)
        assertEquals(first.id, again.id)
    }

    @Test
    fun aClearedTargetIsNullAndASetOneIsTheNumber() {
        assertEquals(0.25, Fixtures.decode<WishlistItemDto>("wishlist_target_set").priceAlertBelow!!, 0.0)
        assertNull(Fixtures.decode<WishlistItemDto>("wishlist_target_cleared").priceAlertBelow)
    }

    @Test
    fun aRowWithNoCardOrFieldsStillDecodes() {
        val sparse = AppJson.decodeFromString(WishlistItemDto.serializer(), """{"id": 3, "card_id": "x-1_en", "surprise": [1, 2]}""")
        assertEquals(1, sparse.quantity)
        assertNull(sparse.card)
        assertEquals("Unnamed card", entries(listOf(sparse)).single().name)
    }
}

class WishlistEntryTest {
    @Test
    fun copiesAreCountedByTheCardsOwnIdSoAnotherLanguageIsAnotherCard() {
        val copies = entries().associate { it.cardId to it.copies }
        assertEquals(2, copies["sv3-125_en"]) // two rows of one copy each
        assertEquals(1, copies["sv3-125_de"])
        assertEquals(2, copies["sv1-198_en"])
        assertEquals(0, copies["sv3-223_en"])
        assertEquals(0, copies["sv2-001_en"])
        // Only the German copy is owned: the English card is still missing.
        val onlyGerman = collection.filter { it.cardId == "sv3-125_de" }
        assertEquals(0, entries(owned = onlyGerman).first { it.cardId == "sv3-125_en" }.copies)
    }

    @Test
    fun ownedAndMissingAreSaidInWords() {
        assertEquals("Owned ×2", entry("sv3-125_en").statusText)
        assertEquals("Owned", entry("sv3-125_de").statusText)
        assertEquals("Missing", entry("sv3-223_en").statusText)
        assertTrue(entry("sv3-125_en").owned)
        assertFalse(entry("sv3-125_en").missing)
        assertTrue(entry("sv2-001_en").missing)
    }

    @Test
    fun beforeTheCollectionHasLoadedNothingIsSaidAboutOwnership() {
        val unknown = entries(owned = emptyList(), loaded = false)
        assertTrue(unknown.all { it.copies == null })
        assertTrue(unknown.all { it.statusText == "" })
        assertTrue(unknown.none { it.owned })
        assertTrue(unknown.none { it.missing }) // not "missing": not known
    }

    @Test
    fun owningACopyDoesNotTakeTheCardOffTheListOrChangeIt() {
        // The entry is still there, owned and listed: the wishlist is only ever edited by the user.
        val owned = entry("sv3-125_en")
        assertEquals(2, owned.item.quantity)
        assertTrue(owned.owned)
        assertEquals(wishlist.size, entries().size)
    }

    @Test
    fun theSetNumberAndRarityAreTheCardsOwn() {
        val charizard = entry("sv3-125_en")
        assertEquals("Obsidian Flames", charizard.setName)
        assertEquals("#125", charizard.numberText)
        assertEquals("Double Rare", charizard.rarity)
        assertEquals("Obsidian Flames · #125 · Double Rare", charizard.subtitle)
        assertEquals("Obsidian-Flammen", entry("sv3-125_de").setName)
    }

    @Test
    fun theCardsPriceIsTheOneTheUserChoseAndAMissingPriceIsNone() {
        assertEquals(8.5, entry("sv3-125_en").priceEur, 0.0) // price_trend
        assertEquals(61.0, entry("sv3-223_en").priceEur, 0.0)
        assertEquals(0.12, entry("sv2-001_en").priceEur, 0.0)
        assertFalse(entry("sv2-003_en").hasPrice) // the market has no figure for it
        assertEquals(0.0, entry("sv2-003_en").priceEur, 0.0)
        // The same choice as everywhere else: another price field changes it.
        assertEquals(9.1, entries(priceField = "price_market").first { it.cardId == "sv3-125_en" }.priceEur, 0.0)
    }

    @Test
    fun theTargetIsTheServersLowerAlertAndItIsReachedWhenThePriceIsNoHigher() {
        val charizard = entry("sv3-125_en") // target 7.5, price 8.5
        assertEquals(7.5, charizard.targetEur!!, 0.0)
        assertFalse(charizard.atTarget)
        val illustration = entry("sv3-223_en") // target 70, price 61
        assertTrue(illustration.atTarget)
        assertNull(entry("sv2-001_en").targetEur)
        assertFalse(entry("sv2-001_en").atTarget)
        // The other alert (above) is not a target.
        assertNull(entry("sv1-198_en").targetEur)
    }

    @Test
    fun aTargetIsNeverReachedByACardWithNoPrice() {
        val noPrice = wishlist.first { it.cardId == "sv2-003_en" }.copy(priceAlertBelow = 5.0)
        val row = entries(listOf(noPrice)).single()
        assertEquals(5.0, row.targetEur!!, 0.0)
        assertFalse(row.atTarget)
    }

    @Test
    fun aZeroTargetMeansNone() {
        val zero = wishlist.first().copy(priceAlertBelow = 0.0)
        assertNull(entries(listOf(zero)).single().targetEur)
    }

    @Test
    fun theLocalPriorityIsAttachedByCardId() {
        val rows = entries(priorities = mapOf("sv3-223_en" to WishlistPriority.HIGH))
        assertEquals(WishlistPriority.HIGH, rows.first { it.cardId == "sv3-223_en" }.priority)
        assertNull(rows.first { it.cardId == "sv2-001_en" }.priority)
    }

    @Test
    fun aScreenReaderIsToldEverythingInOneBreath() {
        val rows = entries(priorities = mapOf("sv3-223_en" to WishlistPriority.HIGH))
        assertEquals(
            "Charizard ex, Obsidian Flames, number 125, Double Rare, price $9.35, owned 2, wants 2, target price $8.25",
            rows.first { it.cardId == "sv3-125_en" }.describe(usd),
        )
        assertEquals(
            "Charizard ex, Obsidian Flames, number 223, Special Illustration Rare, price $67.10, missing, " +
                "target price $77.00, reached, high priority",
            rows.first { it.cardId == "sv3-223_en" }.describe(usd),
        )
        assertEquals(
            "Jumpluff, Paldea Evolved, number 003, Rare, no price, missing",
            rows.first { it.cardId == "sv2-003_en" }.describe(usd),
        )
    }
}

class WishlistListTest {
    @Test
    fun recentIsNewestFirst() {
        assertEquals(
            listOf("sv3-125_en", "sv3-223_en", "sv2-001_en", "sv2-003_en", "sv3-125_de", "sv1-198_en"),
            entries().shuffled(java.util.Random(3)).ordered(WishlistOrder.RECENT).ids(),
        )
    }

    @Test
    fun aRowWithoutADateGoesLastAndTiesFollowTheId() {
        val base = wishlist.first()
        val items = listOf(
            base.copy(id = 1, cardId = "a", createdAt = null),
            base.copy(id = 2, cardId = "b", createdAt = "2026-10-01T00:00:00"),
            base.copy(id = 3, cardId = "c", createdAt = "2026-10-01T00:00:00Z"), // the same moment, written with a Z
        )
        assertEquals(listOf("c", "b", "a"), entries(items).ordered(WishlistOrder.RECENT).ids())
    }

    @Test
    fun nameIsAToZWhateverTheCaseThenBySetAndNumber() {
        assertEquals(
            listOf("sv3-125_en", "sv3-223_en", "sv3-125_de", "sv2-001_en", "sv2-003_en", "sv1-198_en"),
            entries().shuffled(java.util.Random(5)).ordered(WishlistOrder.NAME).ids(),
        )
        val base = wishlist.first().card!!
        val items = listOf(
            WishlistItemDto(1, "z", card = base.copy(id = "z", name = "zubat")),
            WishlistItemDto(2, "a", card = base.copy(id = "a", name = "Abra")),
            WishlistItemDto(3, "m", card = base.copy(id = "m", name = "MEW")),
        )
        assertEquals(listOf("a", "m", "z"), entries(items).ordered(WishlistOrder.NAME).ids())
    }

    @Test
    fun setGroupsTheCardsOfASetInCardNumberOrder() {
        val base = wishlist.first().card!!
        fun item(id: Int, name: String, set: String, number: String) =
            WishlistItemDto(id, "c$id", card = base.copy(id = "c$id", name = name, number = number, setRef = SetDto(id = set, name = set)))
        val items = listOf(
            item(1, "Abra", "Zeta Set", "9"),
            item(2, "Zubat", "alpha Set", "10"),
            item(3, "Mew", "alpha Set", "9"),
            item(4, "Eevee", "Zeta Set", "100"),
        )
        // alpha Set (#9 before #10), then Zeta Set (#9 before #100).
        assertEquals(listOf("c3", "c2", "c1", "c4"), entries(items).ordered(WishlistOrder.SET).ids())
        // By name it is Abra, Eevee, Mew, Zubat: not the same order, so the two sorts are really different.
        assertEquals(listOf("c1", "c4", "c3", "c2"), entries(items).ordered(WishlistOrder.NAME).ids())
    }

    @Test
    fun priceIsMostExpensiveFirstWithTheUnpricedLast() {
        assertEquals(
            listOf("sv3-223_en", "sv3-125_en", "sv3-125_de", "sv1-198_en", "sv2-001_en", "sv2-003_en"),
            entries().shuffled(java.util.Random(9)).ordered(WishlistOrder.PRICE).ids(),
        )
    }

    @Test
    fun missingShowsOnlyTheCardsYouLackAndOwnedOnlyTheOnesYouHave() {
        val all = entries()
        assertEquals(setOf("sv3-223_en", "sv2-001_en", "sv2-003_en"), all.filtered(WishlistFilter.MISSING).ids().toSet())
        assertEquals(setOf("sv3-125_en", "sv3-125_de", "sv1-198_en"), all.filtered(WishlistFilter.OWNED).ids().toSet())
        assertEquals(6, all.filtered(WishlistFilter.ALL).size)
    }

    @Test
    fun theCountsAddUp() {
        val counts = entries().counts()
        assertEquals(WishlistCounts(all = 6, missing = 3, owned = 3), counts)
        assertEquals(3, counts.of(WishlistFilter.MISSING))
        assertEquals(6, counts.of(WishlistFilter.ALL))
        // Until the collection loads there is no telling.
        assertEquals(WishlistCounts(all = 6, missing = 0, owned = 0), entries(owned = emptyList(), loaded = false).counts())
    }

    @Test
    fun theFiltersAndTheOrderWorkTogether() {
        val missingByPrice = entries().filtered(WishlistFilter.MISSING).ordered(WishlistOrder.PRICE)
        assertEquals(listOf("sv3-223_en", "sv2-001_en", "sv2-003_en"), missingByPrice.ids())
    }
}

class WishlistEditsTest {
    @Test
    fun anAddedCardGoesFirstAndReplacesItsOldRow() {
        val added = wishlist.last().copy(id = 99, cardId = "new-1_en")
        assertEquals("new-1_en", wishlist.withAdded(added).first().cardId)
        assertEquals(7, wishlist.withAdded(added).size)
        val again = wishlist[2].copy(quantity = 5)
        val after = wishlist.withAdded(again)
        assertEquals(6, after.size)
        assertEquals(5, after.first().quantity)
        assertEquals(1, after.count { it.cardId == again.cardId })
    }

    @Test
    fun anUpdatedRowStaysWhereItWasAndAnUnknownOneChangesNothing() {
        val updated = wishlist[3].copy(priceAlertBelow = 1.0)
        val after = wishlist.withUpdated(updated)
        assertEquals(1.0, after[3].priceAlertBelow!!, 0.0)
        assertEquals(wishlist.map { it.id }, after.map { it.id })
        assertEquals(wishlist, wishlist.withUpdated(updated.copy(id = 12345)))
    }

    @Test
    fun aCardIsTakenOffByItsId() {
        val after = wishlist.withoutCard("sv3-223_en")
        assertEquals(5, after.size)
        assertTrue(after.none { it.cardId == "sv3-223_en" })
        assertEquals(wishlist.cardIds() - "sv3-223_en", after.cardIds())
    }

    @Test
    fun theIdsOfTheListAreItsCards() {
        assertEquals(
            setOf("sv3-125_en", "sv3-223_en", "sv2-001_en", "sv2-003_en", "sv3-125_de", "sv1-198_en"),
            wishlist.cardIds(),
        )
    }
}

class WishlistBodiesTest {
    @Test
    fun aTargetIsSentAsTheNumberOfEuros() {
        assertEquals("""{"price_alert_below":7.5}""", AppJson.encodeToString(JsonObject.serializer(), wishlistTargetBody(7.5)))
    }

    @Test
    fun aClearedTargetIsSentAsAnExplicitNullBecauseTheServerOnlyChangesWhatIsNamed() {
        // AppJson sends no nulls for classes, so a request class could not say "clear it"; this body does.
        assertEquals("""{"price_alert_below":null}""", AppJson.encodeToString(JsonObject.serializer(), wishlistTargetBody(null)))
    }

    @Test
    fun aQuantityBodyNamesOnlyTheQuantity() {
        assertEquals("""{"quantity":2}""", AppJson.encodeToString(JsonObject.serializer(), wishlistQuantityBody(2)))
    }

    @Test
    fun anAddRequestNamesTheCardAndOneCopy() {
        assertEquals(
            """{"card_id":"sv3-125_en","quantity":1}""",
            AppJson.encodeToString(WishlistAddRequest.serializer(), WishlistAddRequest("sv3-125_en")),
        )
    }
}

class WishlistPrioritiesTest {
    private val account = WishlistPriorities.accountKey("https://cards.example.com/", "ash")

    @Test
    fun anAccountIsAServerAndAUser() {
        assertEquals("https://cards.example.com/#ash", account)
        assertEquals("https://cards.example.com/#", WishlistPriorities.accountKey(" https://cards.example.com/ ", null))
    }

    @Test
    fun levelsSurviveBeingWrittenAndRead() {
        val levels = mapOf("sv3-125_en" to WishlistPriority.HIGH, "sv2-001_en" to WishlistPriority.LOW, "x" to WishlistPriority.MEDIUM)
        assertEquals(levels, WishlistPriorities.decode(account, WishlistPriorities.encode(account, levels)))
    }

    @Test
    fun anotherAccountsLevelsAreNotUsed() {
        val text = WishlistPriorities.encode(account, mapOf("a" to WishlistPriority.HIGH))
        assertEquals(emptyMap<String, WishlistPriority>(), WishlistPriorities.decode(WishlistPriorities.accountKey("https://other.example.com/", "ash"), text))
        assertEquals(emptyMap<String, WishlistPriority>(), WishlistPriorities.decode(WishlistPriorities.accountKey("https://cards.example.com/", "misty"), text))
    }

    @Test
    fun nothingOrJunkIsNoLevels() {
        assertEquals(emptyMap<String, WishlistPriority>(), WishlistPriorities.decode(account, null))
        assertEquals(emptyMap<String, WishlistPriority>(), WishlistPriorities.decode(account, ""))
        assertEquals(emptyMap<String, WishlistPriority>(), WishlistPriorities.decode(account, "not json {"))
        assertEquals(emptyMap<String, WishlistPriority>(), WishlistPriorities.decode(account, "[1,2]"))
    }

    @Test
    fun aLevelThisVersionDoesNotKnowIsDropped() {
        val text = """{"account":"$account","levels":{"a":"high","b":"urgent","c":"low"}}"""
        assertEquals(mapOf("a" to WishlistPriority.HIGH, "c" to WishlistPriority.LOW), WishlistPriorities.decode(account, text))
    }

    @Test
    fun aRemovedCardKeepsNoLevel() {
        val levels = mapOf("a" to WishlistPriority.HIGH, "b" to WishlistPriority.LOW)
        assertEquals(mapOf("b" to WishlistPriority.LOW), WishlistPriorities.keepOnly(levels, setOf("b", "z")))
    }

    @Test
    fun theKeysAreStableBecauseTheyAreWhatIsSaved() {
        assertEquals(listOf("low", "medium", "high"), WishlistPriority.entries.map { it.key })
        assertEquals(WishlistPriority.MEDIUM, WishlistPriority.fromKey("medium"))
        assertNull(WishlistPriority.fromKey(null))
        assertNull(WishlistPriority.fromKey("High")) // keys are lower case
    }
}

class CopiesByCardIdTest {
    @Test
    fun copiesAddUpPerCardAndSkipEmptyRows() {
        val base = collection.first()
        val rows = listOf(
            base.copy(id = 1, cardId = "a", quantity = 2),
            base.copy(id = 2, cardId = "a", quantity = 3, condition = "LP"),
            base.copy(id = 3, cardId = "b", quantity = 0),
            base.copy(id = 4, cardId = null, quantity = 9),
        )
        assertEquals(mapOf("a" to 5), rows.copiesByCardId())
    }
}
