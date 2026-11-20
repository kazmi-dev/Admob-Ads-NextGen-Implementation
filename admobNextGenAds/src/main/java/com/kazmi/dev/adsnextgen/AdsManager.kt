package com.kazmi.dev.adsnextgen

import android.app.Activity
import android.app.Application
import android.content.Context
import com.google.android.libraries.ads.mobile.sdk.MobileAds
import com.google.android.libraries.ads.mobile.sdk.common.RequestConfiguration
import com.google.android.libraries.ads.mobile.sdk.initialization.InitializationConfig
import com.google.android.libraries.ads.mobile.sdk.initialization.InitializationStatus
import com.google.android.libraries.ads.mobile.sdk.initialization.OnAdapterInitializationCompleteListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Created by Kazmi Dev.
 *
 * Single public entry point of the Google NextGen Ads utility.
 * Call [initialize] once from the Application class, then call every ad function directly on
 * AdsManager. The individual ad managers are internal and never used by the app.
 */
object AdsManager :
    InterstitialAds by InterstitialAdManager,
    RewardedAds by RewardedAdManager,
    RewardedInterstitialAds by RewardedInterstitialAdManager,
    AppOpenAds by AppOpenAdManager,
    NativeAds by NativeAdManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val isStarted = AtomicBoolean(false)
    private val _isInitialized = MutableStateFlow(false)

    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    /** Set to false for premium users: every ad call then ends with [AdError.PRODUCT_PURCHASED]. */
    @Volatile
    var isAdsEnabled = true

    var config = AdsConfig()
        private set

    val isFullScreenAdShowing: Boolean get() = AdCoordinator.isShowing

    /**
     * Runs consent (when enabled), then initializes the SDK. [onInitialized] is called once the
     * SDK is ready, which is right after initialize is called unless
     * [AdsConfig.waitForMediation] is true.
     */
    fun initialize(application: Application, config: AdsConfig = AdsConfig(), onInitialized: () -> Unit = {}) {
        if (isStarted.compareAndSet(false, true)) {
            this.config = config
            ActivityTracker.register(application)
            Connectivity.register(application)
            scope.launch { startSdk(application, config) }
        }
        scope.launch {
            awaitInitialized()
            onInitialized()
        }
    }

    suspend fun awaitInitialized() {
        _isInitialized.first { it }
    }

    fun isPrivacyOptionsRequired(context: Context): Boolean =
        ConsentManager.isPrivacyOptionsRequired(context)

    fun showPrivacyOptions(activity: Activity, onDismissed: () -> Unit = {}) =
        ConsentManager.showPrivacyOptions(activity, onDismissed)

    private suspend fun startSdk(application: Application, config: AdsConfig) {
        if (config.requestConsent && !ConsentManager.gather(ActivityTracker.awaitActivity())) return

        val requestConfiguration = RequestConfiguration.Builder()
            .setTestDeviceIds(config.testDeviceIds)
            .build()
        val initializationConfig = InitializationConfig.Builder(config.appId)
            .setRequestConfiguration(requestConfiguration)
            .build()

        withContext(Dispatchers.IO) {
            if (config.waitForMediation) {
                suspendCancellableCoroutine { continuation ->
                    MobileAds.initialize(application, initializationConfig, object : OnAdapterInitializationCompleteListener {
                        override fun onAdapterInitializationComplete(status: InitializationStatus) {
                            continuation.resume(Unit)
                        }
                    })
                }
            } else {
                MobileAds.initialize(application, initializationConfig)
            }
        }

        _isInitialized.value = true
        AppOpenAdManager.start()
    }
}
