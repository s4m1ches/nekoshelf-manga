package komikku.meebook.bridge

import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.library.model.LibraryManga
import java.io.File

/** `library` rows of the bridge contract (`docs/superpowers/specs/2026-10-23-manga-bridge-contract.md`). */
object LibraryRows {
    val COLUMNS = arrayOf(
        "manga_id",
        "title",
        "source_name",
        "date_added",
        "last_read_at",
        "chapters_total",
        "chapters_read",
        "next_chapter_id",
        "next_chapter_name",
        "cover_version",
        "chapters_new",
    )

    /**
     * Values in [COLUMNS] order. `title` is [tachiyomi.domain.manga.model.Manga.title], which in Komikku
     * already resolves the user's custom title for favourites (the accessor the library grid/list uses).
     */
    fun row(
        lm: LibraryManga,
        next: Chapter?,
        sourceName: String,
        coverVersion: Long,
        chaptersNew: Int,
    ): Array<Any?> = arrayOf(
        lm.manga.id,
        lm.manga.title,
        sourceName,
        lm.manga.dateAdded,
        lm.lastRead,
        lm.totalChapters.toInt(),
        lm.readCount.toInt(),
        next?.id,
        next?.name,
        coverVersion,
        chaptersNew,
    )

    /** `chapters_new`: unread chapters Komikku found (`dateFetch`) after the last read; 0 for a never-read manga. */
    fun newChapters(chapters: List<Chapter>, lastRead: Long): Int =
        if (lastRead <= 0L) 0 else chapters.count { !it.read && it.dateFetch > lastRead }

    /** One entry per manga id (Komikku merged entries must never yield duplicate rows), first occurrence kept. */
    fun distinct(library: List<LibraryManga>): List<LibraryManga> = library.distinctBy { it.manga.id }
}

/** `stats_daily` and `totals` rows of the bridge contract. */
object StatsRows {
    val DAILY_COLUMNS = arrayOf("day", "pages", "seconds", "backfilled")
    val TOTALS_COLUMNS = arrayOf("pages_total", "seconds_total")

    fun daily(log: ReadingLog): List<Array<Any?>> {
        val backfilled = log.backfilledDays()
        return log.daily().map { (day, pages, seconds) ->
            arrayOf(day, pages, seconds, if (day in backfilled) 1 else 0)
        }
    }

    fun totals(log: ReadingLog): Array<Any?> = log.totals().let { (pages, seconds) -> arrayOf(pages, seconds) }
}

/** Cover resolution for `cover/#`: the custom cover first, then the downloaded (cached) one. */
object CoverFiles {
    fun pick(custom: File?, cached: File?): File? =
        custom?.takeIf { it.isFile } ?: cached?.takeIf { it.isFile }

    /** `cover_version`: the cover file's mtime, or 0 when there is none. */
    fun version(file: File?): Long = file?.lastModified() ?: 0L
}
