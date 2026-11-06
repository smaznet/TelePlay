package com.smaznet.teleplay.setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupSessionTest {
    private var now = 1_000L
    private fun session(attempts: Int = 5) = SetupSession(clock = { now }, validityMs = 10_000, attempts = attempts)

    @Test fun secretIs22UrlSafeChars() {
        val s = session().secret
        assertEquals(22, s.length)
        assertTrue(s.all { it.isLetterOrDigit() || it == '-' || it == '_' })
    }

    @Test fun twoSessionsNeverShareSecret() {
        assertNotEquals(session().secret, session().secret)
    }

    @Test fun pageViewMovesToPhoneConnected() {
        val s = session()
        assertEquals(SetupState.WAITING, s.state.value)
        s.onPageViewed()
        assertEquals(SetupState.PHONE_CONNECTED, s.state.value)
    }

    @Test fun acceptedSubmissionIsTerminal() {
        val s = session()
        s.onPageViewed()
        assertTrue(s.beginSubmit())
        assertEquals(SetupState.SUBMITTING, s.state.value)
        s.onAccepted()
        assertEquals(SetupState.USED, s.state.value)
        assertFalse(s.beginSubmit())
        assertFalse(s.isUsable)
    }

    @Test fun secondSubmitWhileInFlightIsRefused() {
        val s = session()
        assertTrue(s.beginSubmit())
        assertFalse(s.beginSubmit())
    }

    @Test fun rejectionAllowsRetryAndConsumesAttempt() {
        val s = session()
        s.beginSubmit()
        s.onFailed(SetupError.REJECTED)
        assertEquals(SetupState.REJECTED, s.state.value)
        assertEquals(SetupError.REJECTED, s.lastError.value)
        assertEquals(4, s.attemptsLeft)
        s.onPageViewed()
        assertEquals(SetupState.PHONE_CONNECTED, s.state.value)
        assertTrue(s.beginSubmit())
        assertNull(s.lastError.value)
    }

    @Test fun offlineFailureDoesNotConsumeAttempt() {
        val s = session()
        s.beginSubmit()
        s.onFailed(SetupError.OFFLINE)
        assertEquals(5, s.attemptsLeft)
        assertEquals(SetupState.REJECTED, s.state.value)
    }

    @Test fun exhaustingAttemptsExpires() {
        val s = session(attempts = 2)
        repeat(2) { s.beginSubmit(); s.onFailed(SetupError.REJECTED) }
        assertEquals(SetupState.EXPIRED, s.state.value)
        assertFalse(s.beginSubmit())
    }

    @Test fun expiresAtDeadline() {
        val s = session()
        now += 9_999
        assertFalse(s.checkExpiry())
        now += 1
        assertTrue(s.checkExpiry())
        assertEquals(SetupState.EXPIRED, s.state.value)
        assertFalse(s.beginSubmit())
    }

    @Test fun usedSessionDoesNotBecomeExpired() {
        val s = session()
        s.beginSubmit(); s.onAccepted()
        now += 60_000
        assertFalse(s.checkExpiry())
        assertEquals(SetupState.USED, s.state.value)
    }

    @Test fun constantTimeMatch() {
        val s = session()
        assertTrue(s.matches(s.secret))
        assertFalse(s.matches(s.secret.dropLast(1)))
        assertFalse(s.matches(s.secret.dropLast(1) + (if (s.secret.last() == 'A') 'B' else 'A')))
        assertFalse(s.matches(""))
    }

    @Test fun toStringDoesNotLeakSecret() {
        val s = session()
        assertFalse(s.toString().contains(s.secret))
    }
}
