package com.kazmi.dev.adsnextgen

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.RatingBar
import android.widget.TextView
import androidx.annotation.IdRes
import androidx.annotation.LayoutRes
import androidx.core.view.doOnLayout
import com.google.android.libraries.ads.mobile.sdk.common.AdChoicesView
import com.google.android.libraries.ads.mobile.sdk.common.AdValue
import com.google.android.libraries.ads.mobile.sdk.nativead.MediaView
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAd
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdEventCallback
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Created by Kazmi Dev.
 *
 * Native ad view for XML layouts (and for Compose through the compose module) with Small,
 * Medium and Full Screen templates. It takes a preloaded ad when one exists, loads otherwise,
 * stays invisible while loading, hides itself when no ad is available, and destroys its ad
 * with the host lifecycle.
 */
class AdsNativeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    var adUnitId: String? = null
    var autoLoad = true
    var onEvent: ((NativeEvent) -> Unit)? = null

    var template = NativeTemplate.MEDIUM
        set(value) {
            if (field == value && nativeAdView != null) return
            field = value
            inflateTemplate()
        }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val lifecycleBinding = ViewLifecycleBinding(::destroy)
    private var nativeAdView: NativeAdView? = null
    private var currentAd: NativeAd? = null
    private var loadJob: Job? = null
    private var isDestroyed = false

    init {
        context.obtainStyledAttributes(attrs, R.styleable.AdsNativeView).use {
            adUnitId = it.getString(R.styleable.AdsNativeView_adsAdUnitId)
            autoLoad = it.getBoolean(R.styleable.AdsNativeView_adsAutoLoad, true)
            template = NativeTemplate.entries[it.getInt(R.styleable.AdsNativeView_adsNativeTemplate, 1)]
        }
    }

    fun load() {
        if (isDestroyed || loadJob?.isActive == true) return
        if (!AdsManager.isAdsEnabled) return fail(AdError.PRODUCT_PURCHASED)

        visibility = View.INVISIBLE
        loadJob = scope.launch {
            when (val outcome = NativeAdManager.obtain(adUnitId)) {
                is NativeOutcome.Ready -> bind(outcome.ad)
                is NativeOutcome.Failed -> fail(outcome.error)
            }
        }
    }

    fun destroy() {
        if (isDestroyed) return
        isDestroyed = true
        lifecycleBinding.detach()
        scope.cancel()
        currentAd?.destroy()
        nativeAdView?.destroy()
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

    private fun inflateTemplate() {
        removeAllViews()
        currentAd?.destroy()
        currentAd = null
        @LayoutRes val layout = when (template) {
            NativeTemplate.SMALL -> R.layout.ads_native_small
            NativeTemplate.MEDIUM -> R.layout.ads_native_medium
            NativeTemplate.FULL_SCREEN -> R.layout.ads_native_full_screen
        }
        nativeAdView = LayoutInflater.from(context).inflate(layout, this, true)
            .findViewById(R.id.ads_native_root)
    }

    private fun bind(ad: NativeAd) {
        val root = nativeAdView ?: return ad.destroy()
        currentAd?.destroy()
        currentAd = ad

        root.headlineView = text(R.id.ads_headline, ad.headline)
        root.bodyView = text(R.id.ads_body, ad.body)
        root.callToActionView = text(R.id.ads_cta, ad.callToAction)
        root.advertiserView = text(R.id.ads_advertiser, ad.advertiser)
        root.iconView = find<ImageView>(R.id.ads_icon)?.apply {
            setImageDrawable(ad.icon?.drawable)
            visibility = if (ad.icon == null) View.GONE else View.VISIBLE
        }
        root.starRatingView = find<RatingBar>(R.id.ads_stars)?.apply {
            rating = ad.starRating?.toFloat() ?: 0f
            visibility = if (ad.starRating == null) View.GONE else View.VISIBLE
        }
        root.adChoicesView = find<AdChoicesView>(R.id.ads_adchoices)

        val mediaView = find<MediaView>(R.id.ads_media)
        mediaView?.mediaContent = ad.mediaContent
        root.registerNativeAd(ad, mediaView)

        ad.adEventCallback = object : NativeAdEventCallback {
            override fun onAdClicked() = emit(NativeEvent.Clicked)
            override fun onAdImpression() = emit(NativeEvent.Impression)
            override fun onAdPaid(value: AdValue) = emit(NativeEvent.Paid(value))
        }
        visibility = View.VISIBLE
        onEvent?.invoke(NativeEvent.Loaded)
    }

    private fun text(@IdRes id: Int, value: String?): TextView? = find<TextView>(id)?.apply {
        text = value
        visibility = if (value.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    private fun <T : View> find(@IdRes id: Int): T? = nativeAdView?.findViewById(id)

    private fun emit(event: NativeEvent) {
        scope.launch { onEvent?.invoke(event) }
    }

    private fun fail(error: AdError) {
        visibility = View.GONE
        onEvent?.invoke(NativeEvent.Failed(error))
    }
}
