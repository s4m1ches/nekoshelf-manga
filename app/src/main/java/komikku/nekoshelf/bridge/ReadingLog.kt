package komikku.nekoshelf.bridge

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** One row of the `reading_log` table. */
data class ReadingLogRow(val day: String, val pages: Int, val seconds: Int, val backfilled: Boolean)

/**
 * Storage seam for [ReadingLog] (lets tests use an in-memory fake; Komikku has no Robolectric).
 * Upsert semantics: both writes create the row if missing and accumulate otherwise.
 */
interface ReadingLogStore {
    /** pages += [pages], seconds += [seconds], backfilled = 0. */
    fun addDay(day: String, pages: Int, seconds: Int)

    /** seconds += [seconds], backfilled = 1; pages untouched (0 for a new row). */
    fun addBackfill(day: String, seconds: Int)

    /** All rows ordered by day ascending. */
    fun all(): List<ReadingLogRow>
}

class InMemoryReadingLogStore : ReadingLogStore {
    private val rows = sortedMapOf<String, ReadingLogRow>()

    @Synchronized
    override fun addDay(day: String, pages: Int, seconds: Int) {
        val old = rows[day] ?: ReadingLogRow(day, 0, 0, false)
        rows[day] = ReadingLogRow(day, old.pages + pages, old.seconds + seconds, false)
    }

    @Synchronized
    override fun addBackfill(day: String, seconds: Int) {
        val old = rows[day] ?: ReadingLogRow(day, 0, 0, false)
        rows[day] = ReadingLogRow(day, old.pages, old.seconds + seconds, true)
    }

    @Synchronized
    override fun all(): List<ReadingLogRow> = rows.values.toList()
}

/** `nekoshelf_bridge.db`, table `reading_log(day PK, pages, seconds, backfilled DEFAULT 0)`. */
class SqliteReadingLogStore(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION),
    ReadingLogStore {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE reading_log(day TEXT PRIMARY KEY, pages INTEGER, seconds INTEGER, " +
                "backfilled INTEGER DEFAULT 0)",
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    override fun addDay(day: String, pages: Int, seconds: Int) = upsert(day, pages, seconds, 0)

    override fun addBackfill(day: String, seconds: Int) = upsert(day, 0, seconds, 1)

    // Portable upsert (ON CONFLICT DO UPDATE needs SQLite 3.24, Android 8 ships 3.18).
    private fun upsert(day: String, pages: Int, seconds: Int, backfilled: Int) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL(
                "INSERT OR IGNORE INTO reading_log(day, pages, seconds, backfilled) VALUES(?, 0, 0, 0)",
                arrayOf<Any>(day),
            )
            db.execSQL(
                "UPDATE reading_log SET pages = pages + ?, seconds = seconds + ?, backfilled = ? WHERE day = ?",
                arrayOf<Any>(pages, seconds, backfilled, day),
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override fun all(): List<ReadingLogRow> = readableDatabase.rawQuery(
        "SELECT day, pages, seconds, backfilled FROM reading_log ORDER BY day",
        null,
    ).use { c ->
        buildList {
            while (c.moveToNext()) {
                add(ReadingLogRow(c.getString(0), c.getInt(1), c.getInt(2), c.getInt(3) != 0))
            }
        }
    }

    companion object {
        const val DB_NAME = "nekoshelf_bridge.db"
        private const val DB_VERSION = 1
    }
}

class ReadingLog(private val store: ReadingLogStore) {

    constructor(context: Context) : this(SqliteReadingLogStore(context))

    fun add(r: DwellRecord) = store.addDay(r.day, 1, r.seconds)

    /** (day, pages, seconds) ordered by day. */
    fun daily(): List<Triple<String, Int, Int>> = store.all().map { Triple(it.day, it.pages, it.seconds) }

    fun backfilledDays(): Set<String> = store.all().filter { it.backfilled }.map { it.day }.toSet()

    fun addBackfill(day: String, seconds: Int) = store.addBackfill(day, seconds)

    /** (total pages, total seconds). */
    fun totals(): Pair<Long, Long> =
        store.all().let { rows -> rows.sumOf { it.pages.toLong() } to rows.sumOf { it.seconds.toLong() } }
}
