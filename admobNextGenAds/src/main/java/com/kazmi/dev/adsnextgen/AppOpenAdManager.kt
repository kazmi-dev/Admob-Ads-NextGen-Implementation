package com.kazmi.dev.adsnextgen

import android.app.Activity
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.android.libraries.ads.mobile.sdk.appopen.AppOpenAd
import com.google.android.libraries.ads.mobile.sdk.appopen.AppOpenAdEventCallback
import com.google.android.libraries.ads.mobile.sdk.appopen.AppOpenAdPreloader
import com.google.android.libraries.ads.mobile.sdk.common.AdEventCallback
import com.google.android.libraries.ads.mobile.sdk.common.AdRequest
import com.google.android.libraries.ads.mobile.sdk.common.PreloadConfiguration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/**
 * Created by Kazmi Dev.
 *
 * App Open functions exposed on [AdsManager]. When the app returns to the foreground a preloaded
 * App Open ad is shown automatically, except on excluded activities. Interstitial and rewarded
 * ads always have priority over it.
 */
interface AppOpenAds {

    /** Turns the automatic foreground App Open ad on or off. */
    var isAppOpenEnabled: Boolean

    /** Activities (splash etc.) where the automatic App Open ad never shows. */
    fun excludeFromAppOpen(vararg activities: Class<out Activity>)

    /** Loads silently in the background. No loading UI, nothing is shown. */
    fun preloadAppOpen(count: Int = 1, adUnitId: String? = null)

    /** Loads an ad right now and shows it when ready, waiting at most [timeoutMillis]. */
    fun showAppOpenOnDemand(
        activity: Activity,
        adUnitId: String? = null,
        timeoutMillis: Long = AD_TIMEOUT_MS,
        onEvent: (AdEvent) -> Unit
    )

    /**
     * Shows a preloaded ad instantly. With no ad available it falls back to on-demand loading
     * when [loadOnDemandIfUnavailable] is true, otherwise it reports [AdError.AD_NOT_AVAILABLE].
     */
    fun showPreloadedAppOpen(
        activity: Activity,
        adUnitId: String? = null,
        loadOnDemandIfUnavailable: Boolean = true,
        timeoutMillis: Long = AD_TIMEOUT_MS,
        onEvent: (AdEvent) -> Unit
    )
}

internal object AppOpenAdManager : AppOpenAds, LifecycleEventObserver {

    private const val COOLDOWN_MS = 15_000L

    private val controller =
        FullScreenController(AppOpenFormat, { AdsManager.config.appOpenId }, isHighPriority = false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val excludedActivities = HashSet<Class<out Activity>>()
    private var lastShownAt = -COOLDOWN_MS
    private var isObserving = false

    override var isAppOpenEnabled = true

    fun start() {
        if (isObserving) return
        isObserving = true
        isAppOpenEnabled = AdsManager.config.appOpenEnabled
        excludedActivities += AdsManager.config.appOpenExcludedActivities
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        preloadAppOpen()
    }

    override fun excludeFromAppOpen(vararg activities: Class<out Activity>) {
        excludedActivities += activities
    }

    override fun preloadAppOpen(count: Int, adUnitId: String?) = controller.preload(adUnitId, count)

    override fun showAppOpenOnDemand(
        activity: Activity,
        adUnitId: String?,
        timeoutMillis: Long,
        onEvent: (AdEvent) -> Unit
    ) = controller.showOnDemand(activity, adUnitId, timeoutMillis, onEvent)

    override fun showPreloadedAppOpen(
        activity: Activity,
        adUnitId: String?,
        loadOnDemandIfUnavailable: Boolean,
        timeoutMillis: Long,
        onEvent: (AdEvent) -> Unit
    ) = controller.showPreloaded(activity, adUnitId, loadOnDemandIfUnavailable, timeoutMillis, onEvent)

    override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
        if (event != Lifecycle.Event.ON_START || !isAppOpenEnabled) return
        scope.launch {
            yield()
            showOnForeground()
        }
    }

    private fun showOnForeground() {
        val activity = ActivityTracker.current ?: return
        if (activity.javaClass in excludedActivities) return
        if (SystemClock.elapsedRealtime() - lastShownAt < COOLDOWN_MS) return

        showPreloadedAppOpen(activity, loadOnDemandIfUnavailable = false) { event ->
            when {
                event is AdEvent.Dismissed -> lastShownAt = SystemClock.elapsedRealtime()
                event is AdEvent.Failed && event.error == AdError.AD_NOT_AVAILABLE -> preloadAppOpen()
            }
        }
    }
}

private object AppOpenFormat : FullScreenFormat<AppOpenAd> {

    override val expiryMs = APP_OPEN_EXPIRY_MS

    override suspend fun load(adUnitId: String): AppOpenAd? =
        AppOpenAd.load(AdRequest.Builder(adUnitId).build()).adOrNull()

    override fun startPreload(adUnitId: String, bufferSize: Int) {
        AppOpenAdPreloader.start(
            adUnitId,
            PreloadConfiguration(AdRequest.Builder(adUnitId).build(), bufferSize)
        )
    }

    override fun poll(adUnitId: String): AppOpenAd? = AppOpenAdPreloader.pollAd(adUnitId)

    override fun show(ad: AppOpenAd, activity: Activity, onEvent: (AdEvent) -> Unit) {
        ad.adEventCallback = object : AppOpenAdEventCallback, AdEventCallback by AdEventSink(onEvent) {}
        ad.show(activity)
    }
}
