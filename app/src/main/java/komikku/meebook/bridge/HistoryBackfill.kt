package komikku.meebook.bridge

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
    const val PREFS_NAME = "meebook_bridge"
    const val FLAG_KEY = "meebook_backfill_done"

    private val DAY_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    interface Flag {
        fun isDone(): Boolean
        fun markDone()
    }

    /**
     * [history] is (lastReadMs, timeReadMs) per history row. Seconds are summed per local day of lastRead.
     * Rows reset by Komikku (lastRead 0) or without time are skipped. Days from the first live-tracked day on
     * are skipped: the dwell tracker already counted them, importing would double-count.
     */
    fun run(log: ReadingLog, history: List<Pair<Long, Long>>, zone: ZoneId) {
        val backfilled = log.backfilledDays()
        val firstLiveDay = log.daily().map { it.first }.filterNot { it in backfilled }.minOrNull()
        history
            .filter { (lastRead, timeRead) -> lastRead > 0 && timeRead > 0 }
            .groupBy({ (lastRead, _) -> DAY_FORMAT.format(Instant.ofEpochMilli(lastRead).atZone(zone)) }) { it.second }
            .filterKeys { day -> firstLiveDay == null || day < firstLiveDay }
            .toSortedMap()
            .forEach { (day, millis) ->
                val seconds = (millis.sum() / 1000L).toInt()
                if (seconds > 0) log.addBackfill(day, seconds)
            }
    }

    /**
     * Runs [run] unless [flag] says it already ran; returns whether it ran.
     * An empty history leaves the flag unset: a backup restored after first start brings the history later.
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
        run(log, history, zone)
        flag.markDone()
        return true
    }

    /** Called once per process from `App.onCreate`; does the work on an IO coroutine. */
    fun start(context: Context, scope: CoroutineScope) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val flag = object : Flag {
            override fun isDone() = prefs.getBoolean(FLAG_KEY, false)
            override fun markDone() = prefs.edit().putBoolean(FLAG_KEY, true).apply()
        }
        scope.launch(Dispatchers.IO) {
            try {
                runOnce(flag, Injekt.get<ReadingLog>(), ZoneId.systemDefault()) {
                    Injekt.get<DatabaseHandler>().awaitList {
                        historyQueries.meebookAllReadTimes { lastRead, timeRead -> (lastRead?.time ?: 0L) to timeRead }
                    }
                }
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "MEEBOOK: history backfill failed" }
            }
        }
    }
}
