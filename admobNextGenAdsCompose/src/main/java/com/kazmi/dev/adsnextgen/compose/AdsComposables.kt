package com.kazmi.dev.adsnextgen.compose

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.kazmi.dev.adsnextgen.AdsBannerView
import com.kazmi.dev.adsnextgen.AdsNativeView
import com.kazmi.dev.adsnextgen.BannerCollapse
import com.kazmi.dev.adsnextgen.BannerEvent
import com.kazmi.dev.adsnextgen.BannerSize
import com.kazmi.dev.adsnextgen.NativeEvent
import com.kazmi.dev.adsnextgen.NativeTemplate

/**
 * Created by Kazmi Dev.
 *
 * Compose wrappers around [AdsBannerView] and [AdsNativeView]. The ad loads when the
 * composable enters the composition and is destroyed when it leaves.
 */
@Composable
fun BannerAd(
    modifier: Modifier = Modifier.fillMaxWidth(),
    adUnitId: String? = null,
    size: BannerSize = BannerSize.ANCHORED_ADAPTIVE,
    collapse: BannerCollapse? = null,
    onEvent: (BannerEvent) -> Unit = {}
) {
    val currentOnEvent by rememberUpdatedState(onEvent)
    AndroidView(
        modifier = modifier,
        factory = { context ->
            AdsBannerView(context).also {
                it.adUnitId = adUnitId
                it.size = size
                it.collapse = collapse
                it.onEvent = { event -> currentOnEvent(event) }
            }
        },
        onRelease = { it.destroy() }
    )
}

@Composable
fun NativeAdSmall(
    modifier: Modifier = Modifier.fillMaxWidth(),
    adUnitId: String? = null,
    onEvent: (NativeEvent) -> Unit = {}
) = NativeAdContent(NativeTemplate.SMALL, modifier, adUnitId, onEvent)

@Composable
fun NativeAdMedium(
    modifier: Modifier = Modifier.fillMaxWidth(),
    adUnitId: String? = null,
    onEvent: (NativeEvent) -> Unit = {}
) = NativeAdContent(NativeTemplate.MEDIUM, modifier, adUnitId, onEvent)

@Composable
fun NativeAdFullScreen(
    modifier: Modifier = Modifier.fillMaxSize(),
    adUnitId: String? = null,
    onEvent: (NativeEvent) -> Unit = {}
) = NativeAdContent(NativeTemplate.FULL_SCREEN, modifier, adUnitId, onEvent)

@Composable
private fun NativeAdContent(
    template: NativeTemplate,
    modifier: Modifier,
    adUnitId: String?,
    onEvent: (NativeEvent) -> Unit
) {
    val currentOnEvent by rememberUpdatedState(onEvent)
    AndroidView(
        modifier = modifier,
        factory = { context ->
            AdsNativeView(context).also {
                it.adUnitId = adUnitId
                it.template = template
                it.onEvent = { event -> currentOnEvent(event) }
            }
        },
        onRelease = { it.destroy() }
    )
}
