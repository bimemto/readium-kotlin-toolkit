/*
 * Copyright 2022 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

package org.readium.r2.navigator.epub

import org.readium.r2.navigator.epub.css.ReadiumCss
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.epub.EpubLayout
import org.readium.r2.shared.publication.presentation.presentation
import org.readium.r2.shared.publication.services.isProtected
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.data.ReadError
import org.readium.r2.shared.util.mediatype.MediaType
import org.readium.r2.shared.util.resource.Resource
import org.readium.r2.shared.util.resource.TransformingResource
import timber.log.Timber

/**
 * Injects the Readium CSS files and scripts in the HTML [Resource] receiver.
 *
 * @param baseHref Base URL where the Readium CSS and scripts are served.
 */
@OptIn(ExperimentalReadiumApi::class)
internal fun Resource.injectHtml(
    publication: Publication,
    mediaType: MediaType,
    css: ReadiumCss,
    baseHref: AbsoluteUrl,
    disableSelectionWhenProtected: Boolean,
): Resource =
    TransformingResource(this) { bytes ->
        if (!mediaType.isHtml) {
            return@TransformingResource Try.success(bytes)
        }

        var content = bytes.toString(mediaType.charset ?: Charsets.UTF_8).trim()
        val injectables = mutableListOf<String>()

        if (publication.metadata.presentation.layout == EpubLayout.FIXED) {
            injectables.add(
                script(baseHref.resolve(Url("readium/scripts/readium-fixed.js")!!))
            )
        } else {
            content = try {
                css.injectHtml(content)
            } catch (e: Exception) {
                return@TransformingResource Try.failure(ReadError.Decoding(e))
            }

            injectables.add(
                script(
                    baseHref.resolve(Url("readium/scripts/readium-reflowable.js")!!)
                )
            )
            // GOG: apply symmetric body padding during HTML parse (before first paint) to avoid
            // layout jump from late WebView.evaluateJavascript on Android.
            injectables.add(gogReaderSymmetricBodyPaddingScript())
        }

        // Disable the text selection if the publication is protected.
        // FIXME: This is a hack until proper LCP copy is implemented, see https://github.com/readium/kotlin-toolkit/issues/221
        if (disableSelectionWhenProtected && publication.isProtected) {
            injectables.add(
                """
                <style>
                *:not(input):not(textarea) {
                    user-select: none;
                    -webkit-user-select: none;
                }
                </style>
            """
            )
        }

        val headEndIndex = content.indexOf("</head>", 0, true)
        if (headEndIndex == -1) {
            Timber.e("</head> closing tag not found in resource with href: $sourceUrl")
        } else {
            content = StringBuilder(content)
                .insert(headEndIndex, "\n" + injectables.joinToString("\n") + "\n")
                .toString()
        }

        Try.success(content.toByteArray())
    }

private fun script(src: Url): String =
    """<script type="text/javascript" src="$src"></script>"""

/**
 * Inline script: add [GOG_READER_BODY_SYMMETRIC_EXTRA_CSS_PX] to body padding-left/right from the
 * first computed values, re-apply on DOM mutations (Readium may set margins after load).
 * Must stay in sync with iOS EPUBViewController `noteIconSymmetricInsetCssPx`.
 */
private const val GOG_READER_BODY_SYMMETRIC_EXTRA_CSS_PX: Int = 4

@Suppress("MaxLineLength")
private fun gogReaderSymmetricBodyPaddingScript(): String =
    """
    <script>
    (function(){
      try {
        var EXTRA = ${GOG_READER_BODY_SYMMETRIC_EXTRA_CSS_PX};
        function disconnectOld() {
          if (window.__gogNoteIconPaddingMO) {
            window.__gogNoteIconPaddingMO.disconnect();
            window.__gogNoteIconPaddingMO = null;
          }
        }
        function ensureOrig() {
          var b = document.body;
          if (!b) return false;
          if (!b.dataset.gogOrigPadL) {
            var cs = window.getComputedStyle(b);
            b.dataset.gogOrigPadL = cs.paddingLeft || '0px';
            b.dataset.gogOrigPadR = cs.paddingRight || '0px';
          }
          return true;
        }
        function px(v) {
          var n = parseFloat(v || '0');
          return isNaN(n) ? 0 : n;
        }
        function update() {
          var b = document.body;
          if (!b) return;
          if (!ensureOrig()) return;
          var origL = b.dataset.gogOrigPadL || '0px';
          var origR = b.dataset.gogOrigPadR || '0px';
          b.style.setProperty('padding-left', (px(origL) + EXTRA) + 'px', 'important');
          b.style.setProperty('padding-right', (px(origR) + EXTRA) + 'px', 'important');
        }
        function start() {
          disconnectOld();
          update();
          window.__gogNoteIconPaddingMO = new MutationObserver(function(){ update(); });
          window.__gogNoteIconPaddingMO.observe(document.documentElement, { childList: true, subtree: true, attributes: true });
          window.addEventListener('load', function(){ update(); }, { once: true });
          requestAnimationFrame(function(){ update(); });
        }
        if (document.readyState === 'loading') {
          document.addEventListener('DOMContentLoaded', start, { once: true });
        } else {
          start();
        }
      } catch (e) {}
    })();
    </script>
    """.trimIndent()
