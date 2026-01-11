package org.readium.r2.shared.util.mediatype

import kotlin.test.*
import org.junit.Test

class MediaTypeTest {

    @Test
    fun `returns null for invalid types`() {
        assertNull(MediaType.invoke("application"))
        assertNull(MediaType.invoke("application/atom+xml/extra"))
    }

    @Test
    fun `to string`() {
        assertEquals(
            "application/atom+xml;profile=opds-catalog",
            MediaType.invoke("application/atom+xml;profile=opds-catalog")?.toString()
        )
    }

    @Test
    fun `to string is normalized`() {
        assertEquals(
            "application/atom+xml;a=0;profile=OPDS-CATALOG",
            MediaType.invoke("APPLICATION/ATOM+XML;PROFILE=OPDS-CATALOG   ;   a=0")?.toString()
        )
        // Parameters are sorted by name
        assertEquals(
            "application/atom+xml;a=0;b=1",
            MediaType.invoke("application/atom+xml;a=0;b=1")?.toString()
        )
        assertEquals(
            "application/atom+xml;a=0;b=1",
            MediaType.invoke("application/atom+xml;b=1;a=0")?.toString()
        )
    }

    @Test
    fun `get type`() {
        assertEquals(
            "application",
            MediaType.invoke("application/atom+xml;profile=opds-catalog")?.type
        )
        assertEquals("*", MediaType.invoke("*/jpeg")?.type)
    }

    @Test
    fun `get subtype`() {
        assertEquals(
            "atom+xml",
            MediaType.invoke("application/atom+xml;profile=opds-catalog")?.subtype
        )
        assertEquals("*", MediaType.invoke("image/*")?.subtype)
    }

    @Test
    fun `get parameters`() {
        assertEquals(
            mapOf(
                "type" to "entry",
                "profile" to "opds-catalog"
            ),
            MediaType.invoke("application/atom+xml;type=entry;profile=opds-catalog")?.parameters
        )
    }

    @Test
    fun `get empty parameters`() {
        assertTrue(MediaType.invoke("application/atom+xml")!!.parameters.isEmpty())
    }

    @Test
    fun `get parameters with whitespaces`() {
        assertEquals(
            mapOf(
                "type" to "entry",
                "profile" to "opds-catalog"
            ),
            MediaType.invoke(
                "application/atom+xml    ;    type=entry   ;    profile=opds-catalog   "
            )?.parameters
        )
    }

    @Test
    fun `get structured syntax suffix`() {
        assertNull(MediaType.invoke("foo/bar")?.structuredSyntaxSuffix)
        assertNull(MediaType.invoke("application/zip")?.structuredSyntaxSuffix)
        assertEquals("+zip", MediaType.invoke("application/epub+zip")?.structuredSyntaxSuffix)
        assertEquals("+zip", MediaType.invoke("foo/bar+json+zip")?.structuredSyntaxSuffix)
    }

    @Test
    fun `get charset`() {
        assertNull(MediaType.invoke("text/html")?.charset)
        assertEquals(Charsets.UTF_8, MediaType.invoke("text/html;charset=utf-8")?.charset)
        assertEquals(Charsets.UTF_16, MediaType.invoke("text/html;charset=utf-16")?.charset)
    }

    @Test
    fun `type, subtype and parameter names are lowercased`() {
        val mediaType = MediaType.invoke("APPLICATION/ATOM+XML;PROFILE=OPDS-CATALOG")
        assertEquals("application", mediaType?.type)
        assertEquals("atom+xml", mediaType?.subtype)
        assertEquals(mapOf("profile" to "OPDS-CATALOG"), mediaType?.parameters)
    }

    @Test
    fun `charset value is uppercased`() {
        assertEquals(
            "UTF-8",
            MediaType.invoke("text/html;charset=utf-8")?.parameters?.get("charset")
        )
    }

    @Test
    fun `charset value is canonicalized`() {
        assertEquals(
            "US-ASCII",
            MediaType.invoke("text/html;charset=ascii")?.parameters?.get("charset")
        )
        assertEquals(
            "UNKNOWN",
            MediaType.invoke("text/html;charset=unknown")?.parameters?.get("charset")
        )
    }

    @Test
    fun equality() {
        assertEquals(
            MediaType.invoke("application/atom+xml")!!,
            MediaType.invoke("application/atom+xml")!!
        )
        assertEquals(
            MediaType.invoke("application/atom+xml;profile=opds-catalog")!!,
            MediaType.invoke("application/atom+xml;profile=opds-catalog")!!
        )
        assertNotEquals(
            MediaType.invoke("application/atom+xml")!!,
            MediaType.invoke("application/atom")!!
        )
        assertNotEquals(
            MediaType.invoke("application/atom+xml")!!,
            MediaType.invoke("text/atom+xml")!!
        )
        assertNotEquals(
            MediaType.invoke("application/atom+xml;profile=opds-catalog")!!,
            MediaType.invoke("application/atom+xml")!!
        )
    }

    @Test
    fun `equality ignores case of type, subtype and parameter names`() {
        assertEquals(
            MediaType.invoke("application/atom+xml;profile=opds-catalog")!!,
            MediaType.invoke("APPLICATION/ATOM+XML;PROFILE=opds-catalog")!!
        )
        assertNotEquals(
            MediaType.invoke("application/atom+xml;profile=opds-catalog")!!,
            MediaType.invoke("APPLICATION/ATOM+XML;PROFILE=OPDS-CATALOG")!!
        )
    }

    @Test
    fun `equality ignores parameters order`() {
        assertEquals(
            MediaType.invoke("application/atom+xml;type=entry;profile=opds-catalog")!!,
            MediaType.invoke("application/atom+xml;profile=opds-catalog;type=entry")!!
        )
    }

    @Test
    fun `equality ignores charset case`() {
        assertEquals(
            MediaType.invoke("application/atom+xml;charset=utf-8")!!,
            MediaType.invoke("application/atom+xml;charset=UTF-8")!!
        )
    }

    @Test
    fun `contains equal media type`() {
        assertTrue(
            MediaType.invoke("text/html;charset=utf-8")!!.contains(
                MediaType.invoke("text/html;charset=utf-8")
            )
        )
    }

    @Test
    fun `contains must match parameters`() {
        assertFalse(
            MediaType.invoke("text/html;charset=utf-8")!!.contains(
                MediaType.invoke("text/html;charset=ascii")
            )
        )
        assertFalse(
            MediaType.invoke("text/html;charset=utf-8")!!.contains(MediaType.invoke("text/html"))
        )
    }

    @Test
    fun `contains ignores parameters order`() {
        assertTrue(
            MediaType.invoke("text/html;charset=utf-8;type=entry")!!.contains(
                MediaType.invoke("text/html;type=entry;charset=utf-8")
            )
        )
    }

    @Test
    fun `contains ignore extra parameters`() {
        assertTrue(
            MediaType.invoke("text/html")!!.contains(MediaType.invoke("text/html;charset=utf-8"))
        )
    }

    @Test
    fun `contains supports wildcards`() {
        assertTrue(
            MediaType.invoke("*/*")!!.contains(MediaType.invoke("text/html;charset=utf-8"))
        )
        assertTrue(
            MediaType.invoke("text/*")!!.contains(MediaType.invoke("text/html;charset=utf-8"))
        )
        assertFalse(
            MediaType.invoke("text/*")!!.contains(MediaType.invoke("application/zip"))
        )
    }

    @Test
    fun `contains from string`() {
        assertTrue(
            MediaType.invoke("text/html;charset=utf-8")!!.contains("text/html;charset=utf-8")
        )
    }

    @Test
    fun `matches equal media type`() {
        assertTrue(
            MediaType.invoke("text/html;charset=utf-8")!!.matches(
                MediaType.invoke("text/html;charset=utf-8")
            )
        )
    }

    @Test
    fun `matches must match parameters`() {
        assertFalse(
            MediaType.invoke("text/html;charset=ascii")!!.matches(
                MediaType.invoke("text/html;charset=utf-8")
            )
        )
    }

    @Test
    fun `matches ignores parameters order`() {
        assertTrue(
            MediaType.invoke("text/html;charset=utf-8;type=entry")!!.matches(
                MediaType.invoke("text/html;type=entry;charset=utf-8")
            )
        )
    }

    @Test
    fun `matches ignores extra parameters`() {
        assertTrue(
            MediaType.invoke("text/html;charset=utf-8")!!.matches(
                MediaType.invoke("text/html;charset=utf-8;extra=param")
            )
        )
        assertTrue(
            MediaType.invoke("text/html;charset=utf-8;extra=param")!!.matches(
                MediaType.invoke("text/html;charset=utf-8")
            )
        )
    }

    @Test
    fun `matches supports wildcards`() {
        assertTrue(MediaType.invoke("text/html;charset=utf-8")!!.matches(MediaType.invoke("*/*")))
        assertTrue(MediaType.invoke("text/html;charset=utf-8")!!.matches(MediaType.invoke("text/*")))
        assertFalse(MediaType.invoke("application/zip")!!.matches(MediaType.invoke("text/*")))
        assertTrue(MediaType.invoke("*/*")!!.matches(MediaType.invoke("text/html;charset=utf-8")))
        assertTrue(MediaType.invoke("text/*")!!.matches(MediaType.invoke("text/html;charset=utf-8")))
        assertFalse(MediaType.invoke("text/*")!!.matches(MediaType.invoke("application/zip")))
    }

    @Test
    fun `matches from string`() {
        assertTrue(
            MediaType.invoke("text/html;charset=utf-8")!!.matches("text/html;charset=utf-8")
        )
    }

    @Test
    fun `matches any media type`() {
        assertTrue(
            MediaType.invoke("text/html")!!.matchesAny(
                MediaType.invoke("application/zip")!!,
                MediaType.invoke("text/html;charset=utf-8")!!
            )
        )
        assertFalse(
            MediaType.invoke("text/html")!!.matchesAny(
                MediaType.invoke("application/zip")!!,
                MediaType.invoke("text/plain;charset=utf-8")!!
            )
        )
        assertTrue(
            MediaType.invoke("text/html")!!.matchesAny("application/zip", "text/html;charset=utf-8")
        )
        assertFalse(
            MediaType.invoke("text/html")!!.matchesAny("application/zip", "text/plain;charset=utf-8")
        )
    }

    @Test
    fun `is ZIP`() {
        assertFalse(MediaType.invoke("text/plain")!!.isZip)
        assertTrue(MediaType.invoke("application/zip")!!.isZip)
        assertTrue(MediaType.invoke("application/zip;charset=utf-8")!!.isZip)
        assertTrue(MediaType.invoke("application/epub+zip")!!.isZip)
        // These media types must be explicitly matched since they don't have any ZIP hint
        assertTrue(MediaType.invoke("application/audiobook+lcp")!!.isZip)
        assertTrue(MediaType.invoke("application/pdf+lcp")!!.isZip)
    }

    @Test
    fun `is JSON`() {
        assertFalse(MediaType.invoke("text/plain")!!.isJson)
        assertTrue(MediaType.invoke("application/json")!!.isJson)
        assertTrue(MediaType.invoke("application/json;charset=utf-8")!!.isJson)
        assertTrue(MediaType.invoke("application/opds+json")!!.isJson)
    }

    @Test
    fun `is OPDS`() {
        assertFalse(MediaType.invoke("text/html")!!.isOpds)
        assertTrue(MediaType.invoke("application/atom+xml;profile=opds-catalog")!!.isOpds)
        assertTrue(MediaType.invoke("application/atom+xml;type=entry;profile=opds-catalog")!!.isOpds)
        assertTrue(MediaType.invoke("application/opds+json")!!.isOpds)
        assertTrue(MediaType.invoke("application/opds-publication+json")!!.isOpds)
        assertTrue(MediaType.invoke("application/opds+json;charset=utf-8")!!.isOpds)
        assertTrue(MediaType.invoke("application/opds-authentication+json")!!.isOpds)
    }

    @Test
    fun `is HTML`() {
        assertFalse(MediaType.invoke("application/opds+json")!!.isHtml)
        assertTrue(MediaType.invoke("text/html")!!.isHtml)
        assertTrue(MediaType.invoke("application/xhtml+xml")!!.isHtml)
        assertTrue(MediaType.invoke("text/html;charset=utf-8")!!.isHtml)
    }

    @Test
    fun `is bitmap`() {
        assertFalse(MediaType.invoke("text/html")!!.isBitmap)
        assertTrue(MediaType.invoke("image/bmp")!!.isBitmap)
        assertTrue(MediaType.invoke("image/gif")!!.isBitmap)
        assertTrue(MediaType.invoke("image/jpeg")!!.isBitmap)
        assertTrue(MediaType.invoke("image/png")!!.isBitmap)
        assertTrue(MediaType.invoke("image/tiff")!!.isBitmap)
        assertTrue(MediaType.invoke("image/tiff")!!.isBitmap)
        assertTrue(MediaType.invoke("image/tiff;charset=utf-8")!!.isBitmap)
    }

    @Test
    fun `is audio`() {
        assertFalse(MediaType.invoke("text/html")!!.isAudio)
        assertTrue(MediaType.invoke("audio/unknown")!!.isAudio)
        assertTrue(MediaType.invoke("audio/mpeg;param=value")!!.isAudio)
    }

    @Test
    fun `is video`() {
        assertFalse(MediaType.invoke("text/html")!!.isVideo)
        assertTrue(MediaType.invoke("video/unknown")!!.isVideo)
        assertTrue(MediaType.invoke("video/mpeg;param=value")!!.isVideo)
    }

    @Test
    fun `is RWPM`() {
        assertFalse(MediaType.invoke("text/html")!!.isRwpm)
        assertTrue(MediaType.invoke("application/audiobook+json")!!.isRwpm)
        assertTrue(MediaType.invoke("application/divina+json")!!.isRwpm)
        assertTrue(MediaType.invoke("application/webpub+json")!!.isRwpm)
        assertTrue(MediaType.invoke("application/webpub+json;charset=utf-8")!!.isRwpm)
    }

    @Test
    fun `is publication`() {
        assertFalse(MediaType.invoke("text/html")!!.isPublication)
        assertTrue(MediaType.invoke("application/audiobook+zip")!!.isPublication)
        assertTrue(MediaType.invoke("application/audiobook+json")!!.isPublication)
        assertTrue(MediaType.invoke("application/audiobook+lcp")!!.isPublication)
        assertTrue(MediaType.invoke("application/audiobook+json;charset=utf-8")!!.isPublication)
        assertTrue(MediaType.invoke("application/divina+zip")!!.isPublication)
        assertTrue(MediaType.invoke("application/divina+json")!!.isPublication)
        assertTrue(MediaType.invoke("application/webpub+zip")!!.isPublication)
        assertTrue(MediaType.invoke("application/webpub+json")!!.isPublication)
        assertTrue(MediaType.invoke("application/vnd.comicbook+zip")!!.isPublication)
        assertTrue(MediaType.invoke("application/epub+zip")!!.isPublication)
        assertTrue(MediaType.invoke("application/lpf+zip")!!.isPublication)
        assertTrue(MediaType.invoke("application/pdf")!!.isPublication)
        assertTrue(MediaType.invoke("application/pdf+lcp")!!.isPublication)
        assertTrue(MediaType.invoke("application/x.readium.w3c.wpub+json")!!.isPublication)
        assertTrue(MediaType.invoke("application/x.readium.zab+zip")!!.isPublication)
    }
}
