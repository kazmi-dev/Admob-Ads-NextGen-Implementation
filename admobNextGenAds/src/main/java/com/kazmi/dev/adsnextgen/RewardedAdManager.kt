package com.kazmi.dev.adsnextgen

import android.app.Activity
import com.google.android.libraries.ads.mobile.sdk.common.AdEventCallback
import com.google.android.libraries.ads.mobile.sdk.common.AdRequest
import com.google.android.libraries.ads.mobile.sdk.common.PreloadConfiguration
import com.google.android.libraries.ads.mobile.sdk.rewarded.OnUserEarnedRewardListener
import com.google.android.libraries.ads.mobile.sdk.rewarded.RewardItem
import com.google.android.libraries.ads.mobile.sdk.rewarded.RewardedAd
import com.google.android.libraries.ads.mobile.sdk.rewarded.RewardedAdEventCallback
import com.google.android.libraries.ads.mobile.sdk.rewarded.RewardedAdPreloader

/**
 * Created by Kazmi Dev.
 *
 * Rewarded functions exposed on [AdsManager]. [AdEvent.Rewarded] is sent when the user earns the reward.
 */
interface RewardedAds {

    /** Loads silently in the background. No loading UI, nothing is shown. */
    fun preloadRewarded(count: Int = 1, adUnitId: String? = null)

    /** Loads an ad right now and shows it when ready, waiting at most [timeoutMillis]. */
    fun showRewardedOnDemand(
        activity: Activity,
        adUnitId: String? = null,
        timeoutMillis: Long = AD_TIMEOUT_MS,
        onEvent: (AdEvent) -> Unit
    )

    /**
     * Shows a preloaded ad instantly. With no ad available it falls back to on-demand loading
     * when [loadOnDemandIfUnavailable] is true, otherwise it reports [AdError.AD_NOT_AVAILABLE].
     */
    fun showPreloadedRewarded(
        activity: Activity,
        adUnitId: String? = null,
        loadOnDemandIfUnavailable: Boolean = true,
        timeoutMillis: Long = AD_TIMEOUT_MS,
        onEvent: (AdEvent) -> Unit
    )
}

internal object RewardedAdManager : RewardedAds {

    private val controller = FullScreenController(RewardedFormat, { AdsManager.config.rewardedId })

    override fun preloadRewarded(count: Int, adUnitId: String?) =
        controller.preload(adUnitId, count)

    override fun showRewardedOnDemand(
        activity: Activity,
        adUnitId: String?,
        timeoutMillis: Long,
        onEvent: (AdEvent) -> Unit
    ) = controller.showOnDemand(activity, adUnitId, timeoutMillis, onEvent)

    override fun showPreloadedRewarded(
        activity: Activity,
        adUnitId: String?,
        loadOnDemandIfUnavailable: Boolean,
        timeoutMillis: Long,
        onEvent: (AdEvent) -> Unit
    ) = controller.showPreloaded(activity, adUnitId, loadOnDemandIfUnavailable, timeoutMillis, onEvent)
}

private object RewardedFormat : FullScreenFormat<RewardedAd> {

    override val expiryMs = ONE_HOUR_EXPIRY_MS

    override suspend fun load(adUnitId: String): RewardedAd? =
        RewardedAd.load(AdRequest.Builder(adUnitId).build()).adOrNull()

    override fun startPreload(adUnitId: String, bufferSize: Int) {
        RewardedAdPreloader.start(
            adUnitId,
            PreloadConfiguration(AdRequest.Builder(adUnitId).build(), bufferSize)
        )
    }

    override fun poll(adUnitId: String): RewardedAd? = RewardedAdPreloader.pollAd(adUnitId)

    override fun show(ad: RewardedAd, activity: Activity, onEvent: (AdEvent) -> Unit) {
        ad.adEventCallback = object : RewardedAdEventCallback, AdEventCallback by AdEventSink(onEvent) {}
        ad.show(activity, object : OnUserEarnedRewardListener {
            override fun onUserEarnedReward(reward: RewardItem) = onEvent(AdEvent.Rewarded(reward))
        })
    }
}
