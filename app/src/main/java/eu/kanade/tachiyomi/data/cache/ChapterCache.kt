package eu.kanade.tachiyomi.data.cache

import android.content.Context
import android.text.format.Formatter
import com.jakewharton.disklrucache.DiskLruCache
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.util.storage.DiskUtil
import eu.kanade.tachiyomi.util.storage.saveTo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.serialization.json.Json
import logcat.LogPriority
import okhttp3.Response
import okio.buffer
import okio.sink
import okio.source
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import java.io.File
import java.io.IOException

/**
 * Class used to create chapter cache
 * For each image in a chapter a file is created
 * For each chapter a Json list is created and converted to a file.
 * The files are in format *md5key*.0
 *
 * @param context the application context.
 */
class ChapterCache(
    private val context: Context,
    private val json: Json,
    // SY -->
    readerPreferences: ReaderPreferences,
    // SY <--
) {

    // --> EH
    private val scope = CoroutineScope(Job() + Dispatchers.Main)

    /** Cache class used for cache management.  */
    private val diskCache = setupDiskCache(readerPreferences.cacheSize().get())

    init {
        readerPreferences.cacheSize().changes()
            .drop(1)
            .onEach {
                // Resize in place. Opening a second DiskLruCache over the same directory while
                // the old one still holds its journal writer interleaves both writers' lines and
                // corrupts the journal, which wipes the whole cache on the next launch.
                diskCache.maxSize = toMaxSizeBytes(it)
            }
            .launchIn(scope)
    }
    // <-- EH

    /**
     * Returns directory of cache.
     */
    private val cacheDir: File = diskCache.directory

    /**
     * Returns real size of directory.
     */
    private val realSize: Long
        get() = DiskUtil.getDirectorySize(cacheDir)

    /**
     * Returns real size of directory in human readable format.
     */
    val readableSize: String
        get() = Formatter.formatFileSize(context, realSize)

    // --> EH
    // Cache size is in MB
    private fun setupDiskCache(cacheSize: String): DiskLruCache {
        return DiskLruCache.open(
            File(context.cacheDir, "chapter_disk_cache"),
            PARAMETER_APP_VERSION,
            PARAMETER_VALUE_COUNT,
            toMaxSizeBytes(cacheSize),
        )
    }

    /**
     * Translates the stored cache size preference (in MB) into a byte budget for [DiskLruCache].
     *
     * A value of zero or less means unlimited: nothing is ever evicted, so a page that has been
     * downloaded once stays readable until the cache is cleared explicitly. Pair it with the
     * "Clear chapter cache on app launch" setting to keep it from growing without bound.
     */
    private fun toMaxSizeBytes(cacheSize: String): Long {
        val megabytes = cacheSize.toLongOrNull() ?: DEFAULT_CACHE_SIZE_MB
        return if (megabytes <= 0) Long.MAX_VALUE else megabytes * 1024 * 1024
    }
    // <-- EH

    /**
     * Get page list from cache.
     *
     * @param chapter the chapter.
     * @return the list of pages.
     */
    fun getPageListFromCache(chapter: Chapter): List<Page> {
        // Get the key for the chapter.
        val key = DiskUtil.hashKeyForDisk(getKey(chapter))

        // Convert JSON string to list of objects. Throws an exception if snapshot is null
        return diskCache.get(key).use {
            json.decodeFromString(it.getString(0))
        }
    }

    /**
     * Add page list to disk cache.
     *
     * @param chapter the chapter.
     * @param pages list of pages.
     */
    fun putPageListToCache(chapter: Chapter, pages: List<Page>) {
        // Convert list of pages to json string.
        val cachedValue = json.encodeToString(pages)

        // Initialize the editor (edits the values for an entry).
        var editor: DiskLruCache.Editor? = null

        try {
            // Get editor from md5 key.
            val key = DiskUtil.hashKeyForDisk(getKey(chapter))
            editor = diskCache.edit(key) ?: return

            // Write chapter urls to cache.
            editor.newOutputStream(0).sink().buffer().use {
                it.write(cachedValue.toByteArray())
                it.flush()
            }

            editor.commit()
            editor.abortUnlessCommitted()
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Failed to put page list to cache" }
            // Ignore.
        } finally {
            editor?.abortUnlessCommitted()
        }
    }

    /**
     * Returns true if image is in cache.
     *
     * @param imageUrl url of image.
     * @return true if in cache otherwise false.
     */
    fun isImageInCache(imageUrl: String): Boolean {
        return try {
            diskCache.get(DiskUtil.hashKeyForDisk(imageUrl)).use { it != null }
        } catch (_: IOException) {
            false
        }
    }

    /**
     * Get image file from url.
     *
     * @param imageUrl url of image.
     * @return path of image.
     */
    fun getImageFile(imageUrl: String): File {
        // Get file from md5 key.
        val key = DiskUtil.hashKeyForDisk(imageUrl)
        // KMK -->
        // Count the read as a use. Pages are opened by path, so without this the cache never
        // observes a read at all and evicts in write order instead of least-recently-used
        // order -- which makes the pages at the head of a preload burst, the ones queued but
        // not yet displayed, the first things dropped once the cache is full.
        try {
            diskCache.get(key)?.close()
        } catch (_: IOException) {
            // Nothing to promote; the caller will find out when it opens the file.
        }
        // KMK <--
        return File(diskCache.directory, "$key.0")
    }

    /**
     * Add image to cache.
     *
     * @param imageUrl url of image.
     * @param response http response from page.
     * @throws IOException image error.
     */
    @Throws(IOException::class)
    fun putImageToCache(imageUrl: String, response: Response) {
        // Initialize editor (edits the values for an entry).
        var editor: DiskLruCache.Editor? = null

        try {
            // Get editor from md5 key.
            val key = DiskUtil.hashKeyForDisk(imageUrl)
            editor = diskCache.edit(key) ?: return

            // Get OutputStream and write image with Okio.
            response.body.source().saveTo(editor.newOutputStream(0))

            // commit() flushes the journal itself. An extra flush() here would also run
            // trimToSize() on this thread, holding the cache lock while it deletes evicted files.
            editor.commit()
        } finally {
            response.body.close()
            editor?.abortUnlessCommitted()
        }
    }

    /**
     * Add an already downloaded image to cache.
     *
     * @param imageUrl url of image.
     * @param file the file holding the complete image.
     * @throws IOException image error.
     */
    @Throws(IOException::class)
    fun putImageToCache(imageUrl: String, file: File) {
        // Initialize editor (edits the values for an entry).
        var editor: DiskLruCache.Editor? = null

        try {
            // Get editor from md5 key.
            val key = DiskUtil.hashKeyForDisk(imageUrl)
            editor = diskCache.edit(key) ?: return

            file.source().buffer().saveTo(editor.newOutputStream(0))

            editor.commit()
        } finally {
            editor?.abortUnlessCommitted()
        }
    }

    fun clear(): Int {
        var deletedFiles = 0
        cacheDir.listFiles()?.forEach {
            if (removeFileFromCache(it.name)) {
                deletedFiles++
            }
        }
        return deletedFiles
    }

    /**
     * Remove file from cache.
     *
     * @param file name of file "md5.0".
     * @return status of deletion for the file.
     */
    private fun removeFileFromCache(file: String): Boolean {
        // Make sure we don't delete the journal file (keeps track of cache)
        if (file == "journal" || file.startsWith("journal.")) {
            return false
        }

        return try {
            // Remove the extension from the file to get the key of the cache
            val key = file.substringBeforeLast(".")
            // Remove file from cache
            diskCache.remove(key)
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Failed to remove file from cache" }
            false
        }
    }

    private fun getKey(chapter: Chapter): String {
        return "${chapter.mangaId}${chapter.url}"
    }
}

/** Application cache version.  */
private const val PARAMETER_APP_VERSION = 1

/** The number of values per cache entry. Must be positive.  */
private const val PARAMETER_VALUE_COUNT = 1

/** The maximum number of bytes this cache should use to store.  */
private const val PARAMETER_CACHE_SIZE = 100L * 1024 * 1024

/** Fallback in MB used when the stored cache size preference isn't a number.  */
private const val DEFAULT_CACHE_SIZE_MB = 75L
