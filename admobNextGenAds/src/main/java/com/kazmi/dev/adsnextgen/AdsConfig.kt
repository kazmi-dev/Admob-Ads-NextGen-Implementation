package com.kazmi.dev.adsnextgen

import android.app.Activity

/** Longest time a show call waits for an ad to load. */
const val AD_TIMEOUT_MS = 8_000L

/**
 * Created by Kazmi Dev.
 *
 * Google's published test ids. They are the defaults of [AdsConfig], so a fresh
 * integration works immediately. Pass real ids for release builds.
 */
object TestAdIds {
    const val APP_ID = "ca-app-pub-3940256099942544~3347511713"
    const val INTERSTITIAL = "ca-app-pub-3940256099942544/1033173712"
    const val REWARDED = "ca-app-pub-3940256099942544/5224354917"
    const val REWARDED_INTERSTITIAL = "ca-app-pub-3940256099942544/5354046379"
    const val APP_OPEN = "ca-app-pub-3940256099942544/9257395921"
    const val BANNER = "ca-app-pub-3940256099942544/9214589741"
    const val NATIVE = "ca-app-pub-3940256099942544/2247696110"
}

/**
 * Created by Kazmi Dev.
 *
 * Configuration given once to [AdsManager.initialize]. Every id is optional: pass only the
 * app id and supply an ad unit id with each call, or pass all real ids here.
 *
 * @param requestConsent runs the UMP consent flow before the SDK starts; skipped when unavailable.
 * @param waitForMediation false marks the SDK ready right after initialize is called.
 * @param appOpenExcludedActivities activities (splash etc.) where App Open never shows on foreground.
 */
data class AdsConfig(
    val appId: String = TestAdIds.APP_ID,
    val interstitialId: String = TestAdIds.INTERSTITIAL,
    val rewardedId: String = TestAdIds.REWARDED,
    val rewardedInterstitialId: String = TestAdIds.REWARDED_INTERSTITIAL,
    val appOpenId: String = TestAdIds.APP_OPEN,
    val bannerId: String = TestAdIds.BANNER,
    val nativeId: String = TestAdIds.NATIVE,
    val requestConsent: Boolean = true,
    val waitForMediation: Boolean = false,
    val testDeviceIds: List<String> = emptyList(),
    val appOpenEnabled: Boolean = true,
    val appOpenExcludedActivities: Set<Class<out Activity>> = emptySet()
)
