package com.kazmi.dev.adsnextgen

import android.app.Activity
import android.content.Context
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Created by Kazmi Dev.
 *
 * UMP consent flow. Returns true when ads may be initialized. Any consent error is skipped
 * so ads still initialize.
 */
internal object ConsentManager {

    suspend fun gather(activity: Activity): Boolean = suspendCancellableCoroutine { continuation ->
        val information = UserMessagingPlatform.getConsentInformation(activity)
        information.requestConsentInfoUpdate(
            activity,
            ConsentRequestParameters.Builder().build(),
            {
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { error ->
                    continuation.resume(error != null || information.canRequestAds())
                }
            },
            { continuation.resume(true) }
        )
    }

    fun isPrivacyOptionsRequired(context: Context): Boolean =
        UserMessagingPlatform.getConsentInformation(context).privacyOptionsRequirementStatus ==
            ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED

    fun showPrivacyOptions(activity: Activity, onDismissed: () -> Unit) {
        UserMessagingPlatform.showPrivacyOptionsForm(activity) { onDismissed() }
    }
}
