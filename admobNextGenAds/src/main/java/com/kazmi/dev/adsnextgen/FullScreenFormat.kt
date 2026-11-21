package com.kazmi.dev.adsnextgen

import android.app.Activity
import com.google.android.libraries.ads.mobile.sdk.common.Ad
import com.google.android.libraries.ads.mobile.sdk.common.AdEventCallback
import com.google.android.libraries.ads.mobile.sdk.common.AdLoadResult
import com.google.android.libraries.ads.mobile.sdk.common.AdValue
import com.google.android.libraries.ads.mobile.sdk.common.FullScreenContentError

internal const val ONE_HOUR_EXPIRY_MS = 55 * 60 * 1_000L
internal const val APP_OPEN_EXPIRY_MS = (3 * 60 + 50) * 60 * 1_000L

/**
 * Created by Kazmi Dev.
 *
 * The only things that differ between full-screen formats. Everything else lives in
 * [FullScreenController].
 */
internal interface FullScreenFormat<T : Ad> {
    val expiryMs: Long
    suspend fun load(adUnitId: String): T?
    fun startPreload(adUnitId: String, bufferSize: Int)
    fun poll(adUnitId: String): T?
    fun show(ad: T, activity: Activity, onEvent: (AdEvent) -> Unit)
}

internal class AdEventSink(private val onEvent: (AdEvent) -> Unit) : AdEventCallback {
    override fun onAdShowedFullScreenContent() = onEvent(AdEvent.Showed)
    override fun onAdDismissedFullScreenContent() = onEvent(AdEvent.Dismissed)
    override fun onAdFailedToShowFullScreenContent(fullScreenContentError: FullScreenContentError) =
        onEvent(AdEvent.Failed(fullScreenContentError.toAdError()))

    override fun onAdImpression() = onEvent(AdEvent.Impression)
    override fun onAdClicked() = onEvent(AdEvent.Clicked)
    override fun onAdPaid(value: AdValue) = onEvent(AdEvent.Paid(value))
}

private fun FullScreenContentError.toAdError(): AdError =
    if (code == FullScreenContentError.ErrorCode.APP_NOT_FOREGROUND) AdError.ACTIVITY_UNAVAILABLE
    else AdError.AD_FAILED_TO_SHOW

@Suppress("UNCHECKED_CAST")
internal fun <T : Ad> AdLoadResult<T>.adOrNull(): T? = (this as? AdLoadResult.Success<T>)?.ad
