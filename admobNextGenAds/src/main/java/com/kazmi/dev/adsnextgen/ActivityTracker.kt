package com.kazmi.dev.adsnextgen

import android.app.Activity
import android.app.Application
import android.os.Bundle
import kotlinx.coroutines.suspendCancellableCoroutine
import java.lang.ref.WeakReference
import kotlin.coroutines.resume

/**
 * Created by Kazmi Dev.
 *
 * Keeps a weak reference to the current activity. Main thread only.
 */
internal object ActivityTracker : Application.ActivityLifecycleCallbacks {

    private var currentRef: WeakReference<Activity>? = null
    private val waiters = mutableListOf<(Activity) -> Unit>()
    private var isRegistered = false

    val current: Activity?
        get() = currentRef?.get()?.takeUnless { it.isFinishing || it.isDestroyed }

    fun register(application: Application) {
        if (isRegistered) return
        isRegistered = true
        application.registerActivityLifecycleCallbacks(this)
    }

    suspend fun awaitActivity(): Activity {
        current?.let { return it }
        return suspendCancellableCoroutine { continuation ->
            val waiter: (Activity) -> Unit = { continuation.resume(it) }
            waiters += waiter
            continuation.invokeOnCancellation { waiters -= waiter }
        }
    }

    private fun track(activity: Activity) {
        currentRef = WeakReference(activity)
        if (waiters.isEmpty()) return
        val pending = waiters.toList()
        waiters.clear()
        pending.forEach { it(activity) }
    }

    override fun onActivityStarted(activity: Activity) = track(activity)

    override fun onActivityResumed(activity: Activity) = track(activity)

    override fun onActivityDestroyed(activity: Activity) {
        if (currentRef?.get() === activity) currentRef = null
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
}
