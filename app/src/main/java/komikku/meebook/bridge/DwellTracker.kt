package komikku.meebook.bridge

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** A page that stayed on screen long enough to count. [day] is `yyyy-MM-dd` of the LEAVE time. */
data class DwellRecord(val day: String, val seconds: Int)

/**
 * Tracks how long the current reader page has been on screen.
 * A page counts if shown >= [MIN_SECONDS]; its time is capped at [MAX_SECONDS].
 * Thread-safe (reader callbacks and onPause may come from different threads).
 */
class DwellTracker(
    private val clock: () -> Long,
    private val zone: () -> ZoneId,
) {
    private var currentKey: String? = null
    private var shownAt: Long = 0L

    /** Closes the previous page (returning its record, or null if < 5 s) and opens [key]. */
    @Synchronized
    fun onPageShown(key: String): DwellRecord? {
        val now = clock()
        val record = close(now)
        currentKey = key
        shownAt = now
        return record
    }

    /**
     * Page-change entry point for the reader. In incognito nothing may be recorded: the open page (if any)
     * is closed and dropped, and no new page is opened.
     */
    @Synchronized
    fun onPageShown(key: String, incognito: Boolean): DwellRecord? {
        if (incognito) {
            onPause()
            return null
        }
        return onPageShown(key)
    }

    /** Closes the current page without opening another. A second call returns null. */
    @Synchronized
    fun onPause(): DwellRecord? = close(clock())

    /**
     * Re-opens tracking of the page [key] after a pause, with a fresh start time; emits nothing.
     * Idempotent: if a page is already open (no pause happened) the start time is kept.
     */
    @Synchronized
    fun onResume(key: String, incognito: Boolean = false) {
        if (incognito || currentKey != null) return
        currentKey = key
        shownAt = clock()
    }

    private fun close(now: Long): DwellRecord? {
        if (currentKey == null) return null
        currentKey = null
        val elapsed = ((now - shownAt) / 1000L).coerceAtLeast(0L)
        if (elapsed < MIN_SECONDS) return null
        val day = DAY_FORMAT.format(Instant.ofEpochMilli(now).atZone(zone()))
        return DwellRecord(day, minOf(elapsed, MAX_SECONDS.toLong()).toInt())
    }

    companion object {
        const val MIN_SECONDS = 5
        const val MAX_SECONDS = 120
        private val DAY_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    }
}
