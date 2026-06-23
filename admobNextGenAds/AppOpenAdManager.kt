import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.android.libraries.ads.mobile.sdk.appopen.AppOpenAd
import com.google.android.libraries.ads.mobile.sdk.appopen.AppOpenAdEventCallback
import com.google.android.libraries.ads.mobile.sdk.common.AdLoadResult
import com.google.android.libraries.ads.mobile.sdk.common.AdRequest
import com.google.android.libraries.ads.mobile.sdk.common.AdValue
import com.google.android.libraries.ads.mobile.sdk.common.FullScreenContentError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.lang.ref.WeakReference
import kotlin.time.Duration.Companion.milliseconds

object AppOpenAdManager : Application.ActivityLifecycleCallbacks, LifecycleEventObserver {

    private var appOpenAd: AppOpenAd? = null
    private var isAdLoading: Boolean = false

    var isAppOpenShowing: Boolean = false
        private set

    // Hold a WeakReference to avoid pinning the Activity in memory
    private var currentActivityRef: WeakReference<Activity>? = null

    // Immediate Main Dispatcher for zero-lag UI updates and state mutations
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    enum class AdEventCallback{
        SHOWED,
        DISMISSED
    }

    // Expose a read-only SharedFlow for screens to collect
    private val _adEventFlow = MutableSharedFlow<AdEventCallback>(extraBufferCapacity = 1)
    val adEventFlow = _adEventFlow.asSharedFlow()

    //init in application class
    fun init(application: Application) {
        ProcessLifecycleOwner.get().lifecycle.removeObserver(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        application.unregisterActivityLifecycleCallbacks(this)
        application.registerActivityLifecycleCallbacks(this)
    }

    private fun loadAppOpenAdWithTimeOut(
        activity: Activity,
        adUnitId: String,
        duration: Long = 8000,
        isInternetConnected: Boolean = true,
        isProductPurchased: Boolean = false,
        callback: (AdError?) -> Unit
    ) {
        // Initial Structural Guards
        if (isAdLoading || isAppOpenShowing) { return }
        if (activity.isFinishing || activity.isDestroyed) { callback(AdError.AD_FAILED_TO_LOAD); return }
        if (isProductPurchased) { callback(AdError.PRODUCT_PURCHASED); return }
        if (!isInternetConnected) { callback(AdError.NO_INTERNET); return }

        isAdLoading = true

        val activityRef = WeakReference(activity)
        val adRequest = AdRequest.Builder(adUnitId).build()

        scope.launch {
            // Suspending Wrapper with Cooperative Native Timeout
            val adLoadResult = withTimeoutOrNull(duration.milliseconds) {
                AppOpenAd.load(adRequest)
            }

            isAdLoading = false

            // Activity Lifespan Safety Checkout Post-Suspension
            val validActivity = activityRef.get()
            if (validActivity == null || validActivity.isFinishing || validActivity.isDestroyed) {
                appOpenAd = null
                callback(AdError.AD_FAILED_TO_LOAD)
                return@launch
            }

            // Handle Ad Load Result
            when (adLoadResult) {
                is AdLoadResult.Success -> {
                    appOpenAd = adLoadResult.ad
                    showAppOpenAd(validActivity, callback)
                }
                is AdLoadResult.Failure -> {
                    callback(AdError.AD_FAILED_TO_LOAD)
                }
                null -> {
                    callback(AdError.AD_LOAD_TIMEOUT)
                }
            }
        }
    }

    private fun showAppOpenAd(activity: Activity, callback: (AdError?) -> Unit) {
        val ad = appOpenAd
        if (ad == null || activity.isFinishing || activity.isDestroyed) {
            appOpenAd = null
            callback(AdError.AD_FAILED_TO_SHOW)
            return
        }

        ad.adEventCallback = object : AppOpenAdEventCallback {
            override fun onAdShowedFullScreenContent() {
                isAppOpenShowing = true
                _adEventFlow.tryEmit(AdEventCallback.SHOWED)
            }

            override fun onAdDismissedFullScreenContent() {
                cleanup()
                _adEventFlow.tryEmit(AdEventCallback.DISMISSED)
                callback(null)
            }

            override fun onAdFailedToShowFullScreenContent(error: FullScreenContentError) {
                cleanup()
                callback(AdError.AD_FAILED_TO_SHOW)
            }

            override fun onAdImpression() {
                super.onAdImpression()
            }

            override fun onAdClicked() {
                super.onAdClicked()
            }

            override fun onAdPaid(value: AdValue) {
               //ad value
            }
        }

        ad.show(activity)
    }

    private fun cleanup() {
        appOpenAd = null
        isAdLoading = false
        isAppOpenShowing = false
    }

    // --- Lifecycle Event Observers ---
    override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
        if (event == Lifecycle.Event.ON_START) {
            val activity = currentActivityRef?.get() ?: return

            // Shared Global Ad Collisions Guard Check
            val isOtherAdShowing = InterstitialAdManager.isInterstitialShowing || isAppOpenShowing

            if (!isOtherAdShowing) {
                loadAppOpenAdWithTimeOut(
                    activity = activity,
                    adUnitId = ""
                ) { result -> }
            }
        }
    }

    // --- Activity Lifecycle Tracking Hooks ---

    override fun onActivityStarted(activity: Activity) {
        currentActivityRef = WeakReference(activity)
    }

    override fun onActivityResumed(activity: Activity) {
        currentActivityRef = WeakReference(activity)
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (currentActivityRef?.get() == activity) {
            currentActivityRef = null
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
}
