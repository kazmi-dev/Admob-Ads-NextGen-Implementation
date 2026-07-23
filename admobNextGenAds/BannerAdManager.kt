import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowMetrics
import android.widget.FrameLayout
import com.google.android.libraries.ads.mobile.sdk.banner.AdSize
import com.google.android.libraries.ads.mobile.sdk.banner.AdView
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAd
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAdEventCallback
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAdRequest
import com.google.android.libraries.ads.mobile.sdk.common.AdLoadCallback
import com.google.android.libraries.ads.mobile.sdk.common.AdValue
import com.google.android.libraries.ads.mobile.sdk.common.FullScreenContentError
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError
import com.pedometer.steptracker.walkingtracker.calorieburner.stepcounter.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.suspendCancellableCoroutine
import java.lang.ref.WeakReference
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.seconds

object BannerAdManager {

    private const val TAG = "BannerAd_83472984092342"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    enum class BannerAsSize{
        LARGE, MEDIUM, SMALL, ADAPTIVE, COLLAPSIBLE, LEADERBOARD
    }

    enum class AdEventCallback {
        SHOWED,
        DISMISSED
    }

    private val _adEventFlow = MutableSharedFlow<AdEventCallback>(extraBufferCapacity = 1)
    val adEventFlow = _adEventFlow.asSharedFlow()

    fun showBannerAd(
        adViewContainer: FrameLayout,
        activity: Activity,
        isInternetConnected: Boolean = false,
        isAppPurchased: Boolean = false,
        adUnitId: String = activity.getString(R.string.admob_language_screen_banner_id),
        adSize: BannerAsSize = BannerAsSize.ADAPTIVE,
        onFailed: (AdError) -> Unit
    ){

        if (!isInternetConnected) { onFailed(AdError.NO_INTERNET); return }
        if (isAppPurchased) { onFailed(AdError.APP_PURCHASED); return }
        if (activity.isFinishing || activity.isDestroyed) { onFailed(AdError.AD_FAILED_TO_LOAD); return }

        val activityRef = WeakReference(activity)

        val bannerAdSize = when(adSize){
            BannerAsSize.LARGE -> AdSize.LARGE_BANNER
            BannerAsSize.MEDIUM -> AdSize.MEDIUM_RECTANGLE
            BannerAsSize.SMALL -> AdSize.BANNER
            BannerAsSize.LEADERBOARD -> AdSize.LEADERBOARD
            BannerAsSize.ADAPTIVE, BannerAsSize.COLLAPSIBLE ->  getAdaptiveBannerAdSize(activity)
        }

        Log.d(TAG, "loadBannerAd: BannerAdSize -> $bannerAdSize")

        val bannerAdView = AdView(activity)
        adViewContainer.removeAllViews()
        adViewContainer.addView(bannerAdView)

        scope.launch {
            val validActivityCheck = activityRef.get()
            if (validActivityCheck == null || validActivityCheck.isFinishing || validActivityCheck.isDestroyed) {
                onFailed(AdError.AD_FAILED_TO_LOAD)
                return@launch
            }

            val adView = loadBannerAd(
                bannerAdView = bannerAdView,
                adUnitId = adUnitId,
                adSize = adSize,
                bannerAdSize = bannerAdSize
            )

            val validActivity = activityRef.get()
            if (validActivity == null || validActivity.isFinishing || validActivity.isDestroyed) {
                onFailed(AdError.AD_FAILED_TO_LOAD)
                return@launch
            }

            if (adView == null) {
                onFailed(AdError.AD_FAILED_TO_LOAD)
            }
        }
    }

    private suspend fun loadBannerAd(
        bannerAdView: AdView,
        adUnitId: String,
        adSize: BannerAsSize,
        bannerAdSize: AdSize
    ): AdView? {


        val adRequest = BannerAdRequest.Builder(adUnitId, bannerAdSize)
        if (adSize == BannerAsSize.COLLAPSIBLE){
            Log.d(TAG, "loadBannerAd: Banner id is collapsible")
            val extras = Bundle()
            extras.putString("collapsible", "bottom")
            adRequest.setGoogleExtrasBundle(extras)
        }

        return withTimeoutOrNull(8.seconds) {
            suspendCancellableCoroutine { continuation ->
                bannerAdView.loadAd(
                    adRequest.build(),
                    object : AdLoadCallback<BannerAd> {
                        override fun onAdLoaded(ad: BannerAd) {
                            Log.d(TAG, "Banner ad loaded.")
                            Log.i(TAG, "The last loaded banner is ${if (ad.isCollapsible()) "" else "not "}collapsible.")
                            setAdEventCallbacks(ad, adUnitId)
                            if (continuation.isActive) {
                                continuation.resume(bannerAdView)
                            }
                        }

                        override fun onAdFailedToLoad(adError: LoadAdError) {
                            Log.d(TAG, "Banner ad failed to load: $adError")
                            if (continuation.isActive) {
                                continuation.resume(null)
                            }
                        }
                    }
                )
            }
        }
    }

    private fun setAdEventCallbacks(ad: BannerAd, adUnitId: String) {
        ad.adEventCallback = object: BannerAdEventCallback{
            override fun onAdShowedFullScreenContent() {
                Log.d(TAG, "Banner ad Showed")
                _adEventFlow.tryEmit(AdEventCallback.SHOWED)
            }

            override fun onAdDismissedFullScreenContent() {
                Log.d(TAG, "Banner ad Dismissed")
                _adEventFlow.tryEmit(AdEventCallback.DISMISSED)
            }

            override fun onAdFailedToShowFullScreenContent(fullScreenContentError: FullScreenContentError) {
                Log.d(TAG, "Banner ad Failed to Show -> ${fullScreenContentError.message}")
            }

            override fun onAdImpression() {
                Log.d(TAG, "Banner ad Impression")
            }

            override fun onAdClicked() {
                Log.d(TAG, "Banner ad Clicked")
            }

            override fun onAdPaid(value: AdValue) {
                Log.d(TAG, "Banner ad Paid")
                //tik tok ad revenue
                handleAdRevenue(ad, value, adUnitId)
            }

        }
    }

    private fun handleAdRevenue(ad: BannerAd, value: AdValue, adUnitId: String) {
        val responseExtras = ad.getResponseInfo().responseExtras

        val valueMicros = value.valueMicros
        val currencyCode = value.currencyCode
        val precisionType = value.precisionType.ordinal

        var adSourceName = ""
        var adSourceId = ""
        var adSourceInstanceName = ""
        var adSourceInstanceId = ""

        ad.getResponseInfo().loadedAdSourceResponseInfo?.let {
            adSourceName = it.name
            adSourceId = it.id
            adSourceInstanceName = it.instanceName
            adSourceInstanceId = it.instanceId
        }

        val mediationGroupName = responseExtras.getString("mediation_group_name", "")
        val mediationAbTestName = responseExtras.getString("mediation_ab_test_name", "")
        val mediationAbTestVariant = responseExtras.getString("mediation_ab_test_variant", "")

        TikTokEventHelper.generateRossEvent(
            revenue = valueMicros,
            currencyCode = currencyCode,
            precisionType = precisionType,
            adUnitId = adUnitId,
            adSourceName = adSourceName,
            adSourceId = adSourceId,
            adSourceInstanceName = adSourceInstanceName,
            adSourceInstanceId = adSourceInstanceId,
            mediationGroupName = mediationGroupName,
            mediationAbTestName = mediationAbTestName,
            mediationAbTestVariant = mediationAbTestVariant
        )
    }

    private fun getAdaptiveBannerAdSize(activity: Activity): AdSize {
        val displayMetrics = activity.resources.displayMetrics
        val adWidthPixels = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val windowMetrics: WindowMetrics = activity.windowManager.currentWindowMetrics
            windowMetrics.bounds.width()
        } else {
            displayMetrics.widthPixels
        }
        val density = displayMetrics.density
        val adWidth = (adWidthPixels / density).toInt()
        return AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(activity, adWidth)
    }


}
