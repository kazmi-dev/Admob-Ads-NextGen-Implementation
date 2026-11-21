package com.kazmi.dev.adsnextgen

import android.os.SystemClock
import com.google.android.libraries.ads.mobile.sdk.common.PreloadConfiguration
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAd
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdLoadResult
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdLoader
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdPreloader
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Created by Kazmi Dev.
 *
 * Native functions exposed on [AdsManager]. Native views take a preloaded ad when one exists.
 */
interface NativeAds {

    /** Loads silently in the background so native views can show instantly. */
    fun preloadNative(count: Int = 1, adUnitId: String? = null)
}

internal sealed interface NativeOutcome {
    class Ready(val ad: NativeAd) : NativeOutcome
    class Failed(val error: AdError) : NativeOutcome
}

internal object NativeAdManager : NativeAds {

    private class LateAd(val ad: NativeAd, val loadedAt: Long = SystemClock.elapsedRealtime())

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val lateAds = HashMap<String, LateAd>()
    private val wantedPreloads = HashMap<String, Int>()

    init {
        Connectivity.doOnAvailable { scope.launch { resumePreloads() } }
    }

    override fun preloadNative(count: Int, adUnitId: String?) {
        val id = resolve(adUnitId) ?: return
        wantedPreloads[id] = count
        scope.launch { startPreload(id, count) }
    }

    suspend fun obtain(adUnitId: String?, timeoutMillis: Long = AD_TIMEOUT_MS): NativeOutcome {
        val id = resolve(adUnitId) ?: return NativeOutcome.Failed(AdError.INVALID_AD_UNIT_ID)
        takeReady(id)?.let { return NativeOutcome.Ready(it) }
        if (!Connectivity.isConnected) return NativeOutcome.Failed(AdError.NO_INTERNET)

        val request = scope.async {
            AdsManager.awaitInitialized()
            load(id)
        }
        var timedOut = true
        val ad = withTimeoutOrNull(timeoutMillis) { request.await().also { timedOut = false } }

        return when {
            ad != null -> NativeOutcome.Ready(ad)
            timedOut -> {
                scope.launch { request.await()?.let { keepLate(id, it) } }
                NativeOutcome.Failed(AdError.AD_LOAD_TIMEOUT)
            }
            else -> NativeOutcome.Failed(AdError.AD_FAILED_TO_LOAD)
        }
    }

    private suspend fun startPreload(id: String, count: Int) {
        if (!Connectivity.isConnected || !AdsManager.isAdsEnabled) return
        AdsManager.awaitInitialized()
        NativeAdPreloader.start(id, PreloadConfiguration(buildRequest(id), count))
    }

    private suspend fun resumePreloads() {
        wantedPreloads.toList().forEach { (id, count) -> startPreload(id, count) }
    }

    private suspend fun load(id: String): NativeAd? =
        (NativeAdLoader.load(buildRequest(id)) as? NativeAdLoadResult.NativeAdSuccess)?.ad

    private fun takeReady(id: String): NativeAd? {
        lateAds.remove(id)
            ?.takeIf { SystemClock.elapsedRealtime() - it.loadedAt < ONE_HOUR_EXPIRY_MS }
            ?.let { return it.ad }
        if (!AdsManager.isInitialized.value) return null
        return (NativeAdPreloader.pollAd(id) as? NativeAdLoadResult.NativeAdSuccess)?.ad
    }

    private fun keepLate(id: String, ad: NativeAd) {
        lateAds.put(id, LateAd(ad))?.ad?.destroy()
    }

    private fun buildRequest(id: String) =
        NativeAdRequest.Builder(id, listOf(NativeAd.NativeAdType.NATIVE)).build()

    private fun resolve(adUnitId: String?): String? =
        adUnitId?.takeIf { it.isNotBlank() } ?: AdsManager.config.nativeId.takeIf { it.isNotBlank() }
}
