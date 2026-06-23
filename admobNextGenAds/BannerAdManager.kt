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
import com.navigataion.pedometer.billing.PedometerAppPurchaseHelper
import com.pedometer.steptracker.walkingtracker.calorieburner.stepcounter.R

object BannerAdManager {

    private const val TAG = "BannerAd_83472984092342"

    private var bannerAdView: AdView? = null
    private var adUnitId: String? = null

    enum class BannerAsSize{
        LARGE, MEDIUM, SMALL, ADAPTIVE, LEADERBOARD, COLLAPSIBLE
    }

    fun showBannerAd(
        adViewContainer: FrameLayout,
        activity: Activity,
        adUnitId: String = activity.getString(R.string.admob_medium_banner_language_id),
        adSize: BannerAsSize = BannerAsSize.ADAPTIVE,
    ){

        if (PedometerAppPurchaseHelper(activity).isAnySubscriptionPurchased()) return

        if (bannerAdView != null) bannerAdView = null

        this.adUnitId = adUnitId
        loadBannerAd(
            activity,
            adUnitId,
            adSize,
            adViewContainer,
            onLoad = {ad->
                //banner ad loaded
                setAdEventCallbacks(ad)
            },
            onFailed = {
                //banner failed to load
            }
        )



    }

    private fun loadBannerAd(
        activity: Activity,
        adUnitId: String,
        adSize: BannerAsSize,
        adViewContainer: FrameLayout,
        isCollapsible: Boolean = false,
        onLoad: (ad: BannerAd) -> Unit,
        onFailed: () -> Unit
    ) {
        //create banner ad View
        bannerAdView = AdView(activity)

        val bannerAdSize = when(adSize){
            BannerAsSize.LARGE -> AdSize.LARGE_BANNER
            BannerAsSize.MEDIUM -> AdSize.MEDIUM_RECTANGLE
            BannerAsSize.SMALL -> AdSize.BANNER
            BannerAsSize.LEADERBOARD -> AdSize.LEADERBOARD
            else ->  getAdaptiveBannerAdSize(activity)
        }

        adViewContainer.removeAllViews()
        adViewContainer.addView(bannerAdView)

        val adRequest = BannerAdRequest.Builder(adUnitId, bannerAdSize)
            .apply { 
                if (isCollapsible) {
                    val extras = Bundle().apply { 
                        putString("collapsible", "bottom")
                    }
                    setGoogleExtrasBundle(extras)
                }
            }
            .build()
        
        bannerAdView!!.loadAd(
            adRequest,
            object : AdLoadCallback<BannerAd> {
                override fun onAdLoaded(ad: BannerAd) {
                    Log.d(TAG, "Banner ad loaded.")
                    onLoad(ad)
                }

                override fun onAdFailedToLoad(adError: LoadAdError) {
                    Log.d(TAG, "Banner ad failed to load: $adError")
                    onFailed()
                }
            }
        )
    }

    private fun setAdEventCallbacks(ad: BannerAd) {
        ad.adEventCallback = object: BannerAdEventCallback{
            override fun onAdShowedFullScreenContent() {
                Log.d(TAG, "Banner ad Showed")
            }

            override fun onAdDismissedFullScreenContent() {
                Log.d(TAG, "Banner ad Dismissed")
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
            }

        }
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
