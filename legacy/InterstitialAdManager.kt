package com.kazmi.adsnextgen

import android.app.Activity
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.google.android.libraries.ads.mobile.sdk.common.AdLoadCallback
import com.google.android.libraries.ads.mobile.sdk.common.AdRequest
import com.google.android.libraries.ads.mobile.sdk.common.FullScreenContentError
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError
import com.google.android.libraries.ads.mobile.sdk.interstitial.InterstitialAd
import com.google.android.libraries.ads.mobile.sdk.interstitial.InterstitialAdEventCallback
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Final outcome of any interstitial flow. Navigate onward in EVERY case.
 */
sealed interface InterstitialResult {
    /** Ad was shown and the user closed it. */
    data object Dismissed : InterstitialResult

    /** Ad loaded but failed to show. */
    data class FailedToShow(val message: String) : InterstitialResult

    /** Ad request failed (no fill, network, SDK init failed...). */
    data object FailedToLoad : InterstitialResult

    /** Ad didn't load within the timeout. User goes onward without it. */
    data object Timeout : InterstitialResult

    /** showPreloaded() called but no fresh preloaded ad exists. */
    data object NotAvailable : InterstitialResult

    /** Another interstitial flow is already loading/showing (double-tap guard). */
    data object Busy : InterstitialResult

    /** Activity finishing/destroyed/in background. Ad is kept in cache for later. */
    data object ActivityUnavailable : InterstitialResult
}

/**
 * Interstitial ads for the Google Mobile Ads Next-Gen SDK.
 *
 * Two separate pipelines:
 *  1. PRELOAD   -> preload(ids) fills a cache, showPreloaded() shows instantly.
 *  2. ON-DEMAND -> showOnDemand() loads now, waits up to [MAX_TIMEOUT_MS], then shows or moves on.
 *
 * Every public function has two forms:
 *  - suspend version  -> for Compose (rememberCoroutineScope) / lifecycleScope
 *  - callback version -> for XML / Java-style code (trailing onComplete lambda)
 */
object InterstitialAdManager {

    private const val TAG = "InterstitialAdManager"

    /** Hard cap for on-demand waiting. User never waits longer than this. */
    const val MAX_TIMEOUT_MS = 8_000L
    private const val MIN_TIMEOUT_MS = 1_000L

    /** Interstitials expire after ~1 hour. Drop them a bit earlier to be safe. */
    private const val AD_EXPIRY_MS = 55 * 60 * 1_000L

    private val exceptionHandler = CoroutineExceptionHandler { _, e ->
        Log.e(TAG, "Unexpected error in interstitial coroutine.", e)
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + exceptionHandler)

    // ---------------- State ----------------

    private class CachedAd(
        val ad: InterstitialAd,
        val loadedAt: Long = SystemClock.elapsedRealtime()
    ) {
        fun isFresh() = SystemClock.elapsedRealtime() - loadedAt < AD_EXPIRY_MS
    }

    /** Preloaded ads, one per ad unit. Separate from on-demand loading. */
    private val preloadedAds = ConcurrentHashMap<String, CachedAd>()

    /** In-flight requests. Separate maps so the two pipelines never steal each other's ads. */
    private val preloadJobs = ConcurrentHashMap<String, Deferred<InterstitialAd?>>()
    private val onDemandJobs = ConcurrentHashMap<String, Deferred<InterstitialAd?>>()

    /** True from the moment a show flow starts (including waiting) until it ends. */
    private val flowActive = AtomicBoolean(false)

    /** True only while the ad is on screen. */
    private val showing = AtomicBoolean(false)

    val isShowing: Boolean get() = showing.get()
    fun isPreloaded(adUnitId: String): Boolean = preloadedAds[adUnitId]?.isFresh() == true
    fun isPreloading(adUnitId: String): Boolean = preloadJobs.containsKey(adUnitId)
    fun isLoadingOnDemand(adUnitId: String): Boolean = onDemandJobs.containsKey(adUnitId)

    // =====================================================================
    // 1) PRELOADING
    // =====================================================================

    /**
     * Preloads one ad per ad unit into the cache. Safe to call repeatedly:
     * skips units that already have a fresh ad or a request in flight.
     * Waits for AdsNextGen initialization automatically.
     */
    fun preload(vararg adUnitIds: String) {
        adUnitIds.forEach { id ->
            if (isPreloaded(id)) {
                log("[$id] Already preloaded. Skipping.")
                return@forEach
            }
            startLoad(preloadJobs, id) {
                if (!awaitSdkReady()) return@startLoad null
                loadAd(id)?.also { cacheAd(id, it) }
            }
        }
    }

    /** Shows a preloaded ad instantly, or returns [InterstitialResult.NotAvailable]. */
    suspend fun showPreloaded(
        activity: Activity,
        adUnitId: String,
        reloadAfterShow: Boolean = true
    ): InterstitialResult {
        if (!flowActive.compareAndSet(false, true)) return InterstitialResult.Busy
        try {
            val ad = pollFreshPreloaded(adUnitId)
            val result = if (ad == null) {
                log("[$adUnitId] No preloaded ad available.")
                InterstitialResult.NotAvailable
            } else {
                present(activity, adUnitId, ad)
            }
            if (reloadAfterShow) preload(adUnitId)
            return result
        } finally {
            flowActive.set(false)
        }
    }

    /** Callback version for XML screens. [onComplete] runs on the main thread. */
    fun showPreloaded(
        activity: Activity,
        adUnitId: String,
        reloadAfterShow: Boolean = true,
        onComplete: (InterstitialResult) -> Unit
    ) {
        scope.launch(Dispatchers.Main.immediate) {
            onComplete(showPreloaded(activity, adUnitId, reloadAfterShow))
        }
    }

    // =====================================================================
    // 2) ON-DEMAND
    // =====================================================================

    /**
     * Shows an interstitial right now:
     *  1. Uses a fresh preloaded ad if one exists (instant).
     *  2. Otherwise loads one (or joins a load already in progress) and waits up to [timeoutMillis].
     *  3. On timeout, the user moves on. The late ad is NOT wasted: it goes into the preload cache.
     *
     * @param timeoutMillis   Clamped to 1000..8000 ms.
     * @param onWaitingForAd  Called on main thread ONLY if we actually have to wait.
     *                        Show your "Loading ad..." dialog here, hide it on result.
     */
    suspend fun showOnDemand(
        activity: Activity,
        adUnitId: String,
        timeoutMillis: Long = MAX_TIMEOUT_MS,
        preloadAfterShow: Boolean = false,
        onWaitingForAd: (() -> Unit)? = null
    ): InterstitialResult {
        if (showing.get() || !flowActive.compareAndSet(false, true)) {
            log("[$adUnitId] Busy: another interstitial flow is active.")
            return InterstitialResult.Busy
        }
        try {
            val timeout = timeoutMillis.coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)

            val ad = pollFreshPreloaded(adUnitId)?.also { log("[$adUnitId] Using preloaded ad.") }
                ?: when (val outcome = awaitOnDemandAd(adUnitId, timeout, onWaitingForAd)) {
                    is LoadOutcome.Loaded -> outcome.ad
                    LoadOutcome.Failed -> return InterstitialResult.FailedToLoad
                    LoadOutcome.TimedOut -> return InterstitialResult.Timeout
                }

            val result = present(activity, adUnitId, ad)
            if (preloadAfterShow) preload(adUnitId)
            return result
        } finally {
            flowActive.set(false)
        }
    }

    /** Callback version for XML screens. [onComplete] runs on the main thread. */
    fun showOnDemand(
        activity: Activity,
        adUnitId: String,
        timeoutMillis: Long = MAX_TIMEOUT_MS,
        preloadAfterShow: Boolean = false,
        onWaitingForAd: (() -> Unit)? = null,
        onComplete: (InterstitialResult) -> Unit
    ) {
        scope.launch(Dispatchers.Main.immediate) {
            onComplete(showOnDemand(activity, adUnitId, timeoutMillis, preloadAfterShow, onWaitingForAd))
        }
    }

    private sealed interface LoadOutcome {
        data class Loaded(val ad: InterstitialAd) : LoadOutcome
        data object Failed : LoadOutcome
        data object TimedOut : LoadOutcome
    }

    private suspend fun awaitOnDemandAd(
        adUnitId: String,
        timeout: Long,
        onWaitingForAd: (() -> Unit)?
    ): LoadOutcome {
        val deferred = startLoad(onDemandJobs, adUnitId) {
            if (!awaitSdkReady()) null else loadAd(adUnitId)
        }

        onWaitingForAd?.let { withContext(Dispatchers.Main.immediate) { it() } }

        // Timeout cancels only the WAITING, not the request itself.
        val ad = withTimeoutOrNull(timeout) { deferred.await() }

        return when {
            ad != null -> LoadOutcome.Loaded(ad)

            // Finished just at the timeout edge: grab the result instead of losing it.
            deferred.isCompleted -> deferred.await()?.let { LoadOutcome.Loaded(it) } ?: LoadOutcome.Failed

            else -> {
                log("[$adUnitId] Timed out after ${timeout}ms. Late ad will be cached.")
                // Hand the still-running request over to the preload cache.
                onDemandJobs.remove(adUnitId, deferred)
                scope.launch { deferred.await()?.let { cacheAd(adUnitId, it) } }
                LoadOutcome.TimedOut
            }
        }
    }

    // =====================================================================
    // SHOWING
    // =====================================================================

    private suspend fun present(
        activity: Activity,
        adUnitId: String,
        ad: InterstitialAd
    ): InterstitialResult = withContext(Dispatchers.Main.immediate) {
        if (!activity.canShowAds()) {
            log("[$adUnitId] Activity not resumed/alive. Keeping ad for later.")
            cacheAd(adUnitId, ad)
            return@withContext InterstitialResult.ActivityUnavailable
        }
        showing.set(true)
        try {
            showAd(activity, ad)
        } finally {
            showing.set(false)
        }
    }

    /** Suspends until the ad is dismissed or fails to show. Must be called on Main. */
    private suspend fun showAd(activity: Activity, ad: InterstitialAd): InterstitialResult =
        suspendCancellableCoroutine { cont ->
            val done = AtomicBoolean(false)
            fun finish(result: InterstitialResult) {
                if (done.compareAndSet(false, true)) cont.resume(result)
            }

            // NOTE: Next-Gen SDK calls these on a BACKGROUND thread.
            ad.adEventCallback = object : InterstitialAdEventCallback {
                override fun onAdShowedFullScreenContent() {
                    log("Interstitial showed.")
                }

                override fun onAdDismissedFullScreenContent() {
                    log("Interstitial dismissed.")
                    finish(InterstitialResult.Dismissed)
                }

                override fun onAdFailedToShowFullScreenContent(fullScreenContentError: FullScreenContentError) {
                    Log.w(TAG, "Interstitial failed to show: ${fullScreenContentError.message}")
                    finish(InterstitialResult.FailedToShow(fullScreenContentError.message))
                }
            }
            ad.show(activity)
        }

    private fun Activity.canShowAds(): Boolean {
        if (isFinishing || isDestroyed) return false
        val owner = this as? LifecycleOwner ?: return true
        return owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
    }

    // =====================================================================
    // LOADING (shared internals)
    // =====================================================================

    /**
     * Returns the in-flight request for [adUnitId] or starts a new one.
     * LAZY start + putIfAbsent = only one request per ad unit, even under races.
     */
    private fun startLoad(
        jobs: ConcurrentHashMap<String, Deferred<InterstitialAd?>>,
        adUnitId: String,
        block: suspend () -> InterstitialAd?
    ): Deferred<InterstitialAd?> {
        jobs[adUnitId]?.let {
            log("[$adUnitId] Already loading. Joining existing request.")
            return it
        }
        val deferred = scope.async(start = CoroutineStart.LAZY) { block() }
        val existing = jobs.putIfAbsent(adUnitId, deferred)
        if (existing != null) {
            deferred.cancel()
            return existing
        }
        deferred.invokeOnCompletion { jobs.remove(adUnitId, deferred) }
        deferred.start()
        return deferred
    }

    /** Converts the SDK's callback API into a suspend function. */
    private suspend fun loadAd(adUnitId: String): InterstitialAd? =
        suspendCancellableCoroutine { cont ->
            val done = AtomicBoolean(false)
            log("[$adUnitId] Loading...")

            InterstitialAd.load(
                AdRequest.Builder(adUnitId).build(),
                object : AdLoadCallback<InterstitialAd> {
                    override fun onAdLoaded(ad: InterstitialAd) {
                        log("[$adUnitId] Loaded.")
                        if (done.compareAndSet(false, true)) cont.resume(ad)
                    }

                    override fun onAdFailedToLoad(adError: LoadAdError) {
                        Log.w(TAG, "[$adUnitId] Failed to load: ${adError.code} ${adError.message}")
                        if (done.compareAndSet(false, true)) cont.resume(null)
                    }
                }
            )
        }

    /** Suspends until AdsNextGen finishes initializing. Returns false if init failed. */
    private suspend fun awaitSdkReady(): Boolean {
        if (AdsNextGen.isInitialized) return true
        if (AdsNextGen.initState.value is AdsNextGen.InitState.NotInitialized) {
            Log.w(TAG, "AdsNextGen.initialize() not called yet. Waiting for it...")
        }
        val state = AdsNextGen.initState.first {
            it is AdsNextGen.InitState.Initialized || it is AdsNextGen.InitState.Failed
        }
        return state is AdsNextGen.InitState.Initialized
    }

    // =====================================================================
    // CACHE
    // =====================================================================

    /** Stores an ad unless a fresh one is already cached for this unit. */
    private fun cacheAd(adUnitId: String, ad: InterstitialAd) {
        preloadedAds.compute(adUnitId) { _, existing ->
            if (existing != null && existing.isFresh()) existing else CachedAd(ad)
        }
    }

    /** Atomically removes and returns a fresh ad. Expired ads are dropped. */
    private fun pollFreshPreloaded(adUnitId: String): InterstitialAd? =
        preloadedAds.remove(adUnitId)?.takeIf { it.isFresh() }?.ad

    /** Clears one unit's cached ad, or all of them. */
    fun clearPreloaded(adUnitId: String? = null) {
        if (adUnitId == null) preloadedAds.clear() else preloadedAds.remove(adUnitId)
    }

    private fun log(message: String) {
        if (AdsNextGen.isLoggingEnabled) Log.d(TAG, message)
    }
}
