package com.example

/** Pure TV-navigation policy shared with the Lumen reference implementation. */
internal data class Media3SplitPaneWeights(val primary: Float, val secondary: Float)

internal object Media3TvParityPolicy {
    private val splitWeights = Media3SplitPaneWeights(0.64f, 0.36f)

    fun showPlaylistNavigation(isTelevision: Boolean, playlistSize: Int): Boolean =
        isTelevision && playlistSize > 1

    fun showSeekNavigation(isTelevision: Boolean, isLive: Boolean): Boolean =
        isTelevision && !isLive

    fun splitPaneWeights(): Media3SplitPaneWeights = splitWeights

    fun orderedLiveHubIndices(
        currentIndex: Int,
        recentIndices: List<Int>,
        favoriteStates: List<Boolean>,
        itemCount: Int
    ): List<Int> {
        if (itemCount <= 0) return emptyList()
        return buildList {
            add(currentIndex)
            addAll(recentIndices)
            addAll((0 until itemCount).filter { favoriteStates.getOrNull(it) == true })
            addAll(0 until itemCount)
        }.filter { it in 0 until itemCount }.distinct()
    }

    fun focusTransportOnRemoteInput(
        isLive: Boolean,
        controlsVisible: Boolean,
        playerSurfaceFocused: Boolean
    ): Boolean = !isLive && (!controlsVisible || playerSurfaceFocused)

    fun nextSplitIndex(
        currentIndex: Int,
        blockedIndex: Int,
        direction: Int,
        itemCount: Int
    ): Int? {
        if (direction == 0 || itemCount <= 0) return null
        var target = currentIndex + if (direction > 0) 1 else -1
        while (target in 0 until itemCount && target == blockedIndex) {
            target += if (direction > 0) 1 else -1
        }
        return target.takeIf { it in 0 until itemCount }
    }
}