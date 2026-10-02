package app.cardpulse.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import kotlinx.serialization.serializer
import org.junit.Test

/** Decodes real server responses with the app's models. A failure here means the app and server disagree. */
class ContractTest {
    @Test
    fun `health and auth mode`() {
        val health = Fixtures.decode<HealthDto>("health")
        assertEquals("ok", health.status)
        assertEquals("pokemon-tcg-collection", health.service)
        assertTrue(Fixtures.decode<AuthModeDto>("auth_mode_multi").multiUser)
        assertFalse(Fixtures.decode<AuthModeDto>("auth_mode_single").multiUser)
    }

    @Test
    fun `login nests the user and the forced password change flag`() {
        val login = Fixtures.decode<LoginResponseDto>("login")
        assertEquals("fixture-token-not-a-real-credential", login.accessToken)
        assertEquals("bearer", login.tokenType)
        assertEquals("admin", login.user.username)
        assertEquals("admin", login.user.role)
        assertEquals(25, login.user.avatarId)
        assertFalse(login.user.mustChangePassword)
        assertTrue(Fixtures.decode<LoginResponseDto>("login_must_change").user.mustChangePassword)
    }

    @Test
    fun `me works with and without a token in single-user mode`() {
        assertEquals("admin", Fixtures.decode<UserDto>("me").username)
        assertEquals("admin", Fixtures.decode<UserDto>("single_user_me_without_token").username)
    }

    @Test
    fun `error bodies yield the server's message`() {
        assertEquals("Incorrect username or password", errorDetailFromBody(Fixtures.text("login_error")))
        assertEquals("Not authenticated", errorDetailFromBody(Fixtures.text("unauthorized")))
        assertEquals("This scan has already been handled.", errorDetailFromBody(Fixtures.text("resolve_and_add_repeat")))
        assertEquals("This scan is still being processed.", errorDetailFromBody(Fixtures.text("resolve_not_ready")))
        // FastAPI validation errors use a list.
        assertEquals(
            "Field required",
            errorDetailFromBody("""{"detail":[{"loc":["body","x"],"msg":"Field required","type":"missing"}]}"""),
        )
        assertNull(errorDetailFromBody(""))
        assertNull(errorDetailFromBody("<html>502 Bad Gateway</html>"))
    }

    @Test
    fun `settings give currency price field and photo preference`() {
        val prefs = displayPrefsFrom(Fixtures.decode("settings"))
        assertEquals("USD", prefs.currency)
        assertEquals("price_trend", prefs.priceField)
        assertFalse(prefs.preferOwnPhotos)
        val rate = Fixtures.decode<ExchangeRateDto>("exchange_rate")
        assertEquals(1.1, rate.rate, 0.0001)
        assertTrue(rate.fallback)
    }

    @Test
    fun `price field mapping matches the web app`() {
        assertEquals("price_trend", priceFieldFor("trend"))
        assertEquals("price_market", priceFieldFor("avg"))
        assertEquals("price_market", priceFieldFor("market"))
        assertEquals("price_avg7", priceFieldFor("avg7"))
        assertEquals("price_low", priceFieldFor("low"))
        assertEquals("price_trend", priceFieldFor(null))
        assertEquals("price_trend", priceFieldFor("something-new"))
    }

    @Test
    fun `collection decodes with nested card set and photo flags`() {
        val items = Fixtures.decode<List<CollectionItemDto>>("collection")
        assertEquals(6, items.size)

        val first = items[0]
        assertEquals("sv3-125_en", first.cardId)
        assertEquals(1, first.quantity)
        assertEquals("NM", first.condition)
        assertEquals("Normal", first.variant)
        assertEquals(5.0, first.purchasePrice!!, 0.0)
        assertEquals("en", first.lang)
        assertEquals("Charizard ex", first.card!!.name)
        assertEquals("Obsidian Flames", first.card!!.setRef!!.name)
        assertEquals("OBF", first.card!!.setRef!!.abbreviation)
        assertTrue(first.card!!.imagesSmall!!.endsWith("/low.webp"))

        assertEquals(listOf(3, 6), items.filter { it.hasScanPhoto }.map { it.id })
        assertEquals("Reverse Holo", items[2].variant)
        assertEquals(4, items[2].quantity)
        assertEquals("de", items[4].lang)
        assertNull(items[5].card!!.imagesSmall)
        assertTrue(items[0].printingDetailNames.isEmpty())
    }

    @Test
    fun `cost coverage counts rows that have a purchase price`() {
        val coverage = Fixtures.decode<List<CollectionItemDto>>("collection").costCoverage()
        assertEquals(CostCoverage(withCost = 2, total = 6), coverage)
        assertFalse(coverage.isComplete)
        assertTrue(emptyList<CollectionItemDto>().costCoverage().isComplete)
    }

    @Test
    fun `artwork choice mirrors the web app`() {
        val items = Fixtures.decode<List<CollectionItemDto>>("collection")
        // Has a photo and no catalogue image: the photo is the only picture there is.
        assertEquals(ArtSource.OWN_PHOTO, defaultArtSource(items[5], preferOwnPhotos = false))
        // Has a photo and a catalogue image: catalogue wins unless the user prefers photos.
        assertEquals(ArtSource.OFFICIAL, defaultArtSource(items[2], preferOwnPhotos = false))
        assertEquals(ArtSource.OWN_PHOTO, defaultArtSource(items[2], preferOwnPhotos = true))
        // No photo: always the catalogue, whatever the preference.
        assertEquals(ArtSource.OFFICIAL, defaultArtSource(items[0], preferOwnPhotos = true))
    }

    @Test
    fun `dashboard decodes totals and lists`() {
        val dashboard = Fixtures.decode<DashboardDto>("dashboard")
        assertEquals(10, dashboard.totalCards)
        assertEquals(6, dashboard.uniqueCards)
        assertEquals(32.3, dashboard.totalValue, 0.001)
        assertEquals(5.2, dashboard.totalCost, 0.001)
        assertEquals(27.1, dashboard.pnl, 0.001)
        assertEquals(27.1, dashboard.unrealizedPnl, 0.001)
        assertEquals(4, dashboard.totalSets)
        assertEquals(3, dashboard.ownedSets)
        assertEquals("price_trend", dashboard.priceField)
        assertEquals(6, dashboard.topCards.size)
        assertEquals("Charizard ex", dashboard.topCards[0].name)
        assertEquals(8.5, dashboard.topCards[0].displayPrice, 0.001)
        assertEquals(6, dashboard.recentAdditions.size)
        assertEquals(17, dashboard.valueHistory.size)
    }

    @Test
    fun `portfolio history uses value cost and pnl keys`() {
        // The original generated app read total_value / collection_value, which the server never sends.
        val month = Fixtures.decode<List<SnapshotDto>>("investment_tracker_1m")
        assertEquals(10, month.size)
        assertTrue(month.all { it.value > 0.0 })
        assertEquals(38.0, month.first().cost, 0.001)
        assertEquals(52.2, month.first().pnl, 0.001)

        val points = month.toChartPoints()
        assertEquals("Fractional-second timestamps must parse", month.size, points.size)
        assertEquals(points, points.sortedBy { it.time })

        val all = Fixtures.decode<List<SnapshotDto>>("investment_tracker_max")
        assertTrue(all.first().legacy)
        assertNull(all.first().cardsValue)
        assertFalse(all[1].legacy)
    }

    @Test
    fun `top movers decode`() {
        val movers = Fixtures.decode<List<MoverDto>>("top_movers")
        assertEquals(5, movers.size)
        assertEquals("Charizard ex", movers[0].name)
        assertEquals(11.1, movers[0].changePct, 0.001)
        assertEquals(0.85, movers[0].changeAbs, 0.001)
    }

    @Test
    fun `scan job list and detail`() {
        val job = Fixtures.decode<ScanJobListDto>("scan_jobs").jobs.single()
        assertEquals(6, job.total)
        assertEquals(3, job.active)
        assertEquals(3, job.attention)
        assertEquals("rate_limit", job.retryReason)
        assertFalse(job.isSettled)

        val detail = Fixtures.decode<ScanJobDto>("scan_job_detail")
        assertEquals(6, detail.items.size)
        assertEquals(
            listOf(
                ItemPhase.NEEDS_REVIEW, ItemPhase.NEEDS_REVIEW, ItemPhase.WAITING_TO_RETRY,
                ItemPhase.FAILED, ItemPhase.PROCESSING, ItemPhase.QUEUED,
            ),
            detail.items.map { it.phase() },
        )
        // null `matches` / `recognized` from the server must not break decoding.
        assertTrue(detail.items[3].matches.isEmpty())
        assertNull(detail.items[3].recognized)
        assertEquals("The scanner could not read this photo.", detail.items[3].error)
        assertEquals("rate_limit", detail.items[2].retryReason)
    }

    @Test
    fun `scan candidates carry what the review screen needs`() {
        val item = Fixtures.decode<ScanJobDto>("scan_job_detail").items[0]
        assertEquals(3, item.matches.size)
        val top = item.matches[0]
        assertEquals("sv3-125_en", top.id)
        assertEquals("sv3-125", top.tcgCardId)
        assertEquals("Charizard ex", top.name)
        assertEquals("Obsidian Flames", top.set)
        assertEquals("OBF", top.setAbbreviation)
        assertEquals("125", top.number)
        assertEquals("Obsidian Flames · #125 · Double Rare", top.subtitle())
        assertFalse(top.printedTotalMismatch)
        assertTrue(item.matches[2].printedTotalMismatch)
        assertEquals("Charizard ex · 125/197 · OBF", item.recognizedSummary())
    }

    @Test
    fun `resolved items become handled`() {
        val after = Fixtures.decode<ScanJobDto>("scan_job_after_resolve")
        assertEquals(ItemPhase.HANDLED, after.items[0].phase())
        assertEquals(ItemPhase.HANDLED, after.items[1].phase())
        assertEquals(ItemPhase.WAITING_TO_RETRY, after.items[2].phase())
    }

    @Test
    fun `resolve and add response carries the new collection row`() {
        val response = Fixtures.decode<ResolveAndAddResponse>("resolve_and_add")
        assertTrue(response.item.resolved)
        assertEquals(7, response.collectionItem.id)
        assertEquals("sv3-125_en", response.collectionItem.cardId)
        assertEquals("Charizard ex", response.collectionItem.card!!.name)
        assertEquals(ItemPhase.HANDLED, Fixtures.decode<ScanItemDto>("resolve_dismiss").phase())
    }

    @Test
    fun `add request pairs the composite id with the plain one`() {
        val matches = Fixtures.decode<ScanJobDto>("scan_job_detail").items[0].matches
        val english = matches[0].toAddRequest(AddEdits(quantity = 2, condition = "LP", variant = "Reverse Holo", purchasePrice = 1.5))
        assertEquals("sv3-125_en", english.cardId)
        assertEquals("sv3-125", english.confirmedCardId)
        assertEquals("en", english.lang)
        assertEquals(2, english.quantity)
        assertEquals("LP", english.condition)
        assertEquals("Reverse Holo", english.variant)
        assertEquals(1.5, english.purchasePrice!!, 0.0)

        val german = matches[1].toAddRequest(AddEdits())
        assertEquals("sv3-125_de", german.cardId)
        assertEquals("sv3-125", german.confirmedCardId)
        assertEquals("de", german.lang)

        val json = AppJson.encodeToString(serializer<ResolveAndAddRequest>(), english)
        assertTrue(json, json.contains("\"card_id\":\"sv3-125_en\""))
        assertTrue(json, json.contains("\"confirmed_card_id\":\"sv3-125\""))
        assertTrue(json, json.contains("\"purchase_price\":1.5"))
        assertTrue(json, json.contains("\"printing_details\":[]"))
    }

    @Test
    fun `add request clamps quantity and drops negative prices`() {
        val match = ScanMatchDto(id = "sv1-1_zh-tw", name = "x")
        val request = match.toAddRequest(AddEdits(quantity = 5000, purchasePrice = -3.0))
        assertEquals(999, request.quantity)
        assertNull(request.purchasePrice)
        assertEquals("sv1-1", request.confirmedCardId)
        assertEquals("zh-tw", request.lang)
        assertNotNull(request.cardId)
    }

}
