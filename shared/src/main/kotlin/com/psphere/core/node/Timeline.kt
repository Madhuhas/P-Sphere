package com.psphere.core.node

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A run of photos under one date heading in the Photos timeline. */
data class TimelineSection(val key: String, val title: String, val items: List<LibraryItem>)

/**
 * Groups [items] (newest first) under headings: by day ("Today", "Yesterday", "Sat, 14 Nov",
 * "Fri, 3 Jan 2025"), or by month ("November", "March 2025") when [byMonth] is set.
 */
fun timelineSections(
    items: List<LibraryItem>,
    byMonth: Boolean = false,
    now: Long = System.currentTimeMillis(),
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): List<TimelineSection> {
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val day = DateTimeFormatter.ofPattern("EEE, d MMM", locale)
    val dayYear = DateTimeFormatter.ofPattern("EEE, d MMM yyyy", locale)
    val month = DateTimeFormatter.ofPattern("LLLL", locale)
    val monthYear = DateTimeFormatter.ofPattern("LLLL yyyy", locale)
    val out = ArrayList<TimelineSection>()
    var key: String? = null
    var title = ""
    var run = ArrayList<LibraryItem>()
    for (li in items) {
        val d = Instant.ofEpochMilli(li.item.takenAt).atZone(zone).toLocalDate()
        val k = if (byMonth) "${d.year}-${d.monthValue}" else d.toString()
        if (k != key) {
            if (key != null) out += TimelineSection(key, title, run)
            key = k
            run = ArrayList()
            title = when {
                byMonth -> (if (d.year == today.year) month else monthYear).format(d).replaceFirstChar { it.titlecase(locale) }
                d == today -> "Today"
                d == today.minusDays(1) -> "Yesterday"
                d.year == today.year -> day.format(d)
                else -> dayYear.format(d)
            }
        }
        run += li
    }
    if (key != null) out += TimelineSection(key, title, run)
    return out
}
