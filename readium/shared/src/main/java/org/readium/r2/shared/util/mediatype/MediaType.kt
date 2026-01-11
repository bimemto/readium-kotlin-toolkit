/*
 * Module: r2-shared-kotlin
 * Developers: Mickaël Menu
 *
 * Copyright (c) 2020. Readium Foundation. All rights reserved.
 * Use of this source code is governed by a BSD-style license which is detailed in the
 * LICENSE file present in the project repository where this source code is maintained.
 */

package org.readium.r2.shared.util.mediatype

import android.os.Parcelable
import java.nio.charset.Charset
import java.util.Locale
import kotlinx.parcelize.Parcelize

/**
 * Represents a document format, identified by a unique RFC 6838 media type.
 *
 * [MediaType] handles:
 *  - components parsing – eg. type, subtype and parameters,
 *  - media types comparison.
 *
 * Comparing media types is more complicated than it looks, since they can contain parameters,
 * such as `charset=utf-8`. We can't ignore them because some formats use parameters in their
 * media type, for example `application/atom+xml;profile=opds-catalog` for an OPDS 1 catalog.
 *
 * Specification: https://tools.ietf.org/html/rfc6838
 *
 * @param type The type component, e.g. `application` in `application/epub+zip`.
 * @param subtype The subtype component, e.g. `epub+zip` in `application/epub+zip`.
 * @param parameters The parameters in the media type, such as `charset=utf-8`.
 */
@Parcelize
public class MediaType private constructor(
    public val type: String,
    public val subtype: String,
    public val parameters: Map<String, String>,
) : Parcelable {

    /**
     * Structured syntax suffix, e.g. `+zip` in `application/epub+zip`.
     *
     * Gives a hint on the underlying structure of this media type.
     * See. https://tools.ietf.org/html/rfc6838#section-4.2.8
     */
    public val structuredSyntaxSuffix: String? get() {
        val parts = subtype.split("+")
        return if (parts.size > 1) "+${parts.last()}" else null
    }

    /**
     * Encoding as declared in the `charset` parameter, if there's any.
     */
    public val charset: Charset? get() =
        parameters["charset"]?.let { Charset.forName(it) }

    /** The string representation of this media type. */
    override fun toString(): String {
        var params = parameters.map { "${it.key}=${it.value}" }
            .sorted()
            .joinToString(separator = ";")
        if (params.isNotEmpty()) {
            params = ";$params"
        }
        return "$type/$subtype$params"
    }

    /**
     * Returns whether two media types are equal, checking the type, subtype and parameters.
     * Parameters order is ignored.
     *
     * WARNING: Strict media type comparisons can be a source of bug, if parameters are present.
     * `text/html` != `text/html;charset=utf-8` with strict equality comparison, which is most
     * likely not the desired result. Instead, you can use [matches] to check if any of the media
     * types is a parameterized version of the other one.
     */
    override fun equals(other: Any?): Boolean {
        return toString() == (other as? MediaType)?.toString()
    }

    override fun hashCode(): Int {
        var result = type.hashCode()
        result = 31 * result + subtype.hashCode()
        result = 31 * result + parameters.hashCode()
        return result
    }

    /**
     * Returns whether the given [other] media type is included in this media type.
     *
     * For example, `text/html` contains `text/html;charset=utf-8`.
     *
     * - [other] must match the parameters in the [parameters] property, but extra parameters
     *    are ignored.
     * - Order of parameters is ignored.
     * - Wildcards are supported, meaning that `image/*` contains `image/png` and `*/*` contains
     *   everything.
     */
    public fun contains(other: MediaType?): Boolean {
        if (other == null || (type != "*" && type != other.type) || (subtype != "*" && subtype != other.subtype)) {
            return false
        }
        val paramsSet = parameters.map { "${it.key}=${it.value}" }.toSet()
        val otherParamsSet = other.parameters.map { "${it.key}=${it.value}" }.toSet()
        return otherParamsSet.containsAll(paramsSet)
    }

    /**
     * Returns whether the given [other] media type is included in this media type.
     */
    public fun contains(other: String?): Boolean {
        val mediaType = other?.let { MediaType.invoke(it) }
            ?: return false

        return contains(mediaType)
    }

    /**
     * Returns whether this media type and `other` are the same, ignoring parameters that are not
     * in both media types.
     *
     * For example, `text/html` matches `text/html;charset=utf-8`, but `text/html;charset=ascii`
     * doesn't. This is basically like `contains`, but working in both direction.
     */
    public fun matches(other: MediaType?): Boolean =
        contains(other) || (other?.contains(this) == true)

    /**
     * Returns whether this media type and `other` are the same, ignoring parameters that are not
     * in both media types.
     */
    public fun matches(other: String?): Boolean =
        matches(other?.let { MediaType.invoke(it) })

    /**
     * Returns whether this media type matches any of the `others` media types.
     */
    public fun matchesAny(vararg others: MediaType?): Boolean =
        others.any { matches(it) }

    /**
     * Returns whether this media type matches any of the `others` media types.
     */
    public fun matchesAny(vararg others: String?): Boolean =
        others.any { matches(it) }

    /** Returns whether this media type is structured as a ZIP archive. */
    public val isZip: Boolean get() =
        matchesAny(ZIP, LCP_PROTECTED_AUDIOBOOK, LCP_PROTECTED_PDF) ||
            structuredSyntaxSuffix == "+zip"

    /** Returns whether this media type is structured as a JSON file. */
    public val isJson: Boolean get() =
        matches(JSON) || structuredSyntaxSuffix == "+json"

    /** Returns whether this media type is of an OPDS feed. */
    public val isOpds: Boolean get() =
        matchesAny(OPDS1, OPDS1_ENTRY, OPDS2, OPDS2_PUBLICATION, OPDS_AUTHENTICATION)

    /** Returns whether this media type is of an HTML document. */
    public val isHtml: Boolean get() =
        matchesAny(HTML, XHTML)

    /** Returns whether this media type is of a bitmap image, so excluding vectorial formats. */
    public val isBitmap: Boolean get() =
        matchesAny(BMP, GIF, JPEG, PNG, TIFF, WEBP)

    /** Returns whether this media type is of an audio clip. */
    public val isAudio: Boolean get() =
        type == "audio"

    /** Returns whether this media type is of a video clip. */
    public val isVideo: Boolean get() =
        type == "video"

    /** Returns whether this media type is of a Readium Web Publication Manifest. */
    public val isRwpm: Boolean get() =
        matchesAny(READIUM_AUDIOBOOK_MANIFEST, DIVINA_MANIFEST, READIUM_WEBPUB_MANIFEST)

    public val isRpf: Boolean get() = matchesAny(
        READIUM_WEBPUB,
        READIUM_AUDIOBOOK,
        DIVINA,
        LCP_PROTECTED_PDF,
        LCP_PROTECTED_AUDIOBOOK
    )

    /** Returns whether this media type is of a publication file. */
    public val isPublication: Boolean get() =
        matchesAny(CBZ, EPUB, LPF, PDF, W3C_WPUB_MANIFEST, ZAB) || isRwpm || isRpf

    public companion object {

        /**
         * Creates a [MediaType] from its RFC 6838 string representation.
         */
        public operator fun invoke(string: String): MediaType? {
            if (string.isEmpty()) {
                return null
            }

            // Grammar: https://tools.ietf.org/html/rfc2045#section-5.1
            val components = string.split(";")
                .map { it.trim() }
            val types = components[0].split("/")
            if (types.size != 2) {
                return null
            }

            // > Both top-level type and subtype names are case-insensitive.
            val type = types[0].lowercase(Locale.ROOT)
            val subtype = types[1].lowercase(Locale.ROOT)

            // > Parameter names are case-insensitive and no meaning is attached to the order in which
            // > they appear.
            val parameters = components.drop(1)
                .map { it.split("=") }
                .filter { it.size == 2 }
                .associate { Pair(it[0].lowercase(Locale.ROOT), it[1]) }
                .toMutableMap()

            // For now, we only support case-insensitive `charset`.
            //
            // > Parameter values might or might not be case-sensitive, depending on the semantics of
            // > the parameter name.
            // > https://tools.ietf.org/html/rfc2616#section-3.7
            //
            // > The character set names may be up to 40 characters taken from the printable characters
            // > of US-ASCII.  However, no distinction is made between use of upper and lower case
            // > letters.
            // > https://www.iana.org/assignments/character-sets/character-sets.xhtml
            parameters["charset"]?.let {
                parameters["charset"] =
                    (
                        try {
                            Charset.forName(it).name()
                        } catch (e: Exception) {
                            it
                        }
                        ).uppercase(Locale.ROOT)
            }

            return MediaType(
                type = type,
                subtype = subtype,
                parameters = parameters
            )
        }

        // Known Media Types
        //
        // Reading apps are welcome to extend the static constants with additional media types.

        public val AAC: MediaType = MediaType.invoke("audio/aac")!!
        public val ACSM: MediaType = MediaType.invoke("application/vnd.adobe.adept+xml")!!
        public val AIFF: MediaType = MediaType.invoke("audio/aiff")!!
        public val AVI: MediaType = MediaType.invoke("video/x-msvideo")!!
        public val AVIF: MediaType = MediaType.invoke("image/avif")!!
        public val BINARY: MediaType = MediaType.invoke("application/octet-stream")!!
        public val BMP: MediaType = MediaType.invoke("image/bmp")!!
        public val CBR: MediaType = MediaType.invoke("application/vnd.comicbook-rar")!!
        public val CBZ: MediaType = MediaType.invoke("application/vnd.comicbook+zip")!!
        public val CSS: MediaType = MediaType.invoke("text/css")!!
        public val DIVINA: MediaType = MediaType.invoke("application/divina+zip")!!
        public val DIVINA_MANIFEST: MediaType = MediaType.invoke("application/divina+json")!!
        public val EPUB: MediaType = MediaType.invoke("application/epub+zip")!!
        public val FLAC: MediaType = MediaType.invoke("audio/flac")!!
        public val GIF: MediaType = MediaType.invoke("image/gif")!!
        public val GZ: MediaType = MediaType.invoke("application/gzip")!!
        public val HTML: MediaType = MediaType.invoke("text/html")!!
        public val JAVASCRIPT: MediaType = MediaType.invoke("text/javascript")!!
        public val JPEG: MediaType = MediaType.invoke("image/jpeg")!!
        public val JSON: MediaType = MediaType.invoke("application/json")!!
        public val JSON_PROBLEM_DETAILS: MediaType = MediaType.invoke("application/problem+json")!!
        public val JXL: MediaType = MediaType.invoke("image/jxl")!!
        public val LCP_LICENSE_DOCUMENT: MediaType = MediaType.invoke(
            "application/vnd.readium.lcp.license.v1.0+json"
        )!!
        public val LCP_PROTECTED_AUDIOBOOK: MediaType = MediaType.invoke("application/audiobook+lcp")!!
        public val LCP_PROTECTED_PDF: MediaType = MediaType.invoke("application/pdf+lcp")!!
        public val LCP_STATUS_DOCUMENT: MediaType = MediaType.invoke(
            "application/vnd.readium.license.status.v1.0+json"
        )!!
        public val LPF: MediaType = MediaType.invoke("application/lpf+zip")!!
        public val MP3: MediaType = MediaType.invoke("audio/mpeg")!!
        public val MP4: MediaType = MediaType.invoke("audio/mp4")!!
        public val MPEG: MediaType = MediaType.invoke("video/mpeg")!!
        public val NCX: MediaType = MediaType.invoke("application/x-dtbncx+xml")!!
        public val OGG: MediaType = MediaType.invoke("audio/ogg")!!
        public val OGV: MediaType = MediaType.invoke("video/ogg")!!
        public val OPDS1: MediaType = MediaType.invoke("application/atom+xml;profile=opds-catalog")!!
        public val OPDS1_NAVIGATION_FEED: MediaType = MediaType.invoke(
            "application/atom+xml;profile=opds-catalog;kind=navigation"
        )!!
        public val OPDS1_ACQUISITION_FEED: MediaType = MediaType.invoke(
            "application/atom+xml;profile=opds-catalog;kind=acquisition"
        )!!
        public val OPDS1_ENTRY: MediaType = MediaType.invoke(
            "application/atom+xml;type=entry;profile=opds-catalog"
        )!!
        public val OPDS2: MediaType = MediaType.invoke("application/opds+json")!!
        public val OPDS2_PUBLICATION: MediaType = MediaType.invoke("application/opds-publication+json")!!
        public val OPDS_AUTHENTICATION: MediaType = MediaType.invoke(
            "application/opds-authentication+json"
        )!!
        public val OPUS: MediaType = MediaType.invoke("audio/opus")!!
        public val OTF: MediaType = MediaType.invoke("font/otf")!!
        public val PDF: MediaType = MediaType.invoke("application/pdf")!!
        public val PNG: MediaType = MediaType.invoke("image/png")!!
        public val RAR: MediaType = MediaType.invoke("application/vnd.rar")!!
        public val READIUM_AUDIOBOOK: MediaType = MediaType.invoke("application/audiobook+zip")!!
        public val READIUM_AUDIOBOOK_MANIFEST: MediaType = MediaType.invoke("application/audiobook+json")!!
        public val READIUM_WEBPUB: MediaType = MediaType.invoke("application/webpub+zip")!!
        public val READIUM_WEBPUB_MANIFEST: MediaType = MediaType.invoke("application/webpub+json")!!
        public val SMIL: MediaType = MediaType.invoke("application/smil+xml")!!
        public val SVG: MediaType = MediaType.invoke("image/svg+xml")!!
        public val TEXT: MediaType = MediaType.invoke("text/plain")!!
        public val TIFF: MediaType = MediaType.invoke("image/tiff")!!
        public val TTF: MediaType = MediaType.invoke("font/ttf")!!
        public val W3C_WPUB_MANIFEST: MediaType =
            MediaType.invoke("application/x.readium.w3c.wpub+json")!! // non-existent
        public val WAV: MediaType = MediaType.invoke("audio/wav")!!
        public val WEBM_AUDIO: MediaType = MediaType.invoke("audio/webm")!!
        public val WEBM_VIDEO: MediaType = MediaType.invoke("video/webm")!!
        public val WEBP: MediaType = MediaType.invoke("image/webp")!!
        public val WOFF: MediaType = MediaType.invoke("font/woff")!!
        public val WOFF2: MediaType = MediaType.invoke("font/woff2")!!
        public val XHTML: MediaType = MediaType.invoke("application/xhtml+xml")!!
        public val XML: MediaType = MediaType.invoke("application/xml")!!
        public val ZAB: MediaType = MediaType.invoke("application/x.readium.zab+zip")!! // non-existent
        public val ZIP: MediaType = MediaType.invoke("application/zip")!!
    }
}
