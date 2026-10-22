package komikku.meebook.bridge

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import eu.kanade.tachiyomi.data.cache.CoverCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
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
 * Read-only bridge for the owner's launcher: `library`, `cover/#`, `stats_daily`, `totals`
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
                val sourceName = sourceManager.getOrStub(lm.manga.source).name
                cursor.addRow(LibraryRows.row(lm, next, sourceName, CoverFiles.version(coverFile(lm.manga))))
            }
        }
        return cursor
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (MATCHER.match(uri) != COVER) throw FileNotFoundException("Unknown URI: $uri")
        // Only readPermission is declared: a write mode would be checked against the (absent) write permission.
        if (mode != "r") throw SecurityException("Read-only provider, mode \"$mode\" refused")
        val mangaId = uri.lastPathSegment?.toLongOrNull() ?: throw FileNotFoundException("Bad id: $uri")
        val manga = runBlocking(Dispatchers.IO) {
            Injekt.get<GetManga>().await(mangaId)
        } ?: throw FileNotFoundException("No manga $mangaId")
        val file = coverFile(manga) ?: throw FileNotFoundException("No cached cover for $mangaId")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
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

        private val MATCHER = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(AUTHORITY, "library", LIBRARY)
            addURI(AUTHORITY, "cover/#", COVER)
            addURI(AUTHORITY, "stats_daily", STATS_DAILY)
            addURI(AUTHORITY, "totals", TOTALS)
        }
    }
}
