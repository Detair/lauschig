package io.wolftown.lauschig.links

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Test

class AbsLinksTest {
    private val public = "https://audiobookshelf.wolftown.io/".toHttpUrl()
    private val local = "http://192.168.1.5:13378/".toHttpUrl()
    private val prefixed = "https://example.org/audiobookshelf".toHttpUrl()
    private val addresses = listOf(local, public)
    private val id = "0a356b43-3216-4e8e-bd28-35200531033e"

    private fun item(url: String, a: List<okhttp3.HttpUrl> = addresses) = AbsLinks.parse(url, a)

    @Test fun item_plain_trailingSlash_queryAndFragment() {
        val ok = LinkMatch.Ok(AbsLink.Item(id), public)
        assertEquals(ok, item("https://audiobookshelf.wolftown.io/item/$id"))
        assertEquals(ok, item("https://audiobookshelf.wolftown.io/item/$id/"))
        assertEquals(ok, item("https://audiobookshelf.wolftown.io/item/$id?tab=chapters#top"))
        assertEquals(ok, item("  https://audiobookshelf.wolftown.io/item/$id  "))
    }

    @Test fun item_underAPathPrefix() {
        assertEquals(LinkMatch.Ok(AbsLink.Item(id), prefixed), item("https://example.org/audiobookshelf/item/$id", listOf(prefixed)))
        // The web app under a prefix although the address is the bare host.
        assertEquals(LinkMatch.Ok(AbsLink.Item(id), public), item("https://audiobookshelf.wolftown.io/audiobookshelf/item/$id"))
    }

    @Test fun host_isCaseInsensitive_portsMustMatch() {
        assertEquals(LinkMatch.Ok(AbsLink.Item(id), public), item("HTTPS://AudioBookShelf.Wolftown.IO/item/$id"))
        assertEquals(LinkMatch.Ok(AbsLink.Item(id), public), item("https://audiobookshelf.wolftown.io:443/item/$id"))
        assertEquals(LinkMatch.Ok(AbsLink.Item(id), local), item("http://192.168.1.5:13378/item/$id"))
        assertEquals(LinkMatch.ForeignServer, item("http://192.168.1.5:8080/item/$id"))
        assertEquals(LinkMatch.ForeignServer, item("http://192.168.1.5/item/$id"))
    }

    @Test fun foreignHostOrScheme_isNotOurs() {
        assertEquals(LinkMatch.ForeignServer, item("https://evil.example/item/$id"))
        assertEquals(LinkMatch.ForeignServer, item("https://audiobookshelf.wolftown.io.evil.example/item/$id"))
        assertEquals(LinkMatch.ForeignServer, item("http://audiobookshelf.wolftown.io/item/$id"))
        assertEquals(LinkMatch.ForeignServer, item("https://audiobookshelf.wolftown.io/item/$id", emptyList()))
    }

    @Test fun otherPages_andMalformedIds_areUnsupported() {
        assertEquals(LinkMatch.Unsupported, item("https://audiobookshelf.wolftown.io/"))
        assertEquals(LinkMatch.Unsupported, item("https://audiobookshelf.wolftown.io/config/users"))
        assertEquals(LinkMatch.Unsupported, item("https://audiobookshelf.wolftown.io/item/"))
        assertEquals(LinkMatch.Unsupported, item("https://audiobookshelf.wolftown.io/item/a%2Fb"))
        assertEquals(LinkMatch.Unsupported, item("https://audiobookshelf.wolftown.io/item/$id/edit"))
        assertEquals(LinkMatch.Unsupported, item("not a url"))
    }

    @Test fun series() {
        assertEquals(
            LinkMatch.Ok(AbsLink.Series("lib1", "ser-9"), public),
            item("https://audiobookshelf.wolftown.io/library/lib1/series/ser-9?x=1"),
        )
        assertEquals(LinkMatch.Ok(AbsLink.Series("lib1", "ser-9"), prefixed), item("https://example.org/audiobookshelf/library/lib1/series/ser-9/", listOf(prefixed)))
        assertEquals(LinkMatch.Unsupported, item("https://audiobookshelf.wolftown.io/library/lib1/series"))
    }

    @Test fun firstUrl_inSharedText() {
        assertEquals("https://a.example/item/x", AbsLinks.firstUrl("Hör mal: https://a.example/item/x!"))
        assertEquals("https://a.example/item/x", AbsLinks.firstUrl("(https://a.example/item/x)"))
        assertEquals(null, AbsLinks.firstUrl("kein Link"))
    }
}
