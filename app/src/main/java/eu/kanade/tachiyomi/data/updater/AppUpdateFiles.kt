package eu.kanade.tachiyomi.data.updater

import java.io.File
import java.security.MessageDigest

/**
 * Where an app update download is kept while it runs and once it is done.
 *
 * A partial download may only be resumed by a download of the same file. Resuming a newer release onto what is left
 * of an older one appends the new file's tail to the old file's head: it comes out the right size and cannot be
 * installed.
 */
internal object AppUpdateFiles {

    private const val APK = "update.apk"
    private const val PARTIAL_PREFIX = "update-"
    private const val PARTIAL_SUFFIX = ".apk.part"

    /** The finished download, handed to the installer. */
    fun apk(cacheDir: File) = File(cacheDir, APK)

    /** The download of [url] while it is in progress. Only a download of the same url resumes it. */
    fun partial(cacheDir: File, url: String) = File(cacheDir, PARTIAL_PREFIX + key(url) + PARTIAL_SUFFIX)

    /** Deletes the finished download and every partial download that is not of [url]. */
    fun clearOthers(cacheDir: File, url: String) {
        val keep = partial(cacheDir, url).name
        cacheDir.listFiles { file ->
            file.name != keep &&
                (file.name == APK || (file.name.startsWith(PARTIAL_PREFIX) && file.name.endsWith(PARTIAL_SUFFIX)))
        }?.forEach { it.delete() }
    }

    private fun key(url: String): String = MessageDigest.getInstance("SHA-256")
        .digest(url.toByteArray())
        .take(8)
        .joinToString("") { "%02x".format(it) }
}
