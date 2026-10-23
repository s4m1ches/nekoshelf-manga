package komikku.nekoshelf.bridge

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.Constants
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.history.interactor.GetNextChapters
import tachiyomi.domain.history.repository.HistoryRepository
import tachiyomi.domain.manga.interactor.GetManga
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

sealed interface OpenTarget {
    data class Reader(val mangaId: Long, val chapterId: Long, val page: Int?) : OpenTarget
    data class Details(val mangaId: Long) : OpenTarget
    data object Nothing : OpenTarget
}

/**
 * Order: in-progress chapter (not read, lastPageRead > 0; the most recently read one by [lastReadAt],
 * falling back to lastModifiedAt) -> [next] at the start -> details if the manga exists -> nothing.
 */
fun decideOpenTarget(
    mangaExists: Boolean,
    chapters: List<Chapter>,
    next: Chapter?,
    mangaId: Long,
    lastReadAt: Map<Long, Long> = emptyMap(),
): OpenTarget {
    val inProgress = chapters
        .filter { !it.read && it.lastPageRead > 0 }
        .maxWithOrNull(compareBy<Chapter>({ lastReadAt[it.id] ?: 0L }, { it.lastModifiedAt }))
    if (inProgress != null) {
        val page = inProgress.lastPageRead.takeIf { it <= Int.MAX_VALUE }?.toInt()
        return OpenTarget.Reader(mangaId, inProgress.id, page)
    }
    if (next != null) return OpenTarget.Reader(mangaId, next.id, null)
    return if (mangaExists) OpenTarget.Details(mangaId) else OpenTarget.Nothing
}

/** Signature-protected trampoline (manifest permission): the launcher opens a manga at the right chapter. */
class BridgeOpenActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mangaId = intent?.getLongExtra(EXTRA_MANGA_ID, -1L) ?: -1L
        if (mangaId == -1L) {
            finish()
            return
        }
        lifecycleScope.launch {
            try {
                val target = withIOContext { resolve(mangaId) }
                val intent = when (target) {
                    is OpenTarget.Reader -> ReaderActivity.newIntent(
                        this@BridgeOpenActivity,
                        target.mangaId,
                        target.chapterId,
                        target.page,
                    )
                    is OpenTarget.Details -> Intent(this@BridgeOpenActivity, MainActivity::class.java).apply {
                        action = Constants.SHORTCUT_MANGA
                        putExtra(Constants.MANGA_EXTRA, target.mangaId)
                    }
                    OpenTarget.Nothing -> null
                }
                intent?.let {
                    it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(it)
                }
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Bridge open failed" }
            } finally {
                finish()
            }
        }
    }

    private suspend fun resolve(mangaId: Long): OpenTarget {
        val manga = Injekt.get<GetManga>().await(mangaId)
        val chapters = Injekt.get<GetChaptersByMangaId>().await(mangaId)
        val next = Injekt.get<GetNextChapters>().await(mangaId, onlyUnread = true).firstOrNull()
        val lastReadAt = Injekt.get<HistoryRepository>().getHistoryByMangaId(mangaId)
            .associate { it.chapterId to (it.readAt?.time ?: 0L) }
        return decideOpenTarget(manga != null, chapters, next, mangaId, lastReadAt)
    }

    companion object {
        const val ACTION_OPEN_MANGA = "app.nekoshelf.manga.action.OPEN_MANGA"
        const val EXTRA_MANGA_ID = "manga_id"
    }
}
