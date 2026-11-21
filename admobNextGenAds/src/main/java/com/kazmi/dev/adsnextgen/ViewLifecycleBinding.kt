package com.kazmi.dev.adsnextgen

import android.view.View
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.findViewTreeLifecycleOwner

/**
 * Created by Kazmi Dev.
 *
 * Calls [onDestroy] when the lifecycle that hosts an ad view is destroyed.
 */
internal class ViewLifecycleBinding(private val onDestroy: () -> Unit) {

    private var owner: LifecycleOwner? = null

    private val observer = object : DefaultLifecycleObserver {
        override fun onDestroy(owner: LifecycleOwner) = onDestroy()
    }

    fun attach(view: View) {
        owner = view.findViewTreeLifecycleOwner()?.also { it.lifecycle.addObserver(observer) }
    }

    fun detach() {
        owner?.lifecycle?.removeObserver(observer)
        owner = null
    }
}
