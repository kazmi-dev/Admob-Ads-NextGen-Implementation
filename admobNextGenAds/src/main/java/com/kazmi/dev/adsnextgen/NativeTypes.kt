package com.kazmi.dev.adsnextgen

import com.google.android.libraries.ads.mobile.sdk.common.AdValue

/** Native ad layouts. */
enum class NativeTemplate {
    SMALL,
    MEDIUM,
    FULL_SCREEN
}

/**
 * Created by Kazmi Dev.
 *
 * Events delivered by a native view. [Failed] hides the view so no blank space is left.
 */
sealed interface NativeEvent {
    data object Loaded : NativeEvent
    data class Failed(val error: AdError) : NativeEvent
    data object Clicked : NativeEvent
    data object Impression : NativeEvent
    data class Paid(val value: AdValue) : NativeEvent
}
