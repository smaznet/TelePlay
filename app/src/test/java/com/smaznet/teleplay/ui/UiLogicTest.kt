package com.smaznet.teleplay.ui

import com.smaznet.teleplay.domain.Availability
import com.smaznet.teleplay.domain.ConnectionState
import com.smaznet.teleplay.domain.PlaybackProgress
import com.smaznet.teleplay.domain.TelegramError
import com.smaznet.teleplay.domain.Video
import com.smaznet.teleplay.ui.library.*
import com.smaznet.teleplay.ui.nav.Route
import com.smaznet.teleplay.ui.nav.reconcile
import com.smaznet.teleplay.ui.nav.startRoute
import com.smaznet.teleplay.ui.token.TokenError
import com.smaznet.teleplay.ui.token.tokenErrorFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UiLogicTest {
    private fun v(id: Long, at: Long, a: Availability = Availability.PLAYABLE) = Video(
        id, 1, id.toInt(), id, 0, ByteArray(0), 2, "t$id", 60, 1000, "video/mp4", 1, 1, null, at, a,
    )

    @Test fun startRouteMapping() {
        assertEquals(Route.Token, startRoute(ConnectionState.SIGNED_OUT))
        assertEquals(Route.Token, startRoute(ConnectionState.REJECTED))
        assertEquals(Route.Pairing, startRoute(ConnectionState.AWAITING_PAIRING))
        assertEquals(Route.Library, startRoute(ConnectionState.READY))
        assertEquals(Route.Library, startRoute(ConnectionState.OFFLINE))
        assertEquals(Route.Library, startRoute(ConnectionState.CONNECTING))
        assertEquals(Route.Revoked, startRoute(ConnectionState.REVOKED))
    }

    @Test fun reconcileRules() {
        assertNull(reconcile(ConnectionState.CONNECTING, Route.Token))
        assertNull(reconcile(ConnectionState.SIGNED_OUT, Route.Token))
        assertEquals(Route.Token, reconcile(ConnectionState.SIGNED_OUT, Route.Settings))
        assertEquals(Route.Pairing, reconcile(ConnectionState.AWAITING_PAIRING, Route.Token))
        assertEquals(Route.Library, reconcile(ConnectionState.READY, Route.Pairing))
        assertNull(reconcile(ConnectionState.READY, Route.Settings))
        assertNull(reconcile(ConnectionState.OFFLINE, Route.Player(1, false)))
        assertEquals(Route.Revoked, reconcile(ConnectionState.REVOKED, Route.Library))
        assertEquals(Route.Revoked, reconcile(ConnectionState.REJECTED, Route.Player(1, false)))
        assertNull(reconcile(ConnectionState.REJECTED, Route.Token))
        assertNull(reconcile(ConnectionState.REVOKED, Route.Token))
    }

    @Test fun sectionsNewestFirstAndContinueOnlyPlayable() {
        val s = buildSections(
            listOf(v(1, 10), v(2, 30), v(3, 20)),
            listOf(v(3, 20), v(2, 30, Availability.REMOTE_GONE)),
        )
        assertEquals(listOf(2L, 3L, 1L), s.all.map { it.id })
        assertEquals(listOf(3L), s.continueWatching.map { it.id })
    }

    @Test fun initialFocusRules() {
        val s = buildSections(listOf(v(1, 10), v(2, 30)), listOf(v(1, 10)))
        assertEquals(FocusTarget.Hero, initialFocus(s, null))
        assertEquals(FocusTarget.Continue(1), initialFocus(s, 1))
        assertEquals(FocusTarget.Main(2), initialFocus(s, 2))
        assertEquals(FocusTarget.Hero, initialFocus(s, 99))
        assertEquals(FocusTarget.Hero, initialFocus(buildSections(listOf(v(2, 30)), emptyList()), null))
        assertEquals(FocusTarget.Refresh, initialFocus(buildSections(emptyList(), emptyList()), null))
    }

    @Test fun featuredPrefersContinueThenNewest() {
        val s = buildSections(listOf(v(1, 10), v(2, 30)), listOf(v(1, 10)))
        assertEquals(1L, s.featured()?.id)
        assertEquals(2L, buildSections(listOf(v(1, 10), v(2, 30)), emptyList()).featured()?.id)
        assertNull(buildSections(emptyList(), emptyList()).featured())
    }

    @Test fun mainShelfIndex() {
        val vids = (1L..9L).map { v(it, it) }
        assertEquals(2, mainShelfItemIndex(buildSections(vids, listOf(vids[0]))))
        assertEquals(1, mainShelfItemIndex(buildSections(vids, emptyList())))
    }

    @Test fun progressFraction() {
        assertEquals(0f, progressFraction(null), 0f)
        assertEquals(0.5f, progressFraction(PlaybackProgress(1, 500, 1000, false, 0)), 0f)
        assertEquals(0f, progressFraction(PlaybackProgress(1, 990, 1000, true, 0)), 0f)
    }

    @Test fun formatting() {
        assertEquals("1:05", formatDuration(65))
        assertEquals("1:01:01", formatDuration(3661))
        assertEquals("2.0 GB", formatSize(2L shl 30))
        assertEquals("14:59", formatCountdown(898_500))
        assertEquals("14 min left", formatRemaining(14 * 60_000L - 5_000))
        assertEquals("1 h 5 min left", formatRemaining(65 * 60_000L))
        assertEquals("1 min left", formatRemaining(100))
    }

    @Test fun tokenErrors() {
        assertEquals(TokenError.Invalid, tokenErrorFor(TelegramError.TokenRejected()))
        assertEquals(TokenError.Offline, tokenErrorFor(TelegramError.Offline()))
        assertEquals(TokenError.RateLimited(7), tokenErrorFor(TelegramError.RateLimited(7)))
        assertEquals(TokenError.Other, tokenErrorFor(IllegalStateException("x")))
    }
}
