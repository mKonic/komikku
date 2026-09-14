package tachiyomi.source.local.io

import com.hippo.unifile.UniFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import tachiyomi.core.common.util.lang.SharedCall
import tachiyomi.domain.storage.service.StorageManager

class LocalSourceFileSystem(
    private val storageManager: StorageManager,
) {

    // KMK --> one listing for every search that asks while it runs. Opening an entry searches each title keyword at
    // once, and on a large library those blocking listings took every IO thread, stalling everything else.
    private val baseDirectoryListing = SharedCall(CoroutineScope(SupervisorJob() + Dispatchers.IO)) {
        getBaseDirectory()?.listFiles().orEmpty().toList()
    }
    // KMK <--

    fun getBaseDirectory(): UniFile? {
        return storageManager.getLocalSourceDirectory()
    }

    suspend fun getFilesInBaseDirectory(): List<UniFile> {
        return baseDirectoryListing.await()
    }

    fun getMangaDirectory(name: String): UniFile? {
        return getBaseDirectory()
            ?.findFile(name)
            ?.takeIf { it.isDirectory }
    }

    fun getFilesInMangaDirectory(name: String): List<UniFile> {
        return getBaseDirectory()
            ?.findFile(name)
            ?.takeIf { it.isDirectory }
            ?.listFiles().orEmpty().toList()
    }
}
