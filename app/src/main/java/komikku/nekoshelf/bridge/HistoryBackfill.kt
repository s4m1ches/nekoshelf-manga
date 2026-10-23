package komikku.nekoshelf.bridge

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.data.DatabaseHandler
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * One-time import of Komikku's own reading history into the [ReadingLog] (seconds known, pages = 0).
 * Komikku keeps one history row per chapter: the accumulated `time_read` and the latest `last_read`,
 * so the whole chapter time is attributed to the local day of its last read.
 */
object HistoryBackfill {
    const val PREFS_NAME = "nekoshelf_bridge"
    const val FLAG_KEY = "nekoshelf_backfill_done"

    private val DAY_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    interface Flag {
        fun isDone(): Boolean
        fun markDone()
    }

    /**
     * [history] is (lastReadMs, timeReadMs) per history row. Seconds are summed per local day of lastRead.
     * Rows reset by Komikku (lastRead 0) or without time are skipped. Days from the first live-tracked day on
     * are skipped: the dwell tracker already counted them, importing would double-count. Days already
     * backfilled are skipped too, so a re-run after a crash cannot double them. Returns the days imported.
     */
    fun run(log: ReadingLog, history: List<Pair<Long, Long>>, zone: ZoneId): Int {
        val backfilled = log.backfilledDays()
        val firstLiveDay = log.daily().map { it.first }.filterNot { it in backfilled }.minOrNull()
        var imported = 0
        history
            .filter { (lastRead, timeRead) -> lastRead > 0 && timeRead > 0 }
            .groupBy({ (lastRead, _) -> DAY_FORMAT.format(Instant.ofEpochMilli(lastRead).atZone(zone)) }) { it.second }
            .filterKeys { day -> day !in backfilled && (firstLiveDay == null || day < firstLiveDay) }
            .toSortedMap()
            .forEach { (day, millis) ->
                val seconds = (millis.sum() / 1000L).toInt()
                if (seconds > 0) {
                    log.addBackfill(day, seconds)
                    imported++
                }
            }
        return imported
    }

    /**
     * Runs [run] unless [flag] says it already ran; returns whether it ran.
     * The flag stays unset unless at least one day was imported: a backup restored after first start brings
     * the history later. Re-running is safe, already backfilled days are skipped.
     */
    suspend fun runOnce(
        flag: Flag,
        log: ReadingLog,
        zone: ZoneId,
        readHistory: suspend () -> List<Pair<Long, Long>>,
    ): Boolean {
        if (flag.isDone()) return false
        val history = readHistory()
        if (history.isEmpty()) return false
        if (run(log, history, zone) == 0) return false
        flag.markDone()
        return true
    }

    /** Called once per process from `App.onCreate`; does the work on an IO coroutine. */
    fun start(context: Context, scope: CoroutineScope) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val flag = object : Flag {
            override fun isDone() = prefs.getBoolean(FLAG_KEY, false)
            override fun markDone() {
                prefs.edit().putBoolean(FLAG_KEY, true).commit()
            }
        }
        scope.launch(Dispatchers.IO) {
            try {
                runOnce(flag, Injekt.get<ReadingLog>(), ZoneId.systemDefault()) {
                    Injekt.get<DatabaseHandler>().awaitList {
                        historyQueries.nekoshelfAllReadTimes { lastRead, timeRead -> (lastRead?.time ?: 0L) to timeRead }
                    }
                }
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "NEKOSHELF: history backfill failed" }
            }
        }
    }
}
