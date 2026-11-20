package com.kazmi.dev.adsnextgen

import android.app.Activity
import com.google.android.libraries.ads.mobile.sdk.common.AdEventCallback
import com.google.android.libraries.ads.mobile.sdk.common.AdRequest
import com.google.android.libraries.ads.mobile.sdk.common.PreloadConfiguration
import com.google.android.libraries.ads.mobile.sdk.interstitial.InterstitialAd
import com.google.android.libraries.ads.mobile.sdk.interstitial.InterstitialAdEventCallback
import com.google.android.libraries.ads.mobile.sdk.interstitial.InterstitialAdPreloader

/**
 * Created by Kazmi Dev.
 *
 * Interstitial functions exposed on [AdsManager].
 */
interface InterstitialAds {

    /** Loads silently in the background. No loading UI, nothing is shown. */
    fun preloadInterstitial(count: Int = 1, adUnitId: String? = null)

    /** Loads an ad right now and shows it when ready, waiting at most [timeoutMillis]. */
    fun showInterstitialOnDemand(
        activity: Activity,
        adUnitId: String? = null,
        timeoutMillis: Long = AD_TIMEOUT_MS,
        onEvent: (AdEvent) -> Unit
    )

    /**
     * Shows a preloaded ad instantly. With no ad available it falls back to on-demand loading
     * when [loadOnDemandIfUnavailable] is true, otherwise it reports [AdError.AD_NOT_AVAILABLE].
     */
    fun showPreloadedInterstitial(
        activity: Activity,
        adUnitId: String? = null,
        loadOnDemandIfUnavailable: Boolean = true,
        timeoutMillis: Long = AD_TIMEOUT_MS,
        onEvent: (AdEvent) -> Unit
    )
}

internal object InterstitialAdManager : InterstitialAds {

    private val controller = FullScreenController(InterstitialFormat, { AdsManager.config.interstitialId })

    override fun preloadInterstitial(count: Int, adUnitId: String?) =
        controller.preload(adUnitId, count)

    override fun showInterstitialOnDemand(
        activity: Activity,
        adUnitId: String?,
        timeoutMillis: Long,
        onEvent: (AdEvent) -> Unit
    ) = controller.showOnDemand(activity, adUnitId, timeoutMillis, onEvent)

    override fun showPreloadedInterstitial(
        activity: Activity,
        adUnitId: String?,
        loadOnDemandIfUnavailable: Boolean,
        timeoutMillis: Long,
        onEvent: (AdEvent) -> Unit
    ) = controller.showPreloaded(activity, adUnitId, loadOnDemandIfUnavailable, timeoutMillis, onEvent)
}

private object InterstitialFormat : FullScreenFormat<InterstitialAd> {

    override val expiryMs = ONE_HOUR_EXPIRY_MS

    override suspend fun load(adUnitId: String): InterstitialAd? =
        InterstitialAd.load(AdRequest.Builder(adUnitId).build()).adOrNull()

    override fun startPreload(adUnitId: String, bufferSize: Int) {
        InterstitialAdPreloader.start(
            adUnitId,
            PreloadConfiguration(AdRequest.Builder(adUnitId).build(), bufferSize)
        )
    }

    override fun poll(adUnitId: String): InterstitialAd? = InterstitialAdPreloader.pollAd(adUnitId)

    override fun show(ad: InterstitialAd, activity: Activity, onEvent: (AdEvent) -> Unit) {
        ad.adEventCallback = object : InterstitialAdEventCallback, AdEventCallback by AdEventSink(onEvent) {}
        ad.show(activity)
    }
}
