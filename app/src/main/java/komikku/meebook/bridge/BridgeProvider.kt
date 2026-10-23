package komikku.meebook.bridge

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import eu.kanade.tachiyomi.data.backup.create.BackupCreator
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.cache.CoverCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.history.interactor.GetNextChapters
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.io.FileNotFoundException

/**
 * Read-only bridge for the owner's launcher: `library`, `cover/#`, `stats_daily`, `totals`, `backup`
 * (contract: `docs/superpowers/specs/2026-10-23-manga-bridge-contract.md` in the launcher repo).
 * Declared only in the meebook manifest, guarded by the signature permission `…permission.BRIDGE`.
 * Called from the launcher's IO thread, so the suspend interactors are run with [runBlocking].
 */
class BridgeProvider : ContentProvider() {

    // Injekt is filled in App.onCreate, which runs after provider onCreate: resolve lazily.
    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor = when (MATCHER.match(uri)) {
        LIBRARY -> libraryCursor()
        STATS_DAILY -> MatrixCursor(StatsRows.DAILY_COLUMNS).apply {
            StatsRows.daily(Injekt.get<ReadingLog>()).forEach(::addRow)
        }
        TOTALS -> MatrixCursor(StatsRows.TOTALS_COLUMNS).apply { addRow(StatsRows.totals(Injekt.get<ReadingLog>())) }
        else -> throw IllegalArgumentException("Unknown URI: $uri")
    }

    private fun libraryCursor(): Cursor {
        val getLibraryManga = Injekt.get<GetLibraryManga>()
        val getNextChapters = Injekt.get<GetNextChapters>()
        val getChapters = Injekt.get<GetChaptersByMangaId>()
        val sourceManager = Injekt.get<SourceManager>()
        val cursor = MatrixCursor(LibraryRows.COLUMNS)
        runBlocking(Dispatchers.IO) {
            LibraryRows.distinct(getLibraryManga.await()).forEach { lm ->
                val next = try {
                    getNextChapters.await(lm.manga.id, onlyUnread = true).firstOrNull()
                } catch (e: Exception) {
                    logcat(LogPriority.WARN, e) { "MEEBOOK: next chapter of ${lm.manga.id} failed" }
                    null
                }
                val chaptersNew = try {
                    LibraryRows.newChapters(getChapters.await(lm.manga.id), lm.lastRead)
                } catch (e: Exception) {
                    logcat(LogPriority.WARN, e) { "MEEBOOK: new chapters of ${lm.manga.id} failed" }
                    0
                }
                val sourceName = sourceManager.getOrStub(lm.manga.source).name
                cursor.addRow(
                    LibraryRows.row(lm, next, sourceName, CoverFiles.version(coverFile(lm.manga)), chaptersNew),
                )
            }
        }
        return cursor
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val match = MATCHER.match(uri)
        if (match != COVER && match != BACKUP) throw FileNotFoundException("Unknown URI: $uri")
        // Only readPermission is declared: a write mode would be checked against the (absent) write permission.
        if (mode != "r") throw SecurityException("Read-only provider, mode \"$mode\" refused")
        if (match == BACKUP) return openBackup()
        val mangaId = uri.lastPathSegment?.toLongOrNull() ?: throw FileNotFoundException("Bad id: $uri")
        val manga = runBlocking(Dispatchers.IO) {
            Injekt.get<GetManga>().await(mangaId)
        } ?: throw FileNotFoundException("No manga $mangaId")
        val file = coverFile(manga) ?: throw FileNotFoundException("No cached cover for $mangaId")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    /** A fresh full backup (same content as Settings -> Backup -> Create), written to the cache dir. */
    private fun openBackup(): ParcelFileDescriptor {
        try {
            val context = context ?: throw IllegalStateException("No context")
            val file = File(context.cacheDir, "meebook-bridge-backup.tachibk")
            file.delete()
            runBlocking(Dispatchers.IO) {
                BackupCreator(context, isAutoBackup = false).backup(Uri.fromFile(file), BackupOptions())
            }
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "MEEBOOK: bridge backup failed" }
            throw FileNotFoundException("Backup failed: ${e.message}")
        }
    }

    private fun coverFile(manga: Manga): File? {
        val coverCache = Injekt.get<CoverCache>()
        return CoverFiles.pick(coverCache.getCustomCoverFile(manga.id), coverCache.getCoverFile(manga.thumbnailUrl))
    }

    override fun getType(uri: Uri): String? = when (MATCHER.match(uri)) {
        LIBRARY -> "vnd.android.cursor.dir/vnd.$AUTHORITY.library"
        STATS_DAILY -> "vnd.android.cursor.dir/vnd.$AUTHORITY.stats_daily"
        TOTALS -> "vnd.android.cursor.item/vnd.$AUTHORITY.totals"
        COVER -> "image/*"
        BACKUP -> "application/octet-stream"
        else -> null
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri = throw UnsupportedOperationException("Read-only")

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Read-only")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Read-only")

    companion object {
        const val AUTHORITY = "app.komikku.meebook.bridge"
        private const val LIBRARY = 1
        private const val COVER = 2
        private const val STATS_DAILY = 3
        private const val TOTALS = 4
        private const val BACKUP = 5

        private val MATCHER = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(AUTHORITY, "library", LIBRARY)
            addURI(AUTHORITY, "cover/#", COVER)
            addURI(AUTHORITY, "stats_daily", STATS_DAILY)
            addURI(AUTHORITY, "totals", TOTALS)
            addURI(AUTHORITY, "backup", BACKUP)
        }
    }
}
