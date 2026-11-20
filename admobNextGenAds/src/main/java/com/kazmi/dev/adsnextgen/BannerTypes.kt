package com.kazmi.dev.adsnextgen

import com.google.android.libraries.ads.mobile.sdk.common.AdValue

/**
 * Created by Kazmi Dev.
 *
 * Banner sizes. The adaptive sizes use the width of the banner view; the others are fixed.
 */
enum class BannerSize {
    ANCHORED_ADAPTIVE,
    INLINE_ADAPTIVE,
    BANNER,
    FULL_BANNER,
    LARGE_BANNER,
    LEADERBOARD,
    MEDIUM_RECTANGLE,
    FLUID
}

/** Where a collapsible banner expands from. */
enum class BannerCollapse(internal val value: String) {
    TOP("top"),
    BOTTOM("bottom")
}

/**
 * Created by Kazmi Dev.
 *
 * Events delivered by a banner view. [Failed] hides the view so no blank space is left.
 */
sealed interface BannerEvent {
    data object Loaded : BannerEvent
    data class Failed(val error: AdError) : BannerEvent
    data object Clicked : BannerEvent
    data object Impression : BannerEvent
    data class Paid(val value: AdValue) : BannerEvent
}
