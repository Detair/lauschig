package io.wolftown.lauschig.ui.nav

import io.wolftown.lauschig.ui.TopLevelTab.Discover
import io.wolftown.lauschig.ui.TopLevelTab.Library
import io.wolftown.lauschig.ui.TopLevelTab.Search
import io.wolftown.lauschig.ui.TopLevelTab.Start
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TabLayoutTest {
    private val default = TabLayout.DEFAULT

    @Test fun default_isTheAppBefore() {
        assertEquals(listOf(Start, Library, Discover, Search), default.visible)
        assertEquals(Start, default.start)
    }

    @Test fun stored_isNormalised() {
        // Unknown names dropped, duplicates removed, missing tabs appended in the default order.
        val l = TabLayout.of(listOf("Search", "Bogus", "Search", "Start"), setOf("Discover", "Start", "Nope"), "Library")
        assertEquals(listOf(Search, Start, Library, Discover), l.order)
        assertEquals(setOf(Discover), l.hidden) // Start can never be hidden
        assertEquals(Library, l.start)
        assertEquals(default, TabLayout.of(null, null, null))
    }

    @Test fun stored_tooManyHidden_keepsTwoVisible() {
        val l = TabLayout.of(listOf("Start", "Library", "Discover", "Search"), setOf("Library", "Discover", "Search"), null)
        assertEquals(TabLayout.MIN_VISIBLE, l.visible.size)
        assertEquals(Start, l.visible.first())
    }

    @Test fun stored_hiddenStart_fallsBackToStart() {
        assertEquals(Start, TabLayout.of(null, setOf("Search"), "Search").start)
        assertEquals(Start, TabLayout.of(null, null, "Bogus").start)
    }

    @Test fun hide_atLeastTwoVisible_neverStart() {
        assertFalse(default.canHide(Start))
        val one = default.withHidden(Discover, true)
        val two = one.withHidden(Search, true)
        assertEquals(listOf(Start, Library), two.visible)
        assertFalse(two.canHide(Library))
        assertEquals(two, two.withHidden(Library, true)) // refused
        assertEquals(two, two.withHidden(Start, true)) // refused
        assertEquals(listOf(Start, Library, Search), two.withHidden(Search, false).visible)
    }

    @Test fun hidingTheStartScreen_fallsBackToStart() {
        val l = default.withStart(Library).withHidden(Library, true)
        assertEquals(Start, l.start)
    }

    @Test fun startScreen_onlyVisibleTabs() {
        val l = default.withHidden(Search, true)
        assertEquals(l, l.withStart(Search))
        assertEquals(Discover, l.withStart(Discover).start)
    }

    @Test fun move_upAndDown_noOpAtTheEdges() {
        assertEquals(listOf(Library, Start, Discover, Search), default.moved(Library, -1).order)
        assertEquals(listOf(Start, Library, Search, Discover), default.moved(Discover, +1).order)
        assertEquals(default, default.moved(Start, -1))
        assertEquals(default, default.moved(Search, +1))
        assertTrue(default.canMove(Library, -1))
        assertFalse(default.canMove(Start, -1))
        assertFalse(default.canMove(Search, +1))
    }

    @Test fun move_swapsWithTheNeighbourRow_hiddenOnesToo() {
        // The settings list shows every tab (hidden ones switched off); up/down swaps with the row above/below.
        val l = default.withHidden(Discover, true).moved(Search, -1)
        assertEquals(listOf(Start, Library, Search, Discover), l.order)
        assertEquals(listOf(Start, Library, Search), l.visible)
        assertEquals(listOf(Start, Library, Search, Discover), l.moved(Discover, +1).order)
    }

    @Test fun routes_matchTheTabs() {
        assertEquals(io.wolftown.lauschig.ui.nav.Start, Start.route())
        assertEquals(io.wolftown.lauschig.ui.nav.Library, Library.route())
        assertEquals(io.wolftown.lauschig.ui.nav.Discover, Discover.route())
        assertEquals(io.wolftown.lauschig.ui.nav.Search, Search.route())
    }
}
