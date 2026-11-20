package com.kazmi.dev.adsnextgen

/**
 * Created by Kazmi Dev.
 *
 * Reason an ad flow ended without showing an ad.
 */
enum class AdError {
    NO_INTERNET,
    PRODUCT_PURCHASED,
    INVALID_AD_UNIT_ID,
    AD_FAILED_TO_LOAD,
    AD_LOAD_TIMEOUT,
    AD_NOT_AVAILABLE,
    AD_FAILED_TO_SHOW,
    ANOTHER_AD_SHOWING,
    ACTIVITY_UNAVAILABLE
}
