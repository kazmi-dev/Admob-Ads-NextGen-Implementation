package com.kazmi.dev.adsnextgen

import android.app.Activity
import com.google.android.libraries.ads.mobile.sdk.common.AdEventCallback
import com.google.android.libraries.ads.mobile.sdk.common.AdRequest
import com.google.android.libraries.ads.mobile.sdk.common.PreloadConfiguration
import com.google.android.libraries.ads.mobile.sdk.rewarded.OnUserEarnedRewardListener
import com.google.android.libraries.ads.mobile.sdk.rewarded.RewardItem
import com.google.android.libraries.ads.mobile.sdk.rewardedinterstitial.RewardedInterstitialAd
import com.google.android.libraries.ads.mobile.sdk.rewardedinterstitial.RewardedInterstitialAdEventCallback
import com.google.android.libraries.ads.mobile.sdk.rewardedinterstitial.RewardedInterstitialAdPreloader

/**
 * Created by Kazmi Dev.
 *
 * Rewarded interstitial functions exposed on [AdsManager]. Show only after the user has been
 * told about the reward. [AdEvent.Rewarded] is sent when the user earns it.
 */
interface RewardedInterstitialAds {

    /** Loads silently in the background. No loading UI, nothing is shown. */
    fun preloadRewardedInterstitial(count: Int = 1, adUnitId: String? = null)

    /** Loads an ad right now and shows it when ready, waiting at most [timeoutMillis]. */
    fun showRewardedInterstitialOnDemand(
        activity: Activity,
        adUnitId: String? = null,
        timeoutMillis: Long = AD_TIMEOUT_MS,
        onEvent: (AdEvent) -> Unit
    )

    /**
     * Shows a preloaded ad instantly. With no ad available it falls back to on-demand loading
     * when [loadOnDemandIfUnavailable] is true, otherwise it reports [AdError.AD_NOT_AVAILABLE].
     */
    fun showPreloadedRewardedInterstitial(
        activity: Activity,
        adUnitId: String? = null,
        loadOnDemandIfUnavailable: Boolean = true,
        timeoutMillis: Long = AD_TIMEOUT_MS,
        onEvent: (AdEvent) -> Unit
    )
}

internal object RewardedInterstitialAdManager : RewardedInterstitialAds {

    private val controller =
        FullScreenController(RewardedInterstitialFormat, { AdsManager.config.rewardedInterstitialId })

    override fun preloadRewardedInterstitial(count: Int, adUnitId: String?) =
        controller.preload(adUnitId, count)

    override fun showRewardedInterstitialOnDemand(
        activity: Activity,
        adUnitId: String?,
        timeoutMillis: Long,
        onEvent: (AdEvent) -> Unit
    ) = controller.showOnDemand(activity, adUnitId, timeoutMillis, onEvent)

    override fun showPreloadedRewardedInterstitial(
        activity: Activity,
        adUnitId: String?,
        loadOnDemandIfUnavailable: Boolean,
        timeoutMillis: Long,
        onEvent: (AdEvent) -> Unit
    ) = controller.showPreloaded(activity, adUnitId, loadOnDemandIfUnavailable, timeoutMillis, onEvent)
}

private object RewardedInterstitialFormat : FullScreenFormat<RewardedInterstitialAd> {

    override val expiryMs = ONE_HOUR_EXPIRY_MS

    override suspend fun load(adUnitId: String): RewardedInterstitialAd? =
        RewardedInterstitialAd.load(AdRequest.Builder(adUnitId).build()).adOrNull()

    override fun startPreload(adUnitId: String, bufferSize: Int) {
        RewardedInterstitialAdPreloader.start(
            adUnitId,
            PreloadConfiguration(AdRequest.Builder(adUnitId).build(), bufferSize)
        )
    }

    override fun poll(adUnitId: String): RewardedInterstitialAd? =
        RewardedInterstitialAdPreloader.pollAd(adUnitId)

    override fun show(ad: RewardedInterstitialAd, activity: Activity, onEvent: (AdEvent) -> Unit) {
        ad.adEventCallback =
            object : RewardedInterstitialAdEventCallback, AdEventCallback by AdEventSink(onEvent) {}
        ad.show(activity, object : OnUserEarnedRewardListener {
            override fun onUserEarnedReward(reward: RewardItem) = onEvent(AdEvent.Rewarded(reward))
        })
    }
}
