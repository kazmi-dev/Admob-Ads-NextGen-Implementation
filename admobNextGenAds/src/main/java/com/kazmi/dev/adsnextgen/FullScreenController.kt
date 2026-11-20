package com.kazmi.dev.adsnextgen

import android.app.Activity
import android.os.SystemClock
import com.google.android.libraries.ads.mobile.sdk.common.Ad
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Created by Kazmi Dev.
 *
 * Shared preload, on-demand and show logic for every full-screen format.
 *
 * Preloading uses the SDK preloaders. On-demand loads with a suspend call and waits at most
 * [AD_TIMEOUT_MS]; an ad that arrives after the timeout is kept in the late-ad slot and used
 * by the next preloaded show, so a loaded ad is never lost. Main thread only.
 */
internal class FullScreenController<T : Ad>(
    private val format: FullScreenFormat<T>,
    private val defaultAdUnitId: () -> String,
    private val isHighPriority: Boolean = true
) {

    private class LateAd<T>(val ad: T, val loadedAt: Long = SystemClock.elapsedRealtime())

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val lateAds = HashMap<String, LateAd<T>>()
    private val wantedPreloads = HashMap<String, Int>()
    private var isBusy = false

    init {
        Connectivity.doOnAvailable { scope.launch { resumePreloads() } }
    }

    fun preload(adUnitId: String?, count: Int) {
        val id = resolve(adUnitId) ?: return
        wantedPreloads[id] = count
        scope.launch { startPreload(id, count) }
    }

    fun showOnDemand(
        activity: Activity,
        adUnitId: String?,
        timeoutMillis: Long,
        onEvent: (AdEvent) -> Unit
    ) {
        val id = begin(adUnitId, onEvent) ?: return
        if (!Connectivity.isConnected) return finish(onEvent, AdEvent.Failed(AdError.NO_INTERNET))

        scope.launch {
            val request = scope.async {
                AdsManager.awaitInitialized()
                format.load(id)
            }
            var timedOut = true
            val ad = withTimeoutOrNull(timeoutMillis.coerceIn(MIN_TIMEOUT_MS, AD_TIMEOUT_MS)) {
                request.await().also { timedOut = false }
            }

            when {
                ad != null -> present(activity, id, ad, onEvent)
                timedOut -> {
                    scope.launch { request.await()?.let { keepLate(id, it) } }
                    finish(onEvent, AdEvent.Failed(AdError.AD_LOAD_TIMEOUT))
                }
                else -> finish(onEvent, AdEvent.Failed(AdError.AD_FAILED_TO_LOAD))
            }
        }
    }

    fun showPreloaded(
        activity: Activity,
        adUnitId: String?,
        loadOnDemandIfUnavailable: Boolean,
        timeoutMillis: Long,
        onEvent: (AdEvent) -> Unit
    ) {
        val id = begin(adUnitId, onEvent) ?: return
        val ad = takeReady(id)

        when {
            ad != null -> present(activity, id, ad, onEvent)
            loadOnDemandIfUnavailable -> {
                release()
                showOnDemand(activity, id, timeoutMillis, onEvent)
            }
            else -> finish(onEvent, AdEvent.Failed(AdError.AD_NOT_AVAILABLE))
        }
    }

    private fun begin(adUnitId: String?, onEvent: (AdEvent) -> Unit): String? {
        val id = resolve(adUnitId)
        when {
            !AdsManager.isAdsEnabled -> onEvent(AdEvent.Failed(AdError.PRODUCT_PURCHASED))
            id == null -> onEvent(AdEvent.Failed(AdError.INVALID_AD_UNIT_ID))
            isBusy -> Unit
            AdCoordinator.isShowing -> onEvent(AdEvent.Failed(AdError.ANOTHER_AD_SHOWING))
            !isHighPriority && !AdCoordinator.canShowAppOpen ->
                onEvent(AdEvent.Failed(AdError.ANOTHER_AD_SHOWING))
            else -> {
                isBusy = true
                AdCoordinator.beginFlow(isHighPriority)
                return id
            }
        }
        return null
    }

    private fun present(activity: Activity, id: String, ad: T, onEvent: (AdEvent) -> Unit) {
        when {
            activity.isFinishing || activity.isDestroyed -> {
                keepLate(id, ad)
                finish(onEvent, AdEvent.Failed(AdError.ACTIVITY_UNAVAILABLE))
            }
            !isHighPriority && !AdCoordinator.canShowAppOpen -> {
                keepLate(id, ad)
                finish(onEvent, AdEvent.Failed(AdError.ANOTHER_AD_SHOWING))
            }
            !AdCoordinator.tryStartShowing() -> {
                keepLate(id, ad)
                finish(onEvent, AdEvent.Failed(AdError.ANOTHER_AD_SHOWING))
            }
            else -> format.show(ad, activity) { event -> scope.launch { handle(event, onEvent) } }
        }
    }

    private fun handle(event: AdEvent, onEvent: (AdEvent) -> Unit) {
        when (event) {
            AdEvent.Dismissed, is AdEvent.Failed -> {
                AdCoordinator.stopShowing()
                finish(onEvent, event)
            }
            else -> onEvent(event)
        }
    }

    private fun finish(onEvent: (AdEvent) -> Unit, event: AdEvent) {
        release()
        onEvent(event)
    }

    private fun release() {
        isBusy = false
        AdCoordinator.endFlow(isHighPriority)
    }

    private suspend fun startPreload(id: String, count: Int) {
        if (!Connectivity.isConnected || !AdsManager.isAdsEnabled) return
        AdsManager.awaitInitialized()
        format.startPreload(id, count)
    }

    private suspend fun resumePreloads() {
        wantedPreloads.toList().forEach { (id, count) -> startPreload(id, count) }
    }

    private fun takeReady(id: String): T? {
        lateAds.remove(id)?.takeIf { isFresh(it) }?.let { return it.ad }
        return if (AdsManager.isInitialized.value) format.poll(id) else null
    }

    private fun keepLate(id: String, ad: T) {
        lateAds.put(id, LateAd(ad))?.ad?.destroy()
    }

    private fun isFresh(lateAd: LateAd<T>) =
        SystemClock.elapsedRealtime() - lateAd.loadedAt < format.expiryMs

    private fun resolve(adUnitId: String?): String? =
        adUnitId?.takeIf { it.isNotBlank() } ?: defaultAdUnitId().takeIf { it.isNotBlank() }

    private companion object {
        const val MIN_TIMEOUT_MS = 1_000L
    }
}
