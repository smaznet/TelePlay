package com.smaznet.teleplay.remote

import com.smaznet.teleplay.domain.remote.PlaybackHub
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackHubTest {
    private val hub = PlaybackHub().apply { setLibraryOrder(listOf(5, 4, 3)) }

    @Test fun nextIsTheFollowingInNewestFirstOrder() {
        assertEquals(4L, hub.neighborOf(5, forward = true))
        assertEquals(3L, hub.neighborOf(4, forward = true))
    }

    @Test fun previousIsTheOneBefore() {
        assertEquals(5L, hub.neighborOf(4, forward = false))
    }

    @Test fun noWrapAtEitherEnd() {
        assertNull(hub.neighborOf(3, forward = true))
        assertNull(hub.neighborOf(5, forward = false))
    }

    @Test fun unknownVideoHasNoNeighbors() {
        assertNull(hub.neighborOf(99, forward = true))
        assertNull(hub.neighborOf(99, forward = false))
    }

    @Test fun libraryOrderCanBeReplaced() {
        hub.setLibraryOrder(listOf(5, 3))
        assertEquals(3L, hub.neighborOf(5, forward = true))
    }
}
