package com.kazmi.dev.adsnextgen

/**
 * Created by Kazmi Dev.
 *
 * Allows one full-screen ad at a time and gives interstitial and rewarded flows priority
 * over App Open. Main thread only.
 */
internal object AdCoordinator {

    var isShowing = false
        private set

    private var highPriorityFlows = 0

    val canShowAppOpen: Boolean
        get() = !isShowing && highPriorityFlows == 0

    fun beginFlow(isHighPriority: Boolean) {
        if (isHighPriority) highPriorityFlows++
    }

    fun endFlow(isHighPriority: Boolean) {
        if (isHighPriority && highPriorityFlows > 0) highPriorityFlows--
    }

    fun tryStartShowing(): Boolean {
        if (isShowing) return false
        isShowing = true
        return true
    }

    fun stopShowing() {
        isShowing = false
    }
}
