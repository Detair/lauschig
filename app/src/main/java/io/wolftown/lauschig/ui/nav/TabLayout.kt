package io.wolftown.lauschig.ui.nav

import androidx.compose.runtime.Immutable
import io.wolftown.lauschig.ui.TopLevelTab

/**
 * Order, visibility and start screen of the top-level tabs (Einstellungen → Navigation anpassen). Every instance keeps
 * the rules: all four tabs in [order] exactly once, [TopLevelTab.Start] never hidden, at least [MIN_VISIBLE] tabs
 * visible, [start] one of the visible tabs. Changes that would break them are refused (the same layout comes back).
 */
@Immutable
class TabLayout private constructor(val order: List<TopLevelTab>, val hidden: Set<TopLevelTab>, val start: TopLevelTab) {
    /** The tabs the bottom bar / rail shows, in order. */
    val visible: List<TopLevelTab> get() = order.filter { it !in hidden }

    fun canHide(tab: TopLevelTab): Boolean = tab != TopLevelTab.Start && tab !in hidden && visible.size > MIN_VISIBLE

    /** Hiding the start screen makes [TopLevelTab.Start] the start screen again. */
    fun withHidden(tab: TopLevelTab, hide: Boolean): TabLayout = when {
        hide && !canHide(tab) -> this
        hide -> TabLayout(order, hidden + tab, if (start == tab) TopLevelTab.Start else start)
        else -> TabLayout(order, hidden - tab, start)
    }

    fun canMove(tab: TopLevelTab, by: Int): Boolean = order.indexOf(tab) + by in order.indices

    /** Swaps [tab] with the row [by] (−1 up, +1 down) places away in [order]; no-op at the edges. */
    fun moved(tab: TopLevelTab, by: Int): TabLayout {
        if (!canMove(tab, by)) return this
        val from = order.indexOf(tab)
        val list = order.toMutableList()
        list[from] = list[from + by].also { list[from + by] = tab }
        return TabLayout(list, hidden, start)
    }

    /** Only a visible tab can be the start screen. */
    fun withStart(tab: TopLevelTab): TabLayout = if (tab in hidden) this else TabLayout(order, hidden, tab)

    override fun equals(other: Any?): Boolean =
        other is TabLayout && order == other.order && hidden == other.hidden && start == other.start

    override fun hashCode(): Int = (order.hashCode() * 31 + hidden.hashCode()) * 31 + start.hashCode()

    override fun toString(): String = "TabLayout(order=$order, hidden=$hidden, start=$start)"

    companion object {
        const val MIN_VISIBLE = 2

        val DEFAULT = TabLayout(TopLevelTab.entries.toList(), emptySet(), TopLevelTab.Start)

        /** From stored names: unknown ones are dropped, missing tabs appended in the default order, then the rules applied. */
        fun of(order: List<String>?, hidden: Set<String>?, start: String?): TabLayout {
            fun tab(name: String) = TopLevelTab.entries.firstOrNull { it.name == name }
            val ordered = (order.orEmpty().mapNotNull(::tab) + TopLevelTab.entries).distinct()
            var layout = TabLayout(ordered, emptySet(), TopLevelTab.Start)
            // In display order, as long as the rules allow (a corrupt store cannot hide too many).
            ordered.filter { t -> hidden.orEmpty().any { it == t.name } }.forEach { layout = layout.withHidden(it, true) }
            return start?.let(::tab)?.let(layout::withStart) ?: layout
        }
    }
}

/** The navigation route of a tab. */
fun TopLevelTab.route(): Any = when (this) {
    TopLevelTab.Start -> Start
    TopLevelTab.Library -> Library
    TopLevelTab.Discover -> Discover
    TopLevelTab.Search -> Search
}
