package com.kazmi.dev.adsnextgen

import android.content.Context
import android.os.Bundle
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import androidx.core.view.doOnLayout
import com.google.android.libraries.ads.mobile.sdk.banner.AdSize
import com.google.android.libraries.ads.mobile.sdk.banner.AdView
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAd
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAdEventCallback
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAdRequest
import com.google.android.libraries.ads.mobile.sdk.common.AdLoadCallback
import com.google.android.libraries.ads.mobile.sdk.common.AdValue
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Created by Kazmi Dev.
 *
 * Banner ad view for XML layouts (and for Compose through the compose module).
 * Loads automatically once laid out unless [autoLoad] is false, loads nothing while offline,
 * hides itself when no ad is available and destroys itself with the host lifecycle.
 */
class AdsBannerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    var adUnitId: String? = null
    var size = BannerSize.ANCHORED_ADAPTIVE
    var collapse: BannerCollapse? = null
    var autoLoad = true
    var onEvent: ((BannerEvent) -> Unit)? = null

    private val adView = AdView(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val lifecycleBinding = ViewLifecycleBinding(::destroy)
    private var loadJob: Job? = null
    private var isDestroyed = false

    init {
        addView(adView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        context.obtainStyledAttributes(attrs, R.styleable.AdsBannerView).use {
            adUnitId = it.getString(R.styleable.AdsBannerView_adsAdUnitId)
            autoLoad = it.getBoolean(R.styleable.AdsBannerView_adsAutoLoad, true)
            size = BannerSize.entries[it.getInt(R.styleable.AdsBannerView_adsBannerSize, 0)]
            collapse = BannerCollapse.entries.getOrNull(it.getInt(R.styleable.AdsBannerView_adsCollapsible, 0) - 1)
        }
    }

    fun load() {
        if (isDestroyed || loadJob?.isActive == true) return
        val id = adUnitId?.takeIf { it.isNotBlank() } ?: AdsManager.config.bannerId.takeIf { it.isNotBlank() }
        when {
            !AdsManager.isAdsEnabled -> return fail(AdError.PRODUCT_PURCHASED)
            id == null -> return fail(AdError.INVALID_AD_UNIT_ID)
            !Connectivity.isConnected -> return fail(AdError.NO_INTERNET)
        }

        loadJob = scope.launch {
            if (withTimeoutOrNull(AD_TIMEOUT_MS) { AdsManager.awaitInitialized() } == null) {
                return@launch fail(AdError.AD_LOAD_TIMEOUT)
            }
            adView.loadAd(buildRequest(id), object : AdLoadCallback<BannerAd> {
                override fun onAdLoaded(ad: BannerAd) {
                    scope.launch { onLoaded(ad) }
                }

                override fun onAdFailedToLoad(adError: LoadAdError) {
                    scope.launch { fail(AdError.AD_FAILED_TO_LOAD) }
                }
            })
        }
    }

    fun destroy() {
        if (isDestroyed) return
        isDestroyed = true
        lifecycleBinding.detach()
        scope.cancel()
        adView.destroy()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        lifecycleBinding.attach(this)
        if (autoLoad) doOnLayout { load() }
    }

    override fun onDetachedFromWindow() {
        lifecycleBinding.detach()
        super.onDetachedFromWindow()
    }

    private fun onLoaded(ad: BannerAd) {
        ad.adEventCallback = object : BannerAdEventCallback {
            override fun onAdClicked() = emit(BannerEvent.Clicked)
            override fun onAdImpression() = emit(BannerEvent.Impression)
            override fun onAdPaid(value: AdValue) = emit(BannerEvent.Paid(value))
        }
        visibility = View.VISIBLE
        onEvent?.invoke(BannerEvent.Loaded)
    }

    private fun emit(event: BannerEvent) {
        scope.launch { onEvent?.invoke(event) }
    }

    private fun fail(error: AdError) {
        visibility = View.GONE
        onEvent?.invoke(BannerEvent.Failed(error))
    }

    private fun buildRequest(id: String): BannerAdRequest {
        val builder = BannerAdRequest.Builder(id, resolveSize())
        collapse?.let { builder.setGoogleExtrasBundle(Bundle().apply { putString("collapsible", it.value) }) }
        return builder.build()
    }

    private fun resolveSize(): AdSize {
        val widthDp = ((width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels) /
            resources.displayMetrics.density).toInt()
        return when (size) {
            BannerSize.ANCHORED_ADAPTIVE -> AdSize.getLargeAnchoredAdaptiveBannerAdSize(context, widthDp)
            BannerSize.INLINE_ADAPTIVE -> AdSize.getCurrentOrientationInlineAdaptiveBannerAdSize(context, widthDp)
            BannerSize.BANNER -> AdSize.BANNER
            BannerSize.FULL_BANNER -> AdSize.FULL_BANNER
            BannerSize.LARGE_BANNER -> AdSize.LARGE_BANNER
            BannerSize.LEADERBOARD -> AdSize.LEADERBOARD
            BannerSize.MEDIUM_RECTANGLE -> AdSize.MEDIUM_RECTANGLE
            BannerSize.FLUID -> AdSize.FLUID
        }
    }
}
