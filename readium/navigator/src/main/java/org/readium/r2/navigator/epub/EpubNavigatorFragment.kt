/*
 * Copyright 2020 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

@file:OptIn(InternalReadiumApi::class)

package org.readium.r2.navigator.epub

import android.graphics.PointF
import android.graphics.RectF
import android.os.Bundle
import android.util.LayoutDirection
import android.util.Log
import android.view.ActionMode
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.collection.forEach
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.os.BundleCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentFactory
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.withStarted
import androidx.viewpager.widget.ViewPager
import kotlin.math.ceil
import kotlin.reflect.KClass
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.readium.r2.navigator.DecorableNavigator
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.DecorationId
import org.readium.r2.navigator.HyperlinkNavigator
import org.readium.r2.navigator.NavigatorFragment
import org.readium.r2.navigator.OverflowableNavigator
import org.readium.r2.navigator.R
import org.readium.r2.navigator.R2BasicWebView
import org.readium.r2.navigator.R2WebView
import org.readium.r2.navigator.RestorationNotSupportedException
import org.readium.r2.navigator.SelectableNavigator
import org.readium.r2.navigator.Selection
import org.readium.r2.navigator.databinding.ReadiumNavigatorViewpagerBinding
import org.readium.r2.navigator.dummyPublication
import org.readium.r2.navigator.epub.EpubNavigatorViewModel.RunScriptCommand
import org.readium.r2.navigator.epub.css.FontFamilyDeclaration
import org.readium.r2.navigator.epub.css.MutableFontFamilyDeclaration
import org.readium.r2.navigator.epub.css.RsProperties
import org.readium.r2.navigator.epub.css.buildFontFamilyDeclaration
import org.readium.r2.navigator.extensions.normalizeLocator
import org.readium.r2.navigator.extensions.optRectF
import org.readium.r2.navigator.extensions.positionsByResource
import org.readium.r2.navigator.html.HtmlDecorationTemplates
import org.readium.r2.navigator.input.CompositeInputListener
import org.readium.r2.navigator.input.DragEvent
import org.readium.r2.navigator.input.InputListener
import org.readium.r2.navigator.input.KeyEvent
import org.readium.r2.navigator.input.KeyInterceptorView
import org.readium.r2.navigator.input.TapEvent
import org.readium.r2.navigator.pager.R2EpubPageFragment
import org.readium.r2.navigator.pager.R2PagerAdapter
import org.readium.r2.navigator.pager.R2PagerAdapter.PageResource
import org.readium.r2.navigator.pager.R2ViewPager
import org.readium.r2.navigator.preferences.Configurable
import org.readium.r2.navigator.preferences.FontFamily
import org.readium.r2.navigator.preferences.ReadingProgression
import org.readium.r2.navigator.util.createFragmentFactory
import org.readium.r2.shared.DelicateReadiumApi
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.InternalReadiumApi
import org.readium.r2.shared.extensions.tryOrLog
import org.readium.r2.shared.publication.Href
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.epub.EpubLayout
import org.readium.r2.shared.publication.presentation.presentation
import org.readium.r2.shared.publication.services.positionsByReadingOrder
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.mediatype.MediaType
import org.readium.r2.shared.util.resource.Resource
import org.readium.r2.shared.util.toAbsoluteUrl
import timber.log.Timber

/**
 * Factory for a [JavascriptInterface] which will be injected in the web views.
 *
 * Return `null` if you don't want to inject the interface for the given resource.
 */
public typealias JavascriptInterfaceFactory = (resource: Link) -> Any?

/**
 * Navigator for EPUB publications.
 *
 * To use this [Fragment], create a factory with `EpubNavigatorFragment.createFactory()`.
 */
@OptIn(ExperimentalReadiumApi::class, DelicateReadiumApi::class)
public class EpubNavigatorFragment internal constructor(
    publication: Publication,
    private val initialLocator: Locator?,
    readingOrder: List<Link>?,
    private val initialPreferences: EpubPreferences,
    internal val listener: Listener?,
    internal val paginationListener: PaginationListener?,
    epubLayout: EpubLayout,
    private val defaults: EpubDefaults,
    configuration: Configuration,
) : NavigatorFragment(publication),
    OverflowableNavigator,
    SelectableNavigator,
    DecorableNavigator,
    HyperlinkNavigator,
    Configurable<EpubSettings, EpubPreferences> {

    // Make a copy to prevent the user from modifying the configuration after initialization.
    internal val config: Configuration = configuration.copy().apply {
        servedAssets += "readium/.*"

        addFontFamilyDeclaration(FontFamily.OPEN_DYSLEXIC) {
            addFontFace {
                addSource("readium/fonts/OpenDyslexic-Regular.otf")
            }
        }
    }

    public data class Configuration internal constructor(

        /**
         * Patterns for asset paths which will be available to EPUB resources under
         * https://readium/assets/.
         *
         * The patterns can use simple glob wildcards, see:
         * https://developer.android.com/reference/android/os/PatternMatcher#PATTERN_SIMPLE_GLOB
         *
         * Use .* to serve all app assets.
         */
        @ExperimentalReadiumApi
        var servedAssets: List<String>,

        /**
         * Readium CSS reading system settings.
         *
         * See https://readium.org/readium-css/docs/CSS19-api.html#reading-system-styles
         */
        @ExperimentalReadiumApi
        var readiumCssRsProperties: RsProperties,

        /**
         * When disabled, the Android web view's `WebSettings.textZoom` will be used to adjust the
         * font size, instead of using the Readium CSS's `--USER__fontSize` variable.
         *
         * `WebSettings.textZoom` will work with more publications than `--USER__fontSize`, even the
         * ones poorly authored. However, the page width is not adjusted when changing the font
         * size to keep the optimal line length.
         *
         * See:
         *   - https://github.com/readium/mobile/issues/5
         *   - https://github.com/readium/mobile/issues/1#issuecomment-652431984
         */
        @ExperimentalReadiumApi
        @DelicateReadiumApi
        var useReadiumCssFontSize: Boolean = true,

        /**
         * Supported HTML decoration templates.
         */
        var decorationTemplates: HtmlDecorationTemplates,

        /**
         * Indicates if a user can swipe to change resources when scroll is enabled.
         */
        var disablePageTurnsWhileScrolling: Boolean,

        /**
         * Custom [ActionMode.Callback] to be used when the user selects content.
         *
         * Provide one if you want to customize the selection context menu items.
         */
        var selectionActionModeCallback: ActionMode.Callback?,

        /**
         * Whether padding accounting for display cutouts should be applied.
         */
        var shouldApplyInsetsPadding: Boolean?,

        /**
         * Disable user selection if the publication is protected by a DRM (e.g. with LCP).
         *
         * WARNING: If you choose to disable this, you MUST remove the Copy and Share selection
         * menu items in your app. Otherwise, you will void the EDRLab certification for your
         * application. If you need help, follow up on:
         * https://github.com/readium/kotlin-toolkit/issues/299#issuecomment-1315643577
         */
        @DelicateReadiumApi
        var disableSelectionWhenProtected: Boolean,

        internal var fontFamilyDeclarations: List<FontFamilyDeclaration>,
        internal var javascriptInterfaces: Map<String, JavascriptInterfaceFactory>,
    ) {
        public constructor(
            servedAssets: List<String> = emptyList(),
            readiumCssRsProperties: RsProperties = RsProperties(),
            decorationTemplates: HtmlDecorationTemplates = HtmlDecorationTemplates.defaultTemplates(),
            disablePageTurnsWhileScrolling: Boolean = false,
            selectionActionModeCallback: ActionMode.Callback? = null,
            shouldApplyInsetsPadding: Boolean? = true,
        ) : this(
            servedAssets = servedAssets,
            readiumCssRsProperties = readiumCssRsProperties,
            decorationTemplates = decorationTemplates,
            disablePageTurnsWhileScrolling = disablePageTurnsWhileScrolling,
            selectionActionModeCallback = selectionActionModeCallback,
            shouldApplyInsetsPadding = shouldApplyInsetsPadding,
            disableSelectionWhenProtected = true,
            fontFamilyDeclarations = emptyList(),
            javascriptInterfaces = emptyMap()
        )

        /**
         * Registers a new factory for the [JavascriptInterface] named [name].
         *
         * Return `null` in [factory] to prevent adding the Javascript interface for a given
         * resource.
         */
        public fun registerJavascriptInterface(name: String, factory: JavascriptInterfaceFactory) {
            javascriptInterfaces += name to factory
        }

        /**
         * Adds a declaration for [fontFamily] using [builderAction].
         *
         * @param alternates Specifies a list of alternative font families used as fallbacks when
         * symbols are missing from [fontFamily].
         */
        @ExperimentalReadiumApi
        public fun addFontFamilyDeclaration(
            fontFamily: FontFamily,
            alternates: List<FontFamily> = emptyList(),
            builderAction: (MutableFontFamilyDeclaration).() -> Unit,
        ) {
            fontFamilyDeclarations += buildFontFamilyDeclaration(
                fontFamily = fontFamily.name,
                alternates = alternates.map { it.name },
                builderAction = builderAction
            )
        }

        public companion object {
            public operator fun invoke(builder: Configuration.() -> Unit): Configuration =
                Configuration().apply(builder)
        }
    }

    public interface PaginationListener {
        public fun onPageChanged(pageIndex: Int, totalPages: Int, locator: Locator) {}
        public fun onPageLoaded() {}
    }

    public interface Listener : OverflowableNavigator.Listener, HyperlinkNavigator.Listener

    private sealed class State {
        /** The navigator just started and didn't load any resource yet. */
        data object Initializing : State()

        /** The navigator is loading the first resource at `initialResourceHref`. */
        data class Loading(val initialResourceHref: Url) : State()

        /** The navigator is fully initialized and ready for action. */
        data object Ready : State()
    }

    private var state: State = State.Initializing

    // Configurable

    override val settings: StateFlow<EpubSettings> get() = viewModel.settings

    override fun submitPreferences(preferences: EpubPreferences) {
        viewModel.submitPreferences(preferences)
    }

    /**
     * Evaluates the given JavaScript on the currently visible HTML resource.
     *
     * Note that this only work with reflowable resources.
     */
    public suspend fun evaluateJavascript(script: String): String? {
        val page = currentReflowablePageFragment ?: return null
        page.awaitLoaded()
        return page.runJavaScriptSuspend(script)
    }

    private val viewModel: EpubNavigatorViewModel by viewModels {
        EpubNavigatorViewModel.createFactory(
            requireActivity().application,
            publication,
            config = this.config,
            initialPreferences = initialPreferences,
            listener = listener,
            layout = epubLayout,
            defaults = defaults
        )
    }

    private val readingOrder: List<Link> = readingOrder ?: publication.readingOrder

    private val positionsByReadingOrder: List<List<Locator>> =
        if (readingOrder != null) {
            emptyList()
        } else {
            runBlocking { publication.positionsByReadingOrder() }
        }

    internal lateinit var positions: List<Locator>

    internal lateinit var resourcePager: R2ViewPager

    private lateinit var resourcesSingle: List<PageResource>
    private lateinit var resourcesDouble: List<PageResource>

    internal var currentPagerPosition: Int = 0
    internal lateinit var adapter: R2PagerAdapter
    private lateinit var currentActivity: FragmentActivity

    private var _binding: ReadiumNavigatorViewpagerBinding? = null
    private val binding get() = _binding!!

    // WebView pool to avoid re-creating WebViews from scratch on chapter jumps
    private val webViewPool = mutableListOf<R2WebView>()
    private val maxWebViewPoolSize = 1

    /**
     * Acquire a pre-initialized WebView from the pool, or null if pool is empty.
     * Detaches the WebView from any existing parent before returning it.
     */
    internal fun acquireWebView(): R2WebView? {
        val wv = webViewPool.removeLastOrNull() ?: return null
        // Detach from previous parent if still attached
        (wv.parent as? ViewGroup)?.removeView(wv)
        return wv
    }

    /**
     * Return a WebView to the pool for reuse, or destroy it if pool is full.
     * Does NOT remove from parent here — that causes re-layout during fragment
     * destruction which triggers a ViewPager re-entrancy crash.
     */
    internal fun releaseWebView(webView: R2WebView) {
        if (webViewPool.size >= maxWebViewPoolSize) {
            // Pool full — schedule destruction after layout pass completes
            webView.post {
                (webView.parent as? ViewGroup)?.removeView(webView)
                webView.removeAllViews()
                webView.destroy()
            }
            return
        }
        webView.stopLoading()
        webView.loadDataWithBaseURL(null, "", "text/html", "utf-8", null)
        webView.listener = null
        webView.resourceUrl = null
        webViewPool.add(webView)
    }

    private fun prewarmWebViewPool() {
        if (webViewPool.isEmpty()) {
            try {
                // Inflate an R2WebView from the same XML layout to get proper AttributeSet
                val inflater = LayoutInflater.from(requireContext())
                val wv = inflater.inflate(
                    R.layout.readium_navigator_viewpager_fragment_epub, null, false
                ).findViewById<R2WebView>(R.id.webView)
                wv.settings.javaScriptEnabled = true
                wv.isVerticalScrollBarEnabled = false
                wv.isHorizontalScrollBarEnabled = false
                wv.settings.useWideViewPort = true
                wv.settings.loadWithOverviewMode = true
                wv.settings.setSupportZoom(true)
                wv.settings.builtInZoomControls = true
                wv.settings.displayZoomControls = false
                webViewPool.add(wv)
            } catch (e: Exception) {
                Timber.w(e, "Failed to prewarm WebView pool")
            }
        }
    }

    private fun destroyWebViewPool() {
        webViewPool.forEach { wv ->
            (wv.parent as? ViewGroup)?.removeView(wv)
            wv.removeAllViews()
            wv.destroy()
        }
        webViewPool.clear()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        currentActivity = requireActivity()
        _binding = ReadiumNavigatorViewpagerBinding.inflate(inflater, container, false)
        var view: View = binding.root

        positions = positionsByReadingOrder.flatten()

        when (viewModel.layout) {
            EpubLayout.REFLOWABLE -> {
                resourcesSingle = readingOrder.mapIndexed { index, link ->
                    PageResource.EpubReflowable(
                        link = link,
                        url = viewModel.urlTo(link),
                        positionCount = positionsByReadingOrder.getOrNull(index)?.size ?: 0
                    )
                }
            }

            EpubLayout.FIXED -> {
                val resourcesSingle = mutableListOf<PageResource>()
                val resourcesDouble = mutableListOf<PageResource>()

                // TODO needs work, currently showing two resources for fxl, needs to understand which two resources, left & right, or only right etc.
                var doublePageLeft: Link? = null
                var doublePageRight: Link?

                for ((index, link) in readingOrder.withIndex()) {
                    val url = viewModel.urlTo(link)
                    resourcesSingle.add(PageResource.EpubFxl(leftLink = link, leftUrl = url))

                    // add first page to the right,
                    if (index == 0) {
                        resourcesDouble.add(PageResource.EpubFxl(rightLink = link, rightUrl = url))
                    } else {
                        // add double pages, left & right
                        if (doublePageLeft == null) {
                            doublePageLeft = link
                        } else {
                            doublePageRight = link
                            resourcesDouble.add(
                                PageResource.EpubFxl(
                                    leftLink = doublePageLeft,
                                    leftUrl = viewModel.urlTo(doublePageLeft),
                                    rightLink = doublePageRight,
                                    rightUrl = viewModel.urlTo(doublePageRight)
                                )
                            )
                            doublePageLeft = null
                        }
                    }
                }
                // add last page if there is only a left page remaining
                if (doublePageLeft != null) {
                    resourcesDouble.add(
                        PageResource.EpubFxl(
                            leftLink = doublePageLeft,
                            leftUrl = viewModel.urlTo(doublePageLeft)
                        )
                    )
                }

                this.resourcesSingle = resourcesSingle
                this.resourcesDouble = resourcesDouble
            }
        }

        resourcePager = binding.resourcePager
        resetResourcePager()

        // Pre-create a spare WebView for faster chapter jumps
        prewarmWebViewPool()

        // Fixed layout publications cannot intercept JS events yet.
        if (publication.metadata.presentation.layout == EpubLayout.FIXED) {
            view = KeyInterceptorView(view, inputListener)
        }

        return view
    }

    override fun onDestroyView() {
        destroyWebViewPool()
        super.onDestroyView()
    }

    private fun resetResourcePager() {
        val parent = requireNotNull(resourcePager.parent as? ConstraintLayout) {
            "The parent view of the EPUB `resourcePager` must be a ConstraintLayout"
        }
        // We need to null out the adapter explicitly, otherwise the page fragments will leak.
        resourcePager.adapter = null
        parent.removeView(resourcePager)

        resourcePager = R2ViewPager(requireContext())
        resourcePager.offscreenPageLimit = 3
        resourcePager.id = R.id.resourcePager
        resourcePager.publicationType = when (publication.metadata.presentation.layout) {
            EpubLayout.REFLOWABLE, null -> R2ViewPager.PublicationType.EPUB
            EpubLayout.FIXED -> R2ViewPager.PublicationType.FXL
        }
        resourcePager.setBackgroundColor(viewModel.settings.value.effectiveBackgroundColor)
        // Let the page views handle the keyboard events.
        resourcePager.isFocusable = false
        resourcePager.addOnPageChangeListener(PageChangeListener())

        // Set initial scroll mode flag and orientation
        val isScrollEnabled = viewModel.isScrollEnabled.value
        //resourcePager.isScrollModeEnabled = isScrollEnabled

        // Set ViewPager orientation based on scroll mode
        // Scroll mode (vertical scrolling within chapter) = vertical orientation for chapter navigation
        // Paginated mode (horizontal paging within chapter) = horizontal orientation for chapter navigation
        resourcePager.setHorizontal(!isScrollEnabled)

        // Observe scroll mode changes and update flag + orientation
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.isScrollEnabled
                .flowWithLifecycle(viewLifecycleOwner.lifecycle)
                .collectLatest { scrollEnabled ->
                    //resourcePager.isScrollModeEnabled = scrollEnabled
                    resourcePager.setHorizontal(!scrollEnabled)
                    Timber.d("ViewPager scroll mode: $scrollEnabled, horizontal: ${!scrollEnabled}")
                }
        }

        parent.addView(resourcePager)

        resetResourcePagerAdapter()
    }

    private inner class PageChangeListener : ViewPager.SimpleOnPageChangeListener() {
        override fun onPageSelected(position: Int) {
            currentReflowablePageFragment?.webView?.let { webView ->
                if (viewModel.isScrollEnabled.value) {
                    if (currentPagerPosition < position) {
                        // handle swipe LEFT
                        webView.scrollToStart()
                    } else if (currentPagerPosition > position) {
                        // handle swipe RIGHT
                        webView.scrollToEnd()
                    }
                } else {
                    if (currentPagerPosition < position) {
                        // handle swipe LEFT
                        webView.setCurrentItem(0, false)
                    } else if (currentPagerPosition > position) {
                        // handle swipe RIGHT
                        webView.setCurrentItem(webView.numPages - 1, false)
                    }
                }
            }
            currentPagerPosition = position // Update current position

            notifyCurrentLocation()
        }
    }

    private fun resetResourcePagerAdapter() {
        adapter = when (publication.metadata.presentation.layout) {
            EpubLayout.REFLOWABLE, null -> {
                R2PagerAdapter(childFragmentManager, resourcesSingle)
            }
            EpubLayout.FIXED -> {
                when (viewModel.dualPageMode) {
                    // FIXME: Properly implement DualPage.AUTO depending on the device orientation.
                    DualPage.OFF, DualPage.AUTO -> {
                        R2PagerAdapter(childFragmentManager, resourcesSingle)
                    }
                    DualPage.ON -> {
                        R2PagerAdapter(childFragmentManager, resourcesDouble)
                    }
                }
            }
        }
        adapter.listener = PagerAdapterListener()
        resourcePager.adapter = adapter
        resourcePager.direction = overflow.value.readingProgression
        resourcePager.layoutDirection = when (settings.value.readingProgression) {
            ReadingProgression.RTL -> LayoutDirection.RTL
            ReadingProgression.LTR -> LayoutDirection.LTR
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.events
                    .onEach(::handleEvent)
                    .launchIn(this)

                var previousSettings = viewModel.settings.value
                viewModel.settings
                    .onEach {
                        onSettingsChange(previousSettings, it)
                        previousSettings = it
                    }
                    .launchIn(this)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.withStarted {
                // Restore the last locator before a configuration change (e.g. screen rotation), or the
                // initial locator when given.
                val locator = savedInstanceState?.let {
                    BundleCompat.getParcelable(
                        it,
                        "locator",
                        Locator::class.java
                    )
                }
                    ?: initialLocator
                if (locator != null) {
                    go(locator)
                }
            }
        }
    }

    private fun handleEvent(event: EpubNavigatorViewModel.Event) {
        when (event) {
            is EpubNavigatorViewModel.Event.RunScript -> {
                run(event.command)
            }
            is EpubNavigatorViewModel.Event.OpenInternalLink -> {
                go(event.target)
            }
            EpubNavigatorViewModel.Event.InvalidateViewPager -> {
                invalidateResourcePager()
            }
        }
    }

    private fun invalidateResourcePager() {
        val locator = currentLocator.value
        resetResourcePager()
        go(locator)
    }

    private fun onSettingsChange(previous: EpubSettings, new: EpubSettings) {
        if (previous.effectiveBackgroundColor != new.effectiveBackgroundColor) {
            resourcePager.setBackgroundColor(new.effectiveBackgroundColor)
        }

        if (viewModel.layout == EpubLayout.REFLOWABLE) {
            if (previous.fontSize != new.fontSize) {
                r2PagerAdapter?.setFontSize(new.fontSize)
            }
        }
    }

    private fun R2PagerAdapter.setFontSize(fontSize: Double) {
        if (config.useReadiumCssFontSize) return

        mFragments.forEach { _, fragment ->
            (fragment as? R2EpubPageFragment)?.setFontSize(fontSize)
        }
    }

    private inner class PagerAdapterListener : R2PagerAdapter.Listener {
        override fun onCreatePageFragment(fragment: Fragment) {
            if (viewModel.layout == EpubLayout.REFLOWABLE) {
                if (!config.useReadiumCssFontSize) {
                    (fragment as? R2EpubPageFragment)?.setFontSize(settings.value.fontSize)
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putParcelable("locator", currentLocator.value)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()

        if (publication == dummyPublication) {
            throw RestorationNotSupportedException
        }

        notifyCurrentLocation()
    }

    @OptIn(DelicateReadiumApi::class)
    override fun go(locator: Locator, animated: Boolean): Boolean {
        @Suppress("NAME_SHADOWING")
        val locator = publication.normalizeLocator(locator)

        if (state == State.Initializing) {
            state = State.Loading(locator.href)
        }

        listener?.onJumpToLocator(locator)

        val href = locator.href.removeFragment()

        fun setCurrent(resources: List<PageResource>) {
            val page = resources.withIndex().firstOrNull { (_, res) ->
                when (res) {
                    is PageResource.EpubReflowable ->
                        res.link.url().isEquivalent(href)
                    is PageResource.EpubFxl ->
                        res.leftUrl?.toString()?.endsWith(href.toString()) == true || res.rightUrl?.toString()?.endsWith(
                            href.toString()
                        ) == true
                    else -> false
                }
            } ?: return
            val (index, _) = page

            if (resourcePager.currentItem != index) {
                val isDistantJump = Math.abs(resourcePager.currentItem - index) > resourcePager.offscreenPageLimit
                if (isDistantJump) {
                    // Temporarily reduce offscreenPageLimit so only the target chapter
                    // loads first, instead of 7 chapters competing for CPU.
                    resourcePager.offscreenPageLimit = 1
                }
                resourcePager.currentItem = index
                if (isDistantJump) {
                    // Restore offscreenPageLimit after target chapter finishes loading.
                    // Use a coroutine to wait for the page fragment to load.
                    viewLifecycleOwner.lifecycleScope.launch {
                        // Wait for the adapter to create the fragment and for it to load
                        var attempts = 0
                        while (attempts < 50) { // max 5 seconds
                            val fragment = r2PagerAdapter?.getCurrentFragment() as? R2EpubPageFragment
                            if (fragment != null && fragment.isLoaded.value) {
                                break
                            }
                            delay(100)
                            attempts++
                        }
                        resourcePager.offscreenPageLimit = 3
                    }
                }
            }
            r2PagerAdapter?.loadLocatorAt(index, locator)
        }

        if (publication.metadata.presentation.layout != EpubLayout.FIXED) {
            setCurrent(resourcesSingle)
        } else {
            when (viewModel.dualPageMode) {
                // FIXME: Properly implement DualPage.AUTO depending on the device orientation.
                DualPage.OFF, DualPage.AUTO -> {
                    setCurrent(resourcesSingle)
                }
                DualPage.ON -> {
                    setCurrent(resourcesDouble)
                }
            }
        }

        return true
    }

    override fun go(link: Link, animated: Boolean): Boolean {
        val locator = publication.locatorFromLink(link) ?: return false
        return go(locator, animated)
    }

    private fun run(commands: List<RunScriptCommand>) {
        commands.forEach { run(it) }
    }

    private fun run(command: RunScriptCommand) {
        when (command.scope) {
            RunScriptCommand.Scope.CurrentResource -> {
                currentReflowablePageFragment
                    ?.runJavaScript(command.script)
            }
            RunScriptCommand.Scope.LoadedResources -> {
                r2PagerAdapter?.mFragments?.forEach { _, fragment ->
                    (fragment as? R2EpubPageFragment)
                        ?.takeIf { it.isLoaded.value }
                        ?.runJavaScript(command.script)
                }
            }
            is RunScriptCommand.Scope.LoadedResource -> {
                loadedFragmentForHref(command.scope.href)
                    ?.takeIf { it.isLoaded.value }
                    ?.runJavaScript(command.script)
            }
            is RunScriptCommand.Scope.WebView -> {
                command.scope.webView.runJavaScript(command.script)
            }
        }
    }

    // VisualNavigator

    override val publicationView: View
        get() = requireView()

    override val overflow: StateFlow<OverflowableNavigator.Overflow>
        get() = viewModel.overflow

    private val inputListener = CompositeInputListener()

    override fun addInputListener(listener: InputListener) {
        inputListener.add(listener)
    }

    override fun removeInputListener(listener: InputListener) {
        inputListener.remove(listener)
    }

    // SelectableNavigator

    override suspend fun currentSelection(): Selection? {
        val fragment = currentReflowablePageFragment ?: return null
        val json =
            fragment.runJavaScriptSuspend("readium.getCurrentSelection();")
                .takeIf { it != "null" }
                ?.let { tryOrLog { JSONObject(it) } }
                ?: return null

        val rect = json.optRectF("rect")
            ?.run { adjustedToViewport() }

        return Selection(
            locator = currentLocator.value.copy(
                text = Locator.Text.fromJSON(json.optJSONObject("text"))
            ),
            rect = rect
        )
    }

    override fun clearSelection() {
        run(viewModel.clearSelection())
    }

    private fun PointF.adjustedToViewport(): PointF =
        currentReflowablePageFragment?.paddingTop?.let { top ->
            PointF(x, y + top)
        } ?: this

    private fun RectF.adjustedToViewport(): RectF =
        currentReflowablePageFragment?.paddingTop?.let { topOffset ->
            RectF(left, top + topOffset, right, bottom)
        } ?: this

    // DecorableNavigator

    override fun <T : Decoration.Style> supportsDecorationStyle(style: KClass<T>): Boolean =
        viewModel.supportsDecorationStyle(style)

    override fun addDecorationListener(group: String, listener: DecorableNavigator.Listener) {
        viewModel.addDecorationListener(group, listener)
    }

    override fun removeDecorationListener(listener: DecorableNavigator.Listener) {
        viewModel.removeDecorationListener(listener)
    }

    @OptIn(DelicateReadiumApi::class)
    override suspend fun applyDecorations(decorations: List<Decoration>, group: String) {
        @Suppress("NAME_SHADOWING")
        val decorations = decorations
            .map { it.copy(locator = publication.normalizeLocator(it.locator)) }

        run(viewModel.applyDecorations(decorations, group))
    }

    // R2BasicWebView.Listener

    internal val webViewListener: R2BasicWebView.Listener = WebViewListener()

    private inner class WebViewListener : R2BasicWebView.Listener {

        override val readingProgression: ReadingProgression
            get() = viewModel.readingProgression

        override val verticalText: Boolean
            get() = viewModel.verticalText

        override fun onResourceLoaded(webView: R2BasicWebView, link: Link) {
            run(viewModel.onResourceLoaded(webView, link))
        }

        override fun onPageLoaded(webView: R2BasicWebView, link: Link) {
            paginationListener?.onPageLoaded()

            val href = link.url()
            if (state is State.Initializing || (state as? State.Loading)?.initialResourceHref?.isEquivalent(
                    href
                ) == true
            ) {
                state = State.Ready
            }

            // Inject JS to track which TOC fragment is at the top of the viewport
            injectTocFragmentTracker(webView, link)

            notifyCurrentLocation()
        }

        override fun javascriptInterfacesForResource(link: Link): Map<String, Any?> =
            config.javascriptInterfaces.mapValues { (_, factory) -> factory(link) }

        override fun onTap(point: PointF): Boolean =
            inputListener.onTap(TapEvent(point))

        override fun onDragStart(event: R2BasicWebView.DragEvent): Boolean =
            onDrag(DragEvent.Type.Start, event)

        override fun onDragMove(event: R2BasicWebView.DragEvent): Boolean =
            onDrag(DragEvent.Type.Move, event)

        override fun onDragEnd(event: R2BasicWebView.DragEvent): Boolean =
            onDrag(DragEvent.Type.End, event)

        private fun onDrag(type: DragEvent.Type, event: R2BasicWebView.DragEvent): Boolean =
            inputListener.onDrag(
                DragEvent(
                    type = type,
                    start = event.startPoint.adjustedToViewport(),
                    offset = event.offset
                )
            )

        override fun onKey(event: KeyEvent): Boolean =
            inputListener.onKey(event)

        override fun onDecorationActivated(
            id: DecorationId,
            group: String,
            rect: RectF,
            point: PointF,
        ): Boolean =
            viewModel.onDecorationActivated(
                id = id,
                group = group,
                rect = rect.adjustedToViewport(),
                point = point.adjustedToViewport()
            )

        override fun onProgressionChanged() {
            notifyCurrentLocation()
        }

        override fun goToPreviousResource(jump: Boolean, animated: Boolean): Boolean {
            return this@EpubNavigatorFragment.goToPreviousResource(jump = jump, animated = animated)
        }

        override fun goToNextResource(jump: Boolean, animated: Boolean): Boolean {
            return this@EpubNavigatorFragment.goToNextResource(jump = jump, animated = animated)
        }

        override val selectionActionModeCallback: ActionMode.Callback?
            get() = config.selectionActionModeCallback

        /**
         * Prevents opening external links in the web view and handles internal links.
         */
        override fun shouldOverrideUrlLoading(webView: WebView, request: WebResourceRequest): Boolean {
            val url = request.url.toAbsoluteUrl() ?: return false
            viewModel.navigateToUrl(url)
            return true
        }

        override fun onFootnoteLinkActivated(
            url: AbsoluteUrl,
            context: HyperlinkNavigator.FootnoteContext,
        ) {
            viewModel.navigateToUrl(url, context)
        }

        override fun shouldInterceptRequest(webView: WebView, request: WebResourceRequest): WebResourceResponse? =
            viewModel.shouldInterceptRequest(request)

        override fun resourceAtUrl(url: Url): Resource? =
            viewModel.internalLinkFromUrl(url)
                ?.let { publication.get(it) }

        // override fun onVerticalOverscrollBottom() {
        //     // Triggered when user scrolls past bottom edge in scroll mode
        //     // Debouncing is handled in R2EpubPageFragment, so we can trigger immediately
        //     Timber.d("🔽 onVerticalOverscrollBottom - navigating to next chapter")
        //     viewLifecycleOwner.lifecycleScope.launch {
        //         if (goToNextResource(jump = true, animated = true)) {
        //             Timber.d("🔽 Successfully navigated to next chapter")
        //             // Wait briefly for page to load, then scroll to top
        //             delay(50)
        //             currentReflowablePageFragment?.webView?.scrollToStart()
        //         } else {
        //             Timber.d("🔽 Already at last chapter")
        //         }
        //     }
        // }

        // override fun onVerticalOverscrollTop() {
        //     // Triggered when user scrolls past top edge in scroll mode
        //     // Debouncing is handled in R2EpubPageFragment, so we can trigger immediately
        //     Timber.d("🔼 onVerticalOverscrollTop - navigating to previous chapter")
        //     viewLifecycleOwner.lifecycleScope.launch {
        //         if (goToPreviousResource(jump = true, animated = true)) {
        //             Timber.d("🔼 Successfully navigated to previous chapter")
        //             // Wait briefly for page to load, then scroll to bottom
        //             delay(50)
        //             currentReflowablePageFragment?.webView?.scrollToEnd()
        //         } else {
        //             Timber.d("🔼 Already at first chapter")
        //         }
        //     }
        // }
    }

    override fun goForward(animated: Boolean): Boolean {
        if (publication.metadata.presentation.layout == EpubLayout.FIXED) {
            return goToNextResource(jump = false, animated = animated)
        }

        val webView = currentReflowablePageFragment?.webView ?: return false

        when (settings.value.readingProgression) {
            ReadingProgression.LTR ->
                webView.scrollRight(animated)

            ReadingProgression.RTL ->
                webView.scrollLeft(animated)
        }
        return true
    }

    override fun goBackward(animated: Boolean): Boolean {
        if (publication.metadata.presentation.layout == EpubLayout.FIXED) {
            return goToPreviousResource(jump = false, animated = animated)
        }

        val webView = currentReflowablePageFragment?.webView ?: return false

        when (settings.value.readingProgression) {
            ReadingProgression.LTR ->
                webView.scrollLeft(animated)

            ReadingProgression.RTL ->
                webView.scrollRight(animated)
        }
        return true
    }

    /**
     * Returns whether a forward/backward page turn can move to another page or
     * resource. This does not mutate navigator state.
     */
    public fun canNavigate(forward: Boolean): Boolean {
        if (!::resourcePager.isInitialized) return false

        val adapter = resourcePager.adapter ?: return false
        if (publication.metadata.presentation.layout == EpubLayout.FIXED) {
            return if (forward) {
                resourcePager.currentItem < adapter.count - 1
            } else {
                resourcePager.currentItem > 0
            }
        }

        val webView = currentReflowablePageFragment?.webView ?: return false
        val canScrollWithinResource = when (settings.value.readingProgression) {
            ReadingProgression.LTR ->
                webView.canScrollHorizontally(if (forward) 1 else -1)

            ReadingProgression.RTL ->
                webView.canScrollHorizontally(if (forward) -1 else 1)
        }

        if (canScrollWithinResource) return true

        return if (forward) {
            resourcePager.currentItem < adapter.count - 1
        } else {
            resourcePager.currentItem > 0
        }
    }

    private fun goToNextResource(jump: Boolean, animated: Boolean): Boolean {
        val adapter = resourcePager.adapter ?: return false
        if (resourcePager.currentItem >= adapter.count - 1) {
            return false
        }

        if (jump) {
            locatorToNextResource()?.let { listener?.onJumpToLocator(it) }
        }

        resourcePager.setCurrentItem(resourcePager.currentItem + 1, animated)

        currentReflowablePageFragment?.webView?.let { webView ->
            if (settings.value.readingProgression == ReadingProgression.RTL) {
                webView.setCurrentItem(webView.numPages - 1, false)
            } else {
                webView.setCurrentItem(0, false)
            }
        }

        return true
    }

    private fun goToPreviousResource(jump: Boolean, animated: Boolean): Boolean {
        if (resourcePager.currentItem <= 0) {
            return false
        }

        if (jump) {
            locatorToPreviousResource()?.let { listener?.onJumpToLocator(it) }
        }

        resourcePager.setCurrentItem(resourcePager.currentItem - 1, animated)

        currentReflowablePageFragment?.webView?.let { webView ->
            if (settings.value.readingProgression == ReadingProgression.RTL) {
                webView.setCurrentItem(0, false)
            } else {
                Log.d("EpubNavigator", "🔙 goToPreviousResource: Setting webView to last page: ${webView.numPages}")
                webView.setCurrentItem(webView.numPages - 1, false)
            }
        }

        return true
    }

    private fun locatorToPreviousResource(): Locator? =
        locatorToResourceAtIndex(resourcePager.currentItem - 1)

    private fun locatorToNextResource(): Locator? =
        locatorToResourceAtIndex(resourcePager.currentItem + 1)

    private fun locatorToResourceAtIndex(index: Int): Locator? =
        readingOrder.getOrNull(index)
            ?.let { publication.locatorFromLink(it) }

    private val r2PagerAdapter: R2PagerAdapter?
        get() = if (::resourcePager.isInitialized) {
            resourcePager.adapter as? R2PagerAdapter
        } else {
            null
        }

    private val currentReflowablePageFragment: R2EpubPageFragment? get() =
        currentFragment as? R2EpubPageFragment

    /**
     * WebView for the currently visible reflowable page. Used to map selection/highlight rects
     * (viewport space, physical px from JS) to window coordinates. Prefer over scanning the view
     * hierarchy, which may pick a non-current page in the ViewPager.
     */
    public fun activeWebViewForHighlight(): WebView? =
        currentReflowablePageFragment?.webView

    private val currentFragment: Fragment? get() =
        fragmentAt(resourcePager.currentItem)

    private fun fragmentAt(index: Int): Fragment? =
        r2PagerAdapter?.mFragments?.get(adapter.getItemId(index))

    /**
     * Returns the reflowable page fragment matching the given href, if it is already loaded in the
     * view pager.
     */
    private fun loadedFragmentForHref(href: Url): R2EpubPageFragment? {
        val adapter = r2PagerAdapter ?: return null
        adapter.mFragments.forEach { _, fragment ->
            val pageFragment = fragment as? R2EpubPageFragment ?: return@forEach
            val link = pageFragment.link ?: return@forEach
            if (link.url() == href) {
                return pageFragment
            }
        }
        return null
    }

    override val currentLocator: StateFlow<Locator> get() = _currentLocator
    private val _currentLocator = MutableStateFlow(
        initialLocator
            ?: requireNotNull(publication.locatorFromLink(this.readingOrder.first()))
    )

    /**
     * Returns the [Locator] to the first HTML *block* element that is visible on the screen, even
     * if it begins on previous screen pages.
     */
    @ExperimentalReadiumApi
    override suspend fun firstVisibleElementLocator(): Locator? {
        if (!::resourcePager.isInitialized) return null

        return when (viewModel.layout) {
            EpubLayout.FIXED ->
                currentLocator.value

            EpubLayout.REFLOWABLE -> {
                val resource = readingOrder[resourcePager.currentItem]
                currentReflowablePageFragment?.webView?.findFirstVisibleLocator()
                    ?.copy(
                        href = resource.url(),
                        mediaType = resource.mediaType ?: MediaType.XHTML
                    )
            }
        }
    }

    /**
     * While scrolling we receive a lot of new current locations, so we use a coroutine job to
     * debounce the notification.
     */
    private var debounceLocationNotificationJob: Job? = null

    /**
     * Mapping between reading order base hrefs (url string, no fragment) and their TOC fragment IDs.
     * Used to inject JS that tracks which sub-chapter is currently visible.
     */
    private val tocFragmentsByHref: Map<String, List<String>> by lazy {
        val map = mutableMapOf<String, MutableList<String>>()
        fun collect(links: List<Link>) {
            for (link in links) {
                val full = link.url().toString()
                if (full.contains("#")) {
                    val base = full.substringBefore("#")
                    val frag = full.substringAfter("#")
                    map.getOrPut(base) { mutableListOf() }.add(frag)
                }
                collect(link.children)
            }
        }
        collect(publication.tableOfContents)
        map
    }

    /**
     * Mapping between TOC url strings (including fragments) and their titles.
     * Used to resolve sub-chapter titles when a TOC fragment is active.
     */
    private val tocTitleByUrlString: Map<String, String> by lazy {
        val map = mutableMapOf<String, String>()
        fun collect(links: List<Link>) {
            for (link in links) {
                val title = link.title
                if (!title.isNullOrEmpty()) {
                    map.putIfAbsent(link.url().toString(), title)
                }
                collect(link.children)
            }
        }
        collect(publication.tableOfContents)
        map
    }

    /** Inject a function that finds which TOC fragment anchor is at the top of the viewport. */
    private fun injectTocFragmentTracker(webView: R2BasicWebView, link: Link) {
        val baseHref = link.url().toString().substringBefore("#")
        val fragments = tocFragmentsByHref[baseHref]
        Log.d("EpubNavigator", "::injectTocFragmentTracker baseHref=$baseHref, fragments=$fragments, tocFragmentsByHref keys=${tocFragmentsByHref.keys}")
        if (fragments.isNullOrEmpty()) return

        val fragsJson = fragments.joinToString(",") { "\"${it.replace("\"", "\\\"")}\"" }
        val script = """
            (function() {
                var frags = [$fragsJson];
                window.__getTocFragment = function() {
                    var best = '';
                    var vw = window.innerWidth;
                    var vh = window.innerHeight;
                    for (var i = 0; i < frags.length; i++) {
                        var el = document.getElementById(frags[i]);
                        if (!el) continue;
                        var r = el.getBoundingClientRect();
                        // In paginated mode (CSS columns): element is on or before current page
                        // In scroll mode: element is at or above viewport top
                        if (r.top < vh && r.left < vw) best = frags[i];
                    }
                    return best;
                };
            })();
        """.trimIndent()
        webView.evaluateJavascript(script, null)
    }

    /**
     * Mapping between reading order hrefs and the table of contents title.
     */
    private val tableOfContentsTitleByHref: Map<Href, String> by lazy {
        fun fulfill(linkList: List<Link>): MutableMap<Href, String> {
            var result: MutableMap<Href, String> = mutableMapOf()

            for (link in linkList) {
                val title = link.title ?: ""

                if (title.isNotEmpty()) {
                    result[link.href] = title
                }

                val subResult = fulfill(link.children)

                result = (subResult + result) as MutableMap<Href, String>
            }

            return result
        }

        fulfill(publication.tableOfContents).toMap()
    }

    private fun notifyCurrentLocation() {
        // Make sure viewLifecycleOwner is accessible.
        view ?: return

        debounceLocationNotificationJob?.cancel()
        debounceLocationNotificationJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(100L)

            // We don't want to notify the current location if the navigator is still loading a
            // locator, to avoid notifying intermediate locations.
            if (currentReflowablePageFragment?.isLoaded?.value == false || state != State.Ready) {
                return@launch
            }

            val reflowableWebView = currentReflowablePageFragment?.webView
            val progression = reflowableWebView?.run {
                // The transition has stabilized, so we can ask the web view to refresh its current
                // item to reflect the current scroll position.
                updateCurrentItem()
                progression.coerceIn(0.0, 1.0)
            } ?: 0.0

            Log.d("EpubNavigator", "::notifyCurrentLocation progression=$progression, mCurItem=${reflowableWebView?.mCurItem}, numPages=${reflowableWebView?.numPages}")

            val link = when (val pageResource = adapter.getResource(resourcePager.currentItem)) {
                is PageResource.EpubFxl -> checkNotNull(
                    pageResource.leftLink ?: pageResource.rightLink
                )
                is PageResource.EpubReflowable -> pageResource.link
                else -> throw IllegalStateException(
                    "Expected EpubFxl or EpubReflowable page resources"
                )
            }
            val positionLocator = publication.positionsByResource[link.url()]?.let { positions ->
                val index = ceil(progression * (positions.size - 1)).toInt()
                positions.getOrNull(index)
            }

            val pageCount = reflowableWebView?.numPages ?: 0
            val curItem = reflowableWebView?.mCurItem ?: 0
            val baseLocations = positionLocator?.locations ?: Locator.Locations()
            val otherWithPageCount = if (pageCount > 0) {
                val pageEndProgression = minOf((curItem + 1.0) / pageCount, 1.0)
                baseLocations.otherLocations + ("pageCount" to pageCount) + ("pageEndProgression" to pageEndProgression)
            } else {
                baseLocations.otherLocations
            }
            // Read the current TOC fragment by calling the injected function on-demand
            // (works in both paginated and scroll modes)
            val tocFragment = reflowableWebView?.let { wv ->
                kotlinx.coroutines.suspendCancellableCoroutine<String> { cont ->
                    wv.evaluateJavascript("(typeof window.__getTocFragment === 'function') ? window.__getTocFragment() : ''") { result ->
                        // Result is JSON-encoded, e.g. "\"section1\"" or "\"\""
                        val cleaned = result?.trim()?.removeSurrounding("\"") ?: ""
                        cont.resumeWith(Result.success(cleaned))
                    }
                }
            } ?: ""

            // Extract visible text at viewport top for cross-device sync precision
            val visibleText = reflowableWebView?.let { wv ->
                kotlinx.coroutines.suspendCancellableCoroutine<String?> { cont ->
                    wv.evaluateJavascript(
                        "(function(){ var loc = readium.findFirstVisibleLocator(); return loc && loc.text ? loc.text.highlight || '' : ''; })()"
                    ) { result ->
                        val cleaned = result?.trim()?.removeSurrounding("\"") ?: ""
                        cont.resumeWith(Result.success(cleaned.ifEmpty { null }))
                    }
                }
            }

            // Build href with fragment if a TOC sub-section is visible
            val locatorHref = if (tocFragment.isNotEmpty()) {
                val baseUrl = link.url().toString()
                val urlWithFrag = "$baseUrl#$tocFragment"
                val constructed = Url(urlWithFrag)
                Log.d("EpubNavigator", "::notifyCurrentLocation tocFragment='$tocFragment', constructedUrl=${constructed}, urlString='$urlWithFrag'")
                constructed ?: link.url()
            } else {
                link.url()
            }

            // Resolve title: prefer sub-chapter title from TOC when fragment is active
            val resolvedTitle = if (tocFragment.isNotEmpty()) {
                val urlWithFragment = "${link.url()}#$tocFragment"
                tocTitleByUrlString[urlWithFragment]
                    ?: tableOfContentsTitleByHref[link.href]
                    ?: positionLocator?.title
                    ?: link.title
            } else {
                tableOfContentsTitleByHref[link.href] ?: positionLocator?.title ?: link.title
            }

            val currentLocator = Locator(
                href = locatorHref,
                mediaType = link.mediaType ?: MediaType.XHTML,
                title = resolvedTitle,
                locations = baseLocations.copy(
                    progression = progression,
                    otherLocations = otherWithPageCount
                ),
                text = if (!visibleText.isNullOrEmpty()) {
                    Locator.Text(highlight = visibleText)
                } else {
                    positionLocator?.text ?: Locator.Text()
                }
            )

            Log.d("EpubNavigator", "::notifyCurrentLocation CREATED LOCATOR: href=${currentLocator.href}, title=${currentLocator.title}, progression=${currentLocator.locations.progression}, totalProgression=${currentLocator.locations.totalProgression}")

            _currentLocator.value = currentLocator

            // Deprecated notifications
            reflowableWebView?.let {
                Log.d("EpubNavigator", "::notifyCurrentLocation CALLING paginationListener.onPageChanged with progression=${currentLocator.locations.progression}")
                paginationListener?.onPageChanged(
                    pageIndex = it.mCurItem,
                    totalPages = it.numPages,
                    locator = currentLocator
                )
            }
        }
    }

    public companion object {

        /**
         * Creates a factory for a dummy [EpubNavigatorFragment].
         *
         * Used when Android restore the [EpubNavigatorFragment] after the process was killed. You
         * need to make sure the fragment is removed from the screen before [onResume] is called.
         */
        public fun createDummyFactory(): FragmentFactory = createFragmentFactory {
            EpubNavigatorFragment(
                publication = dummyPublication,
                initialLocator = Locator(href = Url("#")!!, mediaType = MediaType.XHTML),
                readingOrder = null,
                initialPreferences = EpubPreferences(),
                listener = null,
                paginationListener = null,
                epubLayout = EpubLayout.REFLOWABLE,
                defaults = EpubDefaults(),
                configuration = Configuration()
            )
        }

        /**
         * Returns a URL to the application asset at [path], served in the web views.
         *
         * Returns null if the given [path] is not valid or an absolute URL.
         */
        public fun assetUrl(path: String): Url? =
            WebViewServer.assetUrl(path)
    }
}

@ExperimentalReadiumApi
private val EpubSettings.effectiveBackgroundColor: Int get() =
    backgroundColor?.int ?: theme.backgroundColor
