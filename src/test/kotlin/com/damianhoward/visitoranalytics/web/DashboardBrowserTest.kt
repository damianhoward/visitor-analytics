package com.damianhoward.visitoranalytics.web

import com.damianhoward.visitoranalytics.FakeVisitStore
import com.damianhoward.visitoranalytics.sampleVisit
import com.microsoft.playwright.Browser
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import org.hamcrest.MatcherAssert
import org.hamcrest.Matchers.closeTo
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.greaterThan
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Instant

/**
 * The dashboard in a headless Chromium, served by the real [AdminServer] over an in-memory store.
 * [AdminServerTest] pins what the API returns; this pins `app.js` asking for it and rendering it,
 * and the CSS that keeps a long visits list scrollable inside its panel.
 *
 * Twenty visitors, six visits each, interleaved newest first. Visitors 3 and 7 each engaged once;
 * everyone else only browsed.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DashboardBrowserTest {
    private val store = FakeVisitStore()
    private val server = AdminServer(store, AdminAssets.load(), port = 0)
    private lateinit var playwright: Playwright
    private lateinit var browser: Browser

    private val visitors = 20
    private val visitsEach = 6
    private val engagers = setOf(3, 7)

    @BeforeAll
    fun start() {
        val start = Instant.parse("2026-09-26T00:00:00Z")
        var minute = 0L
        for (round in 1..visitsEach) {
            for (visitor in 1..visitors) {
                store.record(
                    sampleVisit(
                        path = "/v$visitor-$round",
                        ipHash = visitor.toString().padStart(64, '0'),
                        engaged = visitor in engagers && round == 2,
                        at = start.plusSeconds(60 * minute++),
                    ),
                )
            }
        }
        server.start()
        playwright = Playwright.create()
        browser = playwright.chromium().launch()
    }

    @AfterAll
    fun stop() {
        browser.close()
        playwright.close()
        server.stop()
    }

    private fun open(): Page {
        val page = browser.newContext(Browser.NewContextOptions().setViewportSize(1440, 900)).newPage()
        page.navigate("http://127.0.0.1:${server.boundPort}/admin")
        return page
    }

    private fun rows(page: Page) = page.locator("#visits tbody tr")

    // The second column is the visitor number, "#n".
    private fun visitorNumbers(page: Page): Set<String> = rows(page).all().map { it.locator("td").nth(1).textContent() }.toSet()

    @Test
    fun `lists every visit, numbering visitors from the newest`() {
        val page = open()
        assertThat(rows(page)).hasCount(visitors * visitsEach)
        assertThat(rows(page).first().locator("td").nth(1)).hasText("#1")
        MatcherAssert.assertThat(visitorNumbers(page).size, equalTo(visitors))
    }

    @Test
    fun `engaged visitors only keeps every visit of the visitors who engaged, and only those`() {
        val page = open()
        val chip = page.locator("#filter-engaged")
        chip.click()

        assertThat(chip).hasAttribute("aria-pressed", "true")
        assertThat(rows(page)).hasCount(engagers.size * visitsEach)
        MatcherAssert.assertThat(visitorNumbers(page).size, equalTo(engagers.size))
        // Each engager engaged once and browsed five times; all six rows stay, so the session reads whole.
        assertThat(page.locator("#visits tbody td.pos")).hasCount(engagers.size)

        chip.click()
        assertThat(chip).hasAttribute("aria-pressed", "false")
        assertThat(rows(page)).hasCount(visitors * visitsEach)
    }

    @Test
    fun `the engaged filter survives a reload`() {
        val page = open()
        page.locator("#filter-engaged").click()
        assertThat(rows(page)).hasCount(engagers.size * visitsEach)

        page.reload()
        assertThat(page.locator("#filter-engaged")).hasAttribute("aria-pressed", "true")
        assertThat(rows(page)).hasCount(engagers.size * visitsEach)
    }

    @Test
    fun `the visits table scrolls inside its panel with its header pinned`() {
        val page = open()
        assertThat(rows(page)).hasCount(visitors * visitsEach)
        val box = page.locator(".scroll")

        val scrollable = box.evaluate("el => el.scrollHeight - el.clientHeight") as Number
        MatcherAssert.assertThat(scrollable.toDouble(), greaterThan(0.0))

        box.evaluate("el => { el.scrollTop = 1000 }")
        val boxTop = box.boundingBox().y
        val headerTop =
            page
                .locator("#visits thead th")
                .first()
                .boundingBox()
                .y
        MatcherAssert.assertThat(headerTop, closeTo(boxTop, 1.0))
    }
}
