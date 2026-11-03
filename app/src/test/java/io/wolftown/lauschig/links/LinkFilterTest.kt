package io.wolftown.lauschig.links

import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The generated VIEW filter (app/build.gradle.kts, default host audiobookshelf.wolftown.io) as the system resolves it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class LinkFilterTest {
    private val pm = ApplicationProvider.getApplicationContext<android.content.Context>().packageManager

    private fun opensLauschig(url: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
        return pm.queryIntentActivities(intent, 0).any { it.activityInfo.name == "io.wolftown.lauschig.MainActivity" }
    }

    @Test fun itemLinks_withPathPrefixesOfUpToFourSegments() {
        listOf("", "/abs", "/a/b", "/a/b/c", "/a/b/c/d").forEach { prefix ->
            assertEquals(prefix, true, opensLauschig("https://audiobookshelf.wolftown.io$prefix/item/0a356b43-3216"))
        }
    }

    @Test fun seriesLinks_withPathPrefixes() {
        listOf("", "/abs", "/a/b", "/a/b/c", "/a/b/c/d").forEach { prefix ->
            assertEquals(prefix, true, opensLauschig("https://audiobookshelf.wolftown.io$prefix/library/lib1/series/ser1"))
        }
    }

    @Test fun otherPages_andOtherHosts_doNot() {
        assertEquals(false, opensLauschig("https://audiobookshelf.wolftown.io/config/users"))
        assertEquals(false, opensLauschig("https://example.org/item/0a356b43-3216"))
    }
}
