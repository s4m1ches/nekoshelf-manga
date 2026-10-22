package komikku.meebook.bridge

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.model.Manga
import java.io.File

class LibraryRowsTest {

    // favorite = false keeps Manga.title off the Injekt-backed custom-info lookup in a plain JVM test;
    // the provider reads favourites, where Manga.title resolves the custom title.
    private fun libraryManga(id: Long = 7L, total: Long = 120, read: Long = 58, lastRead: Long = 1_700_000_000_000L) =
        LibraryManga(
            manga = Manga.create().copy(id = id, source = 42L, ogTitle = "Берсерк", dateAdded = 1_600_000_000_000L),
            categories = listOf(0L),
            totalChapters = total,
            readCount = read,
            bookmarkCount = 0,
            bookmarkReadCount = 0,
            chapterFlags = 0,
            latestUpload = 0,
            chapterFetchedAt = 0,
            lastRead = lastRead,
        )

    private val next = Chapter.create().copy(id = 5059L, mangaId = 7L, name = "Глава 59")

    private fun col(row: Array<Any?>, name: String) = row[LibraryRows.COLUMNS.indexOf(name)]

    @Test
    fun `columns follow the contract order`() {
        LibraryRows.COLUMNS.toList() shouldBe listOf(
            "manga_id", "title", "source_name", "date_added", "last_read_at",
            "chapters_total", "chapters_read", "next_chapter_id", "next_chapter_name", "cover_version",
        )
    }

    @Test
    fun `row maps counts, next chapter and last read`() {
        val row = LibraryRows.row(libraryManga(), next, "MangaDex", 123L)
        row.size shouldBe LibraryRows.COLUMNS.size
        col(row, "manga_id") shouldBe 7L
        col(row, "title") shouldBe "Берсерк"
        col(row, "source_name") shouldBe "MangaDex"
        col(row, "date_added") shouldBe 1_600_000_000_000L
        col(row, "last_read_at") shouldBe 1_700_000_000_000L
        col(row, "chapters_total") shouldBe 120
        col(row, "chapters_read") shouldBe 58
        col(row, "next_chapter_id") shouldBe 5059L
        col(row, "next_chapter_name") shouldBe "Глава 59"
        col(row, "cover_version") shouldBe 123L
    }

    @Test
    fun `no next chapter gives null next columns`() {
        val row = LibraryRows.row(libraryManga(), null, "MangaDex", 0L)
        col(row, "next_chapter_id") shouldBe null
        col(row, "next_chapter_name") shouldBe null
    }

    @Test
    fun `never read gives zero last read`() {
        col(LibraryRows.row(libraryManga(lastRead = 0L), null, "S", 0L), "last_read_at") shouldBe 0L
    }

    @Test
    fun `distinct keeps one row per manga id`() {
        val a = libraryManga(id = 1L)
        val b = libraryManga(id = 2L)
        LibraryRows.distinct(listOf(a, b, a.copy(readCount = 3))).map { it.manga.id } shouldBe listOf(1L, 2L)
    }

    @Test
    fun `stats daily marks backfilled days`() {
        val log = ReadingLog(InMemoryReadingLogStore())
        log.addBackfill("2026-10-20", 600)
        log.add(DwellRecord("2026-10-21", 30))
        StatsRows.daily(log).map { it.toList() } shouldBe listOf(
            listOf("2026-10-20", 0, 600, 1),
            listOf("2026-10-21", 1, 30, 0),
        )
        StatsRows.DAILY_COLUMNS.toList() shouldBe listOf("day", "pages", "seconds", "backfilled")
    }

    @Test
    fun `totals is one row of longs`() {
        val log = ReadingLog(InMemoryReadingLogStore())
        log.add(DwellRecord("2026-10-21", 30))
        log.add(DwellRecord("2026-10-22", 40))
        StatsRows.totals(log).toList() shouldBe listOf(2L, 70L)
        StatsRows.TOTALS_COLUMNS.toList() shouldBe listOf("pages_total", "seconds_total")
    }

    @Test
    fun `cover prefers custom, then cached, else none`(@TempDir dir: File) {
        val custom = File(dir, "custom")
        val cached = File(dir, "cached").apply { writeText("c") }
        CoverFiles.pick(custom, cached) shouldBe cached
        custom.writeText("x")
        CoverFiles.pick(custom, cached) shouldBe custom
        CoverFiles.pick(File(dir, "nope"), null) shouldBe null
        CoverFiles.pick(File(dir, "nope"), File(dir, "nope2")) shouldBe null
    }

    @Test
    fun `cover version is file mtime or zero`(@TempDir dir: File) {
        val f = File(dir, "c").apply { writeText("c") }
        f.setLastModified(1_650_000_000_000L)
        CoverFiles.version(f) shouldBe 1_650_000_000_000L
        CoverFiles.version(null) shouldBe 0L
    }
}
