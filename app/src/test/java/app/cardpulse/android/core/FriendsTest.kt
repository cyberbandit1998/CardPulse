package app.cardpulse.android.core

import java.io.IOException
import java.util.Locale
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

/**
 * Friends as the patched server sends it: every `friends*.json` and `friend_*.json` fixture is what the server's own router
 * returned for a seeded database (see scratchpad/gen_friends_fixtures.py). "ash" is the signed-in user, with the collection of
 * `collection.json` and the wishlist of `wishlist.json`.
 */
private val collection = Fixtures.decode<List<CollectionItemDto>>("collection")
private val wishlist = Fixtures.decode<List<WishlistItemDto>>("wishlist")
private val usd = MoneyFormatter("USD", 1.1, Locale.US)
private val mine = collection.copiesByCardId()

private fun httpError(code: Int, body: String = """{"detail":"nope"}""") =
    HttpException(Response.error<Any>(code, body.toResponseBody("application/json".toMediaType())))

private fun misty(): List<FriendCard> =
    Fixtures.decode<List<CollectionItemDto>>("friend_collection_misty").collectionCards(mine, "price_trend")

private fun mistyWishlist(): List<FriendCard> =
    Fixtures.decode<List<WishlistItemDto>>("friend_wishlist_misty").wishlistCards(mine, "price_trend")

private fun mistyTrade(): List<FriendCard> =
    Fixtures.decode<List<TradeItemDto>>("friend_trade_list_misty").tradeCards(mine, "price_trend")

private val match = Fixtures.decode<TradeMatchDto>("friend_trade_match_misty")

class FriendsDecodeTest {
    @Test
    fun theUsersOwnSideDecodes() {
        val me = Fixtures.decode<FriendsMeDto>("friends_me")
        assertEquals(1, me.version)
        assertEquals("ash", me.user.username)
        assertEquals(25, me.user.avatarId)
        assertEquals("K7MQ9-XWD3H", me.inviteCode)
        assertEquals(Sharing(), me.sharing.toSharing())
        assertEquals(FriendCountsDto(friends = 3, incoming = 1, outgoing = 1, forTradeCards = 3), me.counts)
    }

    @Test
    fun theOverviewHasFriendsWithWhatEachSharesAndBothKindsOfRequest() {
        val overview = Fixtures.decode<FriendsOverviewDto>("friends_overview")
        assertEquals(listOf("brock", "gary", "misty"), overview.friends.map { it.username })
        val byName = overview.friends.associateBy { it.username }
        assertEquals(SharedFlagsDto(collection = false, wishlist = false, trade = true), byName.getValue("brock").shares)
        assertEquals(SharedFlagsDto(), byName.getValue("gary").shares)
        assertEquals(SharedFlagsDto(true, true, true), byName.getValue("misty").shares)
        assertEquals("2026-10-05T12:00:00", byName.getValue("misty").since)
        assertEquals(listOf("dawn"), overview.incoming.map { it.user.username })
        assertEquals(listOf("may"), overview.outgoing.map { it.user.username })
        assertEquals(4, overview.incoming.single().id)
    }

    @Test
    fun aFriendsCollectionDecodesAsCollectionRowsWithTheirTradeCounts() {
        val rows = Fixtures.decode<List<CollectionItemDto>>("friend_collection_misty")
        assertEquals(listOf("Miraidon ex", "Skiploom", "Hoppip", "Charizard ex"), rows.map { it.card?.name })
        val hoppip = rows.first { it.cardId == "sv2-001_en" }
        assertEquals(3, hoppip.quantity)
        assertEquals(2, hoppip.forTradeQuantity)
        assertEquals("Paldea Evolved", hoppip.card?.setRef?.name)
        // What was paid is not part of what a friend shares.
        assertTrue(rows.all { it.purchasePrice == null && it.addedAt == null && !it.hasScanPhoto })
    }

    @Test
    fun theUsersOwnCollectionHasNoTradeCountOnItsRows() {
        assertTrue(collection.all { it.forTradeQuantity == null })
    }

    @Test
    fun aFriendsWishlistDecodesAsWishlistRowsWithoutTargetPrices() {
        val rows = Fixtures.decode<List<WishlistItemDto>>("friend_wishlist_misty")
        assertEquals(listOf("sv1-198_en", "sv3-001_en", "sv2-002_en"), rows.map { it.cardId })
        assertEquals(listOf(1, 2, 1), rows.map { it.quantity })
        assertTrue(rows.all { it.priceAlertBelow == null && it.priceAlertAbove == null })
    }

    @Test
    fun aTradeMatchHasBothHalvesAndSaysWhatCouldBeWorkedOut() {
        assertEquals(listOf("sv3-223_en", "sv2-001_en"), match.theyHaveYouWant.map { it.cardId })
        assertEquals(listOf(1, 2), match.theyHaveYouWant.map { it.quantity })
        assertEquals(listOf("sv1-198_en", "sv3-001_en"), match.youHaveTheyWant.map { it.cardId })
        assertEquals(listOf(1, 2), match.youHaveTheyWant.map { it.wantedQuantity })
        assertEquals("Reverse Holo", match.youHaveTheyWant.last().variant)
        assertTrue(match.canSeeTheirTradeList && match.canSeeTheirWishlist)
        assertEquals(4, match.total)
    }

    @Test
    fun aFriendWhoSharesOnlyTheirTradeListGivesOnlyTheFirstHalf() {
        val brock = Fixtures.decode<TradeMatchDto>("friend_trade_match_brock")
        assertEquals(listOf("Jumpluff"), brock.theyHaveYouWant.map { it.card?.name })
        assertTrue(brock.youHaveTheyWant.isEmpty())
        assertTrue(brock.canSeeTheirTradeList)
        assertFalse(brock.canSeeTheirWishlist)
    }

    @Test
    fun aFriendWhoSharesNothingGivesAnEmptyComparisonThatSaysWhy() {
        val gary = Fixtures.decode<TradeMatchDto>("friend_trade_match_gary")
        assertEquals(0, gary.total)
        assertFalse(gary.canSeeTheirTradeList)
        assertFalse(gary.canSeeTheirWishlist)
    }

    @Test
    fun theOwnTradeListSaysHowManyCopiesAreMarkedAndHeld() {
        val list = Fixtures.decode<OwnTradeListDto>("friends_own_trade_list")
        assertEquals(mapOf(3 to 2, 4 to 1), list.toMarks())
        assertEquals(listOf(4, 2), list.items.map { it.owned })
    }

    @Test
    fun requestsAnswerPendingOrAcceptedAtOnce() {
        val pending = Fixtures.decode<FriendRequestResultDto>("friends_request_pending")
        val accepted = Fixtures.decode<FriendRequestResultDto>("friends_request_accepted_at_once")
        assertFalse(pending.accepted)
        assertTrue(accepted.accepted)
        assertEquals("serena", pending.user.username)
        assertEquals("Request sent to serena. They will see it on their Friends screen.", pending.message())
        assertEquals("You and dawn are friends now.", accepted.message())
    }

    @Test
    fun anAcceptedRequestIsAFriendWithNothingSharedYet() {
        val wren = Fixtures.decode<FriendDto>("friends_accept")
        assertEquals("wren", wren.username)
        assertEquals(SharedFlagsDto(), wren.shares)
    }

    @Test
    fun theSharingAndInviteCodeAnswersDecode() {
        assertEquals(Sharing(ShareLevel.FRIENDS, ShareLevel.PRIVATE, ShareLevel.PUBLIC), Fixtures.decode<SharingDto>("friends_sharing_set").toSharing())
        assertEquals("M4PQ7-RSTV2", Fixtures.decode<InviteCodeDto>("friends_invite_code_new").inviteCode)
        assertEquals(TradeEntryDto(collectionItemId = 1, quantity = 1, owned = 1), Fixtures.decode<TradeEntryDto>("friends_trade_set"))
    }

    @Test
    fun aProfileSaysWhoTheyAreAndWhichSectionsOpen() {
        val profile = Fixtures.decode<FriendProfileDto>("friend_profile_misty")
        assertTrue(profile.isFriend)
        assertNull(profile.request)
        assertEquals(SharedFlagsDto(true, true, true), profile.shared)
        assertEquals(SharedFlagsDto(), Fixtures.decode<FriendProfileDto>("friend_profile_gary").shared)
    }

    @Test
    fun theServersWordsOnRefusalsAreTheMessagesTheAppShows() {
        assertEquals("brock hasn't shared their collection with you", errorDetailFromBody(Fixtures.text("friend_collection_brock_forbidden")))
        assertEquals("User not found", errorDetailFromBody(Fixtures.text("friend_collection_not_found")))
        assertEquals("Nobody on this server has that username", errorDetailFromBody(Fixtures.text("friends_request_not_found")))
        assertEquals("You and misty are already friends", errorDetailFromBody(Fixtures.text("friends_request_already_friends")))
        assertEquals("You can't add yourself as a friend", errorDetailFromBody(Fixtures.text("friends_request_self")))
        assertEquals("You only have 4 of this card", errorDetailFromBody(Fixtures.text("friends_trade_too_many")))
    }

    @Test
    fun aServerWithoutFriendsAnswersNotFound() {
        assertEquals("Not Found", errorDetailFromBody(Fixtures.text("friends_unsupported_server")))
    }

    @Test
    fun aRowWithNothingButAnIdStillDecodes() {
        val row = AppJson.decodeFromString(TradeItemDto.serializer(), """{"id": 4, "surprise": {"a": 1}}""")
        assertEquals(1, row.quantity)
        assertNull(row.card)
        assertEquals("Unnamed card", listOf(row).tradeCards(null, "price_trend").single().name)
    }
}

class SharingTest {
    @Test
    fun nothingIsSharedUntilTheUserChooses() {
        val sharing = Sharing()
        assertFalse(sharing.sharesAnything)
        ShareSection.entries.forEach { assertEquals(ShareLevel.PRIVATE, sharing[it]) }
    }

    @Test
    fun aLevelTheAppDoesNotKnowCountsAsPrivate() {
        assertEquals(ShareLevel.PRIVATE, ShareLevel.fromKey("everyone"))
        assertEquals(ShareLevel.PRIVATE, ShareLevel.fromKey(null))
        assertEquals(ShareLevel.PRIVATE, ShareLevel.fromKey(""))
        assertEquals(ShareLevel.FRIENDS, ShareLevel.fromKey("friends"))
        assertEquals(ShareLevel.PUBLIC, ShareLevel.fromKey("public"))
        assertEquals(Sharing(), SharingDto("???", "Friends", "").toSharing())
    }

    @Test
    fun eachSectionChangesOnItsOwn() {
        val sharing = Sharing().with(ShareSection.WISHLIST, ShareLevel.FRIENDS)
        assertEquals(Sharing(wishlist = ShareLevel.FRIENDS), sharing)
        assertEquals(ShareLevel.FRIENDS, sharing[ShareSection.WISHLIST])
        assertTrue(sharing.sharesAnything)
        assertEquals(Sharing(ShareLevel.PUBLIC, ShareLevel.FRIENDS, ShareLevel.PRIVATE), sharing.with(ShareSection.COLLECTION, ShareLevel.PUBLIC))
    }

    @Test
    fun theUpdateBodyNamesOnlyTheSectionThatChanges() {
        fun body(section: ShareSection, level: ShareLevel) = AppJson.encodeToString(SharingUpdateBody.serializer(), sharingUpdate(section, level))
        assertEquals("""{"collection":"public"}""", body(ShareSection.COLLECTION, ShareLevel.PUBLIC))
        assertEquals("""{"wishlist":"private"}""", body(ShareSection.WISHLIST, ShareLevel.PRIVATE))
        assertEquals("""{"trade":"friends"}""", body(ShareSection.TRADE, ShareLevel.FRIENDS))
    }

    @Test
    fun theLevelsHaveTheKeysTheServerUses() {
        assertEquals(listOf("private", "friends", "public"), ShareLevel.entries.map { it.key })
        assertEquals(listOf("collection", "wishlist", "trade"), ShareSection.entries.map { it.key })
    }

    @Test
    fun aFriendsSharesAreSaidInWords() {
        assertEquals("Hasn't shared anything with you yet", SharedFlagsDto().summary())
        assertEquals("Shares For Trade", SharedFlagsDto(trade = true).summary())
        assertEquals("Shares Collection · Wishlist · For Trade", SharedFlagsDto(true, true, true).summary())
        assertEquals("Shares Wishlist", SharedFlagsDto(wishlist = true).summary())
    }
}

class InviteCodeTest {
    @Test
    fun aCodeMayBeTypedInAnyCaseWithOrWithoutTheDash() {
        assertEquals("K7MQ9XWD3H", InviteCode.normalize("k7mq9-xwd3h"))
        assertEquals("K7MQ9XWD3H", InviteCode.normalize(" K7MQ9 XWD3H "))
        assertEquals("K7MQ9XWD3H", InviteCode.normalize("K7MQ9XWD3H"))
        assertEquals("K7MQ9-XWD3H", InviteCode.format("K7MQ9XWD3H"))
    }

    @Test
    fun whatCannotBeACodeIsRefused() {
        listOf("", "   ", "K7MQ9", "K7MQ9XWD3HH", "K7MQ9XWD3O", "K7MQ9XWD31", "K7MQ9XWDLH", "K7MQ9XWDUH", "K7MQ9XWDIH", "K7MQ9XWD30").forEach {
            assertNull(it, InviteCode.normalize(it))
        }
        assertNull(InviteCode.normalize(null))
    }

    @Test
    fun theFixturesCodeIsOneTheAppAccepts() {
        val code = Fixtures.decode<FriendsMeDto>("friends_me").inviteCode
        assertEquals(code, InviteCode.format(InviteCode.normalize(code)!!))
    }

    @Test
    fun aCodeOfTheWrongLengthIsLeftAsItIs() {
        assertEquals("ABC", InviteCode.format("ABC"))
    }
}

class AddFriendTest {
    private fun body(request: FriendRequestBody?) = AppJson.encodeToString(FriendRequestBody.serializer(), checkNotNull(request))

    @Test
    fun aUsernameIsTrimmedAndSentAsOne() {
        assertEquals("""{"username":"misty"}""", body(AddFriendMode.USERNAME.requestFor("  misty ")))
    }

    @Test
    fun anInviteCodeIsSentAsTheServerWritesIt() {
        assertEquals("""{"invite_code":"K7MQ9-XWD3H"}""", body(AddFriendMode.CODE.requestFor("k7mq9xwd3h")))
    }

    @Test
    fun nothingIsSentUntilWhatWasTypedCouldBeRight() {
        assertNull(AddFriendMode.USERNAME.requestFor(""))
        assertNull(AddFriendMode.USERNAME.requestFor("    "))
        assertNull(AddFriendMode.USERNAME.requestFor("x".repeat(65)))
        assertNull(AddFriendMode.CODE.requestFor("misty"))
        assertNull(AddFriendMode.CODE.requestFor("K7MQ9-XWD3"))
    }

    @Test
    fun aUsernameThatLooksLikeACodeIsStillAUsername() {
        // The user chooses what they typed; the app never guesses between the two.
        assertEquals("""{"username":"K7MQ9XWD3H"}""", body(AddFriendMode.USERNAME.requestFor("K7MQ9XWD3H")))
    }

    @Test
    fun friendsAreListedByNameWhateverTheCase() {
        val friends = listOf(FriendDto(1, "zed"), FriendDto(2, "Ash"), FriendDto(3, "misty"))
        assertEquals(listOf("Ash", "misty", "zed"), friends.byName().map { it.username })
    }
}

class FriendsAvailabilityTest {
    @Test
    fun aNotFoundMeansTheServerHasNotGotTheUpdate() {
        assertEquals(FriendsAvailability.MISSING, httpError(404, Fixtures.text("friends_unsupported_server")).friendsAvailability())
    }

    @Test
    fun aRefusalMeansTheServerNeedsMultiUserMode() {
        assertEquals(FriendsAvailability.NEEDS_MULTI_USER, httpError(403).friendsAvailability())
    }

    @Test
    fun anythingElseIsWorthTryingAgain() {
        assertEquals(FriendsAvailability.FAILED, IOException("offline").friendsAvailability())
        assertEquals(FriendsAvailability.FAILED, httpError(500).friendsAvailability())
        assertEquals(FriendsAvailability.FAILED, httpError(401).friendsAvailability())
        assertEquals(FriendsAvailability.FAILED, IllegalArgumentException("odd").friendsAvailability())
    }
}

class FriendCardTest {
    @Test
    fun aCollectionRowSaysHowManyTheyHaveAndHowManyAreForTrade() {
        val hoppip = misty().first { it.cardId == "sv2-001_en" }
        assertEquals("Hoppip", hoppip.name)
        assertEquals("Paldea Evolved · #001 · Common", hoppip.subtitle)
        assertEquals(3, hoppip.owned)
        assertEquals(2, hoppip.offered)
        assertEquals(listOf(FriendTag("Has ×3"), FriendTag("For trade ×2", TagTone.GOOD), FriendTag("You don't own it")), hoppip.tags)
    }

    @Test
    fun aCardWithNoTradeCopiesHasNoTradeTag() {
        val skiploom = misty().first { it.cardId == "sv2-002_en" }
        assertEquals(listOf(FriendTag("You don't own it")), skiploom.tags)
    }

    @Test
    fun whetherTheUserOwnsTheCardIsCountedByItsOwnId() {
        val miraidon = misty().first { it.cardId == "sv1-198_en" }
        assertEquals(2, miraidon.mine)
        assertTrue(miraidon.tags.contains(FriendTag("You own ×2")))
        // Until the collection has loaded nothing is said about it.
        val unknown = Fixtures.decode<List<CollectionItemDto>>("friend_collection_misty").collectionCards(null, "price_trend")
        assertTrue(unknown.all { it.mine == null })
        assertTrue(unknown.none { card -> card.tags.any { it.text.startsWith("You") } })
    }

    @Test
    fun aWishlistCardSaysHowManyAreWantedAndHavingItIsGoodNews() {
        val cards = mistyWishlist().associateBy { it.cardId }
        val oddish = cards.getValue("sv3-001_en")
        assertEquals(listOf(FriendTag("Wants ×2"), FriendTag("You own ×4", TagTone.GOOD)), oddish.tags)
        // One wanted: no "Wants" tag. The user has two Miraidon: they can give one.
        assertEquals(listOf(FriendTag("You own ×2", TagTone.GOOD)), cards.getValue("sv1-198_en").tags)
        assertEquals(listOf(FriendTag("You don't own it")), cards.getValue("sv2-002_en").tags)
        assertNull(oddish.condition)
        assertEquals("", oddish.copyText)
    }

    @Test
    fun aTradeCardSaysHowManyCopiesAreOnOffer() {
        val cards = mistyTrade()
        assertEquals(listOf("sv3-223_en", "sv2-001_en"), cards.map { it.cardId })
        assertEquals(listOf(FriendTag("For trade ×1", TagTone.GOOD), FriendTag("You don't own it")), cards.first().tags)
    }

    @Test
    fun theHalvesOfAMatchSpeakFromTheirSides() {
        val theirs = match.theyHaveYouWant.matchCards(FriendCardKind.THEIR_OFFER, mine, "price_trend")
        val yours = match.youHaveTheyWant.matchCards(FriendCardKind.YOUR_OFFER, mine, "price_trend")
        assertEquals(listOf(FriendTag("Offers ×2", TagTone.GOOD)), theirs.first { it.cardId == "sv2-001_en" }.tags)
        assertEquals(listOf(FriendTag("You offer ×1", TagTone.GOOD)), yours.first { it.cardId == "sv1-198_en" }.tags.take(1))
        val oddish = yours.first { it.cardId == "sv3-001_en" }
        assertEquals(listOf(FriendTag("You offer ×2", TagTone.GOOD), FriendTag("They want ×2"), FriendTag("You own ×4")), oddish.tags)
        assertEquals("LP · Reverse Holo", oddish.copyText)
        // The two halves never share keys, so one list can hold both.
        assertTrue((theirs + yours).map { it.key }.toSet().size == theirs.size + yours.size)
    }

    @Test
    fun theCopyTextLeavesOutTheUsualVariantAndLanguage() {
        fun card(condition: String?, variant: String?, lang: String?) =
            FriendCard(FriendCardKind.COLLECTION, "k", "x_en", null, condition, variant, lang, 1, null, null, null, 0.0, 0)
        assertEquals("NM", card("NM", "Normal", "en").copyText)
        assertEquals("LP · Holo", card("LP", "Holo", "en").copyText)
        assertEquals("NM · DE", card("NM", "Normal", "de").copyText)
        assertEquals("Mint · Reverse Holo · DE", card("Mint", "Reverse Holo", "de").copyText)
        assertEquals("", card(null, null, null).copyText)
    }

    @Test
    fun theCardIsPricedByTheUsersChosenPriceAndTheVariantOfTheCopy() {
        val oddish = match.youHaveTheyWant.matchCards(FriendCardKind.YOUR_OFFER, mine, "price_trend").first { it.cardId == "sv3-001_en" }
        // Reverse Holo has no price of its own in the fixture, so the plain trend price is used, as the server does.
        assertEquals(0.1, oddish.priceEur, 1e-9)
        val sir = mistyTrade().first { it.cardId == "sv3-223_en" }
        assertEquals(61.0, sir.priceEur, 1e-9)
        assertTrue(sir.hasPrice)
        val jumpluff = Fixtures.decode<TradeMatchDto>("friend_trade_match_brock").theyHaveYouWant.matchCards(FriendCardKind.THEIR_OFFER, mine, "price_trend").single()
        assertEquals(0.0, jumpluff.priceEur, 0.0)
        assertFalse(jumpluff.hasPrice)
    }

    @Test
    fun aScreenReaderHearsTheWholeRow() {
        val oddish = match.youHaveTheyWant.matchCards(FriendCardKind.YOUR_OFFER, mine, "price_trend").first { it.cardId == "sv3-001_en" }
        val text = oddish.describe(usd)
        assertTrue(text, text.startsWith("Oddish, Obsidian Flames, number 001, Common, LP · Reverse Holo, you offer ×2, they want ×2, you own ×4, price"))
        val none = mistyTrade().first().copy(card = null, cardId = "x-1_en", priceEur = 0.0).describe(usd)
        assertTrue(none, none.contains("no price"))
    }

    @Test
    fun theUsualCardDetailsCanShowAFriendsCard() {
        val hoppip = misty().first { it.cardId == "sv2-001_en" }.asCollectionItem()
        assertEquals("sv2-001_en", hoppip.cardId)
        assertEquals(3, hoppip.quantity)
        assertEquals(2, hoppip.forTradeQuantity)
        assertEquals("Hoppip", hoppip.card?.name)
        assertNull(hoppip.purchasePrice)
        val wanted = mistyWishlist().first { it.cardId == "sv3-001_en" }.asCollectionItem()
        assertEquals(2, wanted.quantity)
        assertEquals("NM", wanted.condition)
        assertEquals("Normal", wanted.variant)
        assertEquals("en", wanted.lang)
    }

    @Test
    fun searchingFindsEveryWordInTheNameSetNumberRarityOrCopy() {
        val cards = misty()
        assertEquals(listOf("sv2-001_en"), cards.filter { it.matches("hoppip") }.map { it.cardId })
        assertEquals(listOf("sv2-002_en", "sv2-001_en"), cards.filter { it.matches("paldea") }.map { it.cardId })
        assertEquals(listOf("sv3-223_en"), cards.filter { it.matches("special illustration") }.map { it.cardId })
        assertEquals(listOf("sv1-198_en"), cards.filter { it.matches("lp") }.map { it.cardId })
        assertTrue(cards.none { it.matches("hoppip charizard") })
        assertEquals(cards.size, cards.count { it.matches("   ") })
    }

    @Test
    fun ordering() {
        val cards = misty()
        assertEquals(cards.map { it.cardId }, cards.ordered(FriendCardOrder.RECENT).map { it.cardId })
        assertEquals(listOf("Charizard ex", "Hoppip", "Miraidon ex", "Skiploom"), cards.ordered(FriendCardOrder.NAME).map { it.name })
        // By set: Obsidian Flames (the Charizard), Paldea Evolved (Hoppip #001, Skiploom #002), Scarlet & Violet (Miraidon).
        assertEquals(listOf("sv3-223_en", "sv2-001_en", "sv2-002_en", "sv1-198_en"), cards.ordered(FriendCardOrder.SET).map { it.cardId })
        assertEquals(listOf("sv3-223_en", "sv1-198_en", "sv2-002_en", "sv2-001_en"), cards.ordered(FriendCardOrder.PRICE).map { it.cardId })
    }

    @Test
    fun theCardsWithNoPriceComeLastWhenOrderedByPrice() {
        val withNoPrice = misty().first().copy(priceEur = 0.0, card = null, key = "zz")
        val ordered = (misty() + withNoPrice).ordered(FriendCardOrder.PRICE)
        assertEquals("zz", ordered.last().key)
    }
}

class TradeMarksTest {
    @Test
    fun nothingIsForTradeUntilTheUserSaysSo() {
        val slot = TradeSlot(held = 3, offered = 0)
        assertEquals(0, slot.shown)
        assertFalse(slot.canOfferFewer)
        assertTrue(slot.canOfferMore)
        assertEquals("None for trade", slot.summary)
    }

    @Test
    fun theUserCanOfferSomeOfTheirCopiesButNotMoreThanTheyHold() {
        val slot = TradeSlot(held = 3, offered = 2)
        assertEquals("2 of 3 for trade", slot.summary)
        assertEquals(3, slot.more())
        assertEquals(1, slot.fewer())
        val all = TradeSlot(3, 3)
        assertFalse(all.canOfferMore)
        assertEquals(3, all.more())
        assertEquals(0, TradeSlot(3, 0).fewer())
    }

    @Test
    fun aMarkLargerThanWhatIsHeldIsShownAsWhatIsHeld() {
        // Copies were removed after being marked: the server trims the mark, and until the app knows, so does the screen.
        val slot = TradeSlot(held = 1, offered = 4)
        assertEquals(1, slot.shown)
        assertEquals("1 of 1 for trade", slot.summary)
        assertFalse(slot.canOfferMore)
        assertEquals(0, TradeSlot(held = 0, offered = 2).shown)
        assertEquals(0, TradeSlot(held = -1, offered = 2).shown)
        assertEquals(0, TradeSlot(held = 2, offered = -3).shown)
    }

    @Test
    fun marksComeFromTheServersListAndChangeOneRowAtATime() {
        val marks = Fixtures.decode<OwnTradeListDto>("friends_own_trade_list").toMarks()
        assertEquals(mapOf(3 to 2, 4 to 1), marks)
        assertEquals(mapOf(3 to 2, 4 to 1, 1 to 1), marks.withMark(1, 1))
        assertEquals(mapOf(4 to 1), marks.withMark(3, 0))
        assertEquals(marks, marks.withMark(9, 0))
        assertEquals(mapOf(3 to 5, 4 to 1), marks.withMark(3, 5))
    }

    @Test
    fun aMarkOfNoneIsNotAMark() {
        assertEquals(emptyMap<Int, Int>(), OwnTradeListDto(listOf(TradeEntryDto(1, 0, 3))).toMarks())
    }

    @Test
    fun theCopiesForTradeNeverCountMoreThanARowHolds() {
        // collection.json: row 3 holds 4 (Oddish), row 4 holds 2 (Miraidon).
        assertEquals(3, mapOf(3 to 2, 4 to 1).copiesForTrade(collection))
        assertEquals(6, mapOf(3 to 9, 4 to 9).copiesForTrade(collection))
        assertEquals(0, emptyMap<Int, Int>().copiesForTrade(collection))
        assertEquals(0, mapOf(99 to 5).copiesForTrade(collection))
    }
}

class FriendsStateTest {
    @Test
    fun aFreshStateSharesNothingAndKnowsNothing() {
        val state = FriendsState()
        assertFalse(state.supported)
        assertEquals(Sharing(), state.sharing)
        assertEquals("", state.inviteCode)
        assertEquals(0, state.markedFor(7))
    }

    @Test
    fun aChangeOnItsWayIsShownAsDone() {
        val state = FriendsState(marks = mapOf(7 to 1), marking = mapOf(7 to 2, 8 to 1))
        assertEquals(2, state.markedFor(7))
        assertEquals(1, state.markedFor(8))
        assertEquals(1, FriendsState(marks = mapOf(7 to 1)).markedFor(7))
        assertEquals(0, FriendsState(marks = mapOf(7 to 1), marking = mapOf(7 to 0)).markedFor(7))
    }

    @Test
    fun whatTheUserSharesIsWhatTheServerLastSaid() {
        val me = Fixtures.decode<FriendsMeDto>("friends_me").copy(sharing = SharingDto("friends", "public", "private"))
        val state = FriendsState(availability = FriendsAvailability.SUPPORTED, me = me)
        assertTrue(state.supported)
        assertEquals(Sharing(ShareLevel.FRIENDS, ShareLevel.PUBLIC, ShareLevel.PRIVATE), state.sharing)
        assertEquals("K7MQ9-XWD3H", state.inviteCode)
    }

    @Test
    fun theWishlistFixtureIsStillWhatTheWishlistTestsExpect() {
        // The friends fixtures were generated beside the wishlist ones: the user's wishlist is the same.
        assertEquals(6, wishlist.size)
    }
}
