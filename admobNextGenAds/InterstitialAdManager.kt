import android.app.Activity
import com.google.android.libraries.ads.mobile.sdk.common.AdLoadResult
import com.google.android.libraries.ads.mobile.sdk.common.AdRequest
import com.google.android.libraries.ads.mobile.sdk.common.AdValue
import com.google.android.libraries.ads.mobile.sdk.common.FullScreenContentError
import com.google.android.libraries.ads.mobile.sdk.common.PreloadConfiguration
import com.google.android.libraries.ads.mobile.sdk.interstitial.InterstitialAd
import com.google.android.libraries.ads.mobile.sdk.interstitial.InterstitialAdEventCallback
import com.google.android.libraries.ads.mobile.sdk.interstitial.InterstitialAdPreloader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.lang.ref.WeakReference
import kotlin.time.Duration.Companion.seconds

object InterstitialAdManager {

    private var interstitialAd: InterstitialAd? = null
    private var isAdLoading: Boolean = false

    var isInterstitialShowing: Boolean = false
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun loadInterstitialAdWithTimeOut(
        activity: Activity,
        adUnitId: String,
        duration: Long = 8,
        isInternetConnected: Boolean = true,
        isProductPurchased: Boolean = false,
        callback: (AdError?) -> Unit,
    ) {
        // Pre-checks
        if (isAdLoading || isInterstitialShowing) return
        if (activity.isFinishing || activity.isDestroyed) return
        if (isProductPurchased) { callback(AdError.PRODUCT_PURCHASED); return }

        // If ad is already available
        if (isInterstitialAdAvailable()) {
            showInterstitialAd(activity, callback)
            return
        }

        if (!isInternetConnected) { callback(AdError.NO_INTERNET); return }

        // Wrap activity in WeakReference to prevent leaks
        val activityRef = WeakReference(activity)

        isAdLoading = true
        val adRequest = AdRequest.Builder(adUnitId).build()

        scope.launch {
            // Suspends here waiting for network/timeout
            val adLoadResult = withTimeoutOrNull(duration.seconds) {
                InterstitialAd.load(adRequest)
            }

            isAdLoading = false

            // Extract the activity after suspension finishes and check its validity
            val validActivity = activityRef.get()
            if (validActivity == null || validActivity.isFinishing || validActivity.isDestroyed) {
                return@launch
            }

            // 4. Handle ad load result
            when (adLoadResult) {
                is AdLoadResult.Success -> {
                    interstitialAd = adLoadResult.ad
                    showInterstitialAd(validActivity, callback)
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

    private fun showInterstitialAd(
        activity: Activity,
        callback: (AdError?) -> Unit,
    ) {
        // Local snapshot/smart cast safety check
        val ad = interstitialAd
        if (ad == null) {
            callback(AdError.AD_FAILED_TO_LOAD)
            return
        }

        // Double check activity state right before showing UI
        if (activity.isFinishing || activity.isDestroyed) return

        ad.adEventCallback = object : InterstitialAdEventCallback {
            override fun onAdShowedFullScreenContent() {
                isInterstitialShowing = true
            }

            override fun onAdDismissedFullScreenContent() {
                cleanup()
                callback(null)
            }

            override fun onAdFailedToShowFullScreenContent(fullScreenContentError: FullScreenContentError) {
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
                // ILRD mappings go here
            }
        }

        ad.show(activity)
    }

    fun startPreLoadingAds(adUnitId: String){
        val adRequest = AdRequest.Builder(adUnitId).build()
        val preloadConfig = PreloadConfiguration(adRequest)
        InterstitialAdPreloader.start(adUnitId, preloadConfig)
    }

    fun showPreloadedInterstitialAd(
        activity: Activity,
        adUnitId: String,
        callback: (AdError?) -> Unit,
    ){
        //get activity reference to prevent leaks
        val activityRef = WeakReference(activity)

        val ad = InterstitialAdPreloader.pollAd(adUnitId)
        if (ad == null) { callback(AdError.AD_FAILED_TO_LOAD); return }

        val validActivity = activityRef.get()
        if (validActivity == null || validActivity.isFinishing || validActivity.isDestroyed){
            callback(AdError.AD_FAILED_TO_LOAD)
            return
        }

        //show ad
        showInterstitialAd(
            validActivity,
            callback
        )
    }

    private fun isInterstitialAdAvailable(): Boolean = interstitialAd != null

    private fun cleanup() {
        interstitialAd = null
        isAdLoading = false
        isInterstitialShowing = false
    }
}
