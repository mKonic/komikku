package eu.kanade.tachiyomi.data.cache

import android.content.Context
import eu.kanade.tachiyomi.util.storage.DiskUtil
import tachiyomi.domain.manga.model.Manga
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Class used to create cover cache.
 * It is used to store the covers of the library.
 * Names of files are created with the md5 of the thumbnail URL.
 *
 * @param context the application context.
 * @constructor creates an instance of the cover cache.
 */
class CoverCache(private val context: Context) {

    companion object {
        private const val COVERS_DIR = "covers"
        private const val CUSTOM_COVERS_DIR = "covers/custom"
    }

    /**
     * Cache directory used for cache management.
     */
    private val cacheDir = getCacheDir(COVERS_DIR)

    private val customCoverCacheDir = getCacheDir(CUSTOM_COVERS_DIR)

    /** Per manga, the [Manga.coverLastModified] a custom cover was last looked for at, and what was found. */
    private val customCoverSeen = ConcurrentHashMap<Long, Pair<Long, Boolean>>()

    /**
     * Whether the manga has a custom cover, answered from memory once it has been looked for.
     *
     * The cover keyers ask this for every cover on screen, on whatever thread is composing, so the plain
     * [File.exists] behind it was a disk read per cover per frame on the main thread. The answer is remembered
     * against [coverLastModified], which the paths that add or remove a custom cover update, and which the cover's
     * own cache key is built from: a memo that outlived a change to it would mean the key had gone stale first.
     * [setCustomCoverToCache] and [deleteCustomCover] drop their entry as well, for the migration path, which
     * gives a manga a custom cover without touching its [coverLastModified].
     */
    fun hasCustomCover(mangaId: Long?, coverLastModified: Long): Boolean {
        val id = mangaId ?: return false
        customCoverSeen[id]?.let { (seenAt, found) -> if (seenAt == coverLastModified) return found }
        val found = getCustomCoverFile(id).exists()
        customCoverSeen[id] = coverLastModified to found
        return found
    }

    /**
     * Returns the cover from cache.
     *
     * @param mangaThumbnailUrl thumbnail url for the manga.
     * @return cover image.
     */
    fun getCoverFile(mangaThumbnailUrl: String?): File? {
        return mangaThumbnailUrl?.let {
            File(cacheDir, DiskUtil.hashKeyForDisk(it))
        }
    }

    /**
     * Returns the custom cover from cache.
     *
     * @param mangaId the manga id.
     * @return cover image.
     */
    fun getCustomCoverFile(mangaId: Long?): File {
        return File(customCoverCacheDir, DiskUtil.hashKeyForDisk(mangaId.toString()))
    }

    /**
     * Saves the given stream as the manga's custom cover to cache.
     *
     * @param manga the manga.
     * @param inputStream the stream to copy.
     * @throws IOException if there's any error.
     */
    @Throws(IOException::class)
    fun setCustomCoverToCache(manga: Manga, inputStream: InputStream) {
        getCustomCoverFile(manga.id).outputStream().use {
            inputStream.copyTo(it)
        }
        customCoverSeen.remove(manga.id)
    }

    /**
     * Delete the cover files of the manga from the cache.
     *
     * @param manga the manga.
     * @param deleteCustomCover whether the custom cover should be deleted.
     * @return number of files that were deleted.
     */
    fun deleteFromCache(manga: Manga, deleteCustomCover: Boolean = false): Int {
        var deleted = 0

        getCoverFile(manga.thumbnailUrl)?.let {
            if (it.exists() && it.delete()) ++deleted
        }

        if (deleteCustomCover) {
            if (deleteCustomCover(manga.id)) ++deleted
        }

        return deleted
    }

    /**
     * Delete custom cover of the manga from the cache
     *
     * @param mangaId the manga id.
     * @return whether the cover was deleted.
     */
    fun deleteCustomCover(mangaId: Long?): Boolean {
        customCoverSeen.remove(mangaId)
        return getCustomCoverFile(mangaId).let {
            it.exists() && it.delete()
        }
    }

    private fun getCacheDir(dir: String): File {
        return context.getExternalFilesDir(dir)
            ?: File(context.filesDir, dir).also { it.mkdirs() }
    }
}
