package com.example

import org.junit.Assert.*
import org.junit.Test

class Media3TvParityPolicyTest {
    @Test fun navigationVisibilityIsContextAware() {
        assertTrue(Media3TvParityPolicy.showPlaylistNavigation(true, 2))
        assertFalse(Media3TvParityPolicy.showPlaylistNavigation(true, 1))
        assertFalse(Media3TvParityPolicy.showPlaylistNavigation(false, 5))
        assertTrue(Media3TvParityPolicy.showSeekNavigation(true, false))
        assertFalse(Media3TvParityPolicy.showSeekNavigation(true, true))
        assertFalse(Media3TvParityPolicy.showSeekNavigation(false, false))
    }

    @Test fun splitLayoutUsesReferenceWeights() {
        assertEquals(0.64f, Media3TvParityPolicy.splitPaneWeights().primary)
        assertEquals(0.36f, Media3TvParityPolicy.splitPaneWeights().secondary)
    }

    @Test fun liveHubOrderIsStableAndDeduplicated() {
        assertEquals(
            listOf(3, 1, 4, 0, 2),
            Media3TvParityPolicy.orderedLiveHubIndices(
                3, listOf(1, 3, 99), listOf(true, false, false, false, true), 5
            )
        )
        assertTrue(Media3TvParityPolicy.orderedLiveHubIndices(0, emptyList(), emptyList(), 0).isEmpty())
    }

    @Test fun focusPolicyOnlyTargetsOnDemandTransport() {
        assertTrue(Media3TvParityPolicy.focusTransportOnRemoteInput(false, false, false))
        assertTrue(Media3TvParityPolicy.focusTransportOnRemoteInput(false, true, true))
        assertFalse(Media3TvParityPolicy.focusTransportOnRemoteInput(true, false, false))
        assertFalse(Media3TvParityPolicy.focusTransportOnRemoteInput(false, true, false))
    }

    @Test fun splitNavigationSkipsBlockedPaneAndHonorsEdges() {
        assertEquals(2, Media3TvParityPolicy.nextSplitIndex(0, 1, 1, 4))
        assertEquals(1, Media3TvParityPolicy.nextSplitIndex(2, 3, -1, 4))
        assertNull(Media3TvParityPolicy.nextSplitIndex(2, 3, 1, 4))
        assertNull(Media3TvParityPolicy.nextSplitIndex(1, 1, 0, 4))
        assertNull(Media3TvParityPolicy.nextSplitIndex(0, 0, 1, 0))
    }
}