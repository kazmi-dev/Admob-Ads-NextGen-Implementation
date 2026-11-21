package com.kazmi.dev.adsnextgen

import com.google.android.libraries.ads.mobile.sdk.common.AdValue
import com.google.android.libraries.ads.mobile.sdk.rewarded.RewardItem

/**
 * Created by Kazmi Dev.
 *
 * Events delivered to the caller of every full-screen show function.
 * [Dismissed] and [Failed] are terminal: exactly one of them arrives per show call,
 * so navigate onward when either is received. [Rewarded] is only sent by rewarded formats.
 */
sealed interface AdEvent {
    data object Showed : AdEvent
    data object Clicked : AdEvent
    data object Impression : AdEvent
    data class Paid(val value: AdValue) : AdEvent
    data class Rewarded(val item: RewardItem) : AdEvent
    data object Dismissed : AdEvent
    data class Failed(val error: AdError) : AdEvent
}
