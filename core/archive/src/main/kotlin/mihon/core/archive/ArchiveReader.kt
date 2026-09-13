package mihon.core.archive

import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import me.zhanghai.android.libarchive.ArchiveException
import java.io.Closeable
import java.io.InputStream

class ArchiveReader(pfd: ParcelFileDescriptor) : Closeable {
    val size = pfd.statSize
    val address = Os.mmap(0, size, OsConstants.PROT_READ, OsConstants.MAP_PRIVATE, pfd.fileDescriptor, 0)

    // KMK -->
    // Streams read straight from the mapping, so it stays mapped until the reader and its last open stream are
    // both closed. A page still decoding when its chapter's loader recycles would otherwise read unmapped memory.
    private val lock = Any()
    private var openStreams = 0
    private var closed = false
    // KMK <--

    // SY -->
    var encrypted: Boolean = false
        private set
    var wrongPassword: Boolean? = null
        private set
    val archiveHashCode = pfd.hashCode()

    init {
        checkEncryptionStatus()
    }
    // SY <--

    inline fun <T> useEntries(block: (Sequence<ArchiveEntry>) -> T): T = openStream(
        // SY -->
        encrypted,
        // SY <--
    ).use { block(generateSequence { it.getNextEntry() }) }

    fun getInputStream(entryName: String): InputStream? {
        val archive = openStream(/* SY --> */ encrypted /* SY <-- */)
        try {
            while (true) {
                val entry = archive.getNextEntry() ?: break
                if (entry.name == entryName) {
                    return archive
                }
            }
        } catch (e: ArchiveException) {
            archive.close()
            throw e
        }
        archive.close()
        return null
    }

    // KMK -->
    @PublishedApi
    internal fun openStream(encrypted: Boolean): ArchiveInputStream {
        synchronized(lock) {
            check(!closed) { "Archive is closed" }
            openStreams++
        }
        return ArchiveInputStream(address, size, encrypted, onClose = ::releaseStream)
    }

    private fun releaseStream() {
        val unmap = synchronized(lock) {
            openStreams--
            closed && openStreams == 0
        }
        if (unmap) Os.munmap(address, size)
    }
    // KMK <--

    // SY -->
    private fun checkEncryptionStatus() {
        val archive = openStream(false)
        try {
            while (true) {
                val entry = archive.getNextEntry() ?: break
                if (entry.isEncrypted) {
                    encrypted = true
                    isPasswordIncorrect(entry.name)
                    break
                }
            }
        } catch (e: ArchiveException) {
            archive.close()
            throw e
        }
        archive.close()
    }

    private fun isPasswordIncorrect(entryName: String) {
        try {
            getInputStream(entryName).use { stream ->
                stream!!.read()
            }
        } catch (e: ArchiveException) {
            if (e.message == "Incorrect passphrase") {
                wrongPassword = true
                return
            }
            throw e
        }
        wrongPassword = false
    }
    // SY <--

    override fun close() {
        // KMK -->
        val unmap = synchronized(lock) {
            if (closed) return
            closed = true
            openStreams == 0
        }
        if (unmap) Os.munmap(address, size)
        // KMK <--
    }
}
