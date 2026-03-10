/*
 * Module: r2-navigator-kotlin
 * Developers: Aferdita Muriqi, Clément Baumann
 *
 * Copyright (c) 2018. Readium Foundation. All rights reserved.
 * Use of this source code is governed by a BSD-style license which is detailed in the
 * LICENSE file present in the project repository where this source code is maintained.
 */

package org.readium.r2.navigator.pager

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import org.readium.r2.navigator.BuildConfig.DEBUG
import org.readium.r2.navigator.R2WebView
import org.readium.r2.shared.InternalReadiumApi
import timber.log.Timber

// See https://youtrack.jetbrains.com/issue/KTLC-271 for visibility issue.
@InternalReadiumApi
public class R2ViewPager : R2RTLViewPager {

    internal enum class PublicationType {
        EPUB,
        CBZ,
        FXL,
        WEBPUB,
        AUDIO,
        DiViNa,
    }

    internal lateinit var publicationType: PublicationType

    internal constructor(context: Context) : super(context)
    internal constructor(context: Context, attrs: AttributeSet) : super(context, attrs)

    /** Set to false to disable swipe-based page navigation. */
    public var isSwipeEnabled: Boolean = true
        set(value) {
            field = value
            propagateSwipeEnabled(value)
        }

    private fun propagateSwipeEnabled(enabled: Boolean) {
        forEachWebView(this) { it.swipeEnabled = enabled }
    }

    private fun forEachWebView(view: View, action: (R2WebView) -> Unit) {
        if (view is R2WebView) { action(view); return }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) forEachWebView(view.getChildAt(i), action)
        }
    }

    override fun setCurrentItem(item: Int) {
        super.setCurrentItem(item, false)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (DEBUG) Timber.d("ev.action ${ev.action}")
        if (publicationType == PublicationType.EPUB) {
            when (ev.action and MotionEvent.ACTION_MASK) {
                MotionEvent.ACTION_DOWN -> {
                    // prevent swipe from view pager directly
                    if (DEBUG) Timber.d("ACTION_DOWN")
                    return false
                }
            }
        }

        return try {
            // The super implementation sometimes triggers:
            // java.lang.IllegalArgumentException: pointerIndex out of range
            // i.e. https://stackoverflow.com/q/48496257/1474476
            return super.onTouchEvent(ev)
        } catch (ex: IllegalArgumentException) {
            Timber.e(ex)
            false
        }
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (!isSwipeEnabled) return false

        if (publicationType == PublicationType.EPUB) {
            when (ev.action and MotionEvent.ACTION_MASK) {
                MotionEvent.ACTION_DOWN -> {
                    // prevent swipe from view pager directly
                    return false
                }
            }
        }

        return try {
            // The super implementation sometimes triggers:
            // java.lang.IllegalArgumentException: pointerIndex out of range
            // i.e. https://stackoverflow.com/q/48496257/1474476
            super.onInterceptTouchEvent(ev)
        } catch (ex: IllegalArgumentException) {
            Timber.e(ex)
            false
        }
    }
}