package eu.kanade.tachiyomi.ui.reader.loader

import java.io.IOException
import java.io.InputStream
import java.util.TreeMap

/**
 * A page's bytes as they download, readable in order before the download finishes, so a viewer
 * can start decoding the top of the image while the rest is still on its way.
 *
 * Writes may land anywhere (a ranged download fills several stretches at once); readers only see
 * the contiguous run from the start. Held in memory for the length of one download, next to the
 * chapter cache file that is the page's real copy.
 */
class PageBytes(expectedLength: Long) {

    private var data = ByteArray(expectedLength.toInt().coerceIn(INITIAL_CAPACITY, MAX_LENGTH))

    /** Filled stretches, start to end (exclusive), merged whenever two touch. */
    private val filled = TreeMap<Long, Long>()

    /** End of the stretch that starts at 0: what readers may read. */
    private var readable = 0L

    private var done = false
    private var failure: Throwable? = null

    /** Copies [len] bytes of [bytes] from [off] to [position]. */
    @Synchronized
    fun write(position: Long, bytes: ByteArray, off: Int, len: Int) {
        if (len <= 0 || done || failure != null) return
        val end = position + len
        if (end > MAX_LENGTH) throw IOException("Page larger than $MAX_LENGTH bytes")
        if (end > data.size) data = data.copyOf(maxOf(end.toInt(), minOf(data.size * 2, MAX_LENGTH)))
        System.arraycopy(bytes, off, data, position.toInt(), len)

        var start = position
        var stop = end
        filled.floorEntry(start)?.let { before ->
            if (before.value >= start) {
                start = before.key
                stop = maxOf(stop, before.value)
            }
        }
        while (true) {
            val next = filled.ceilingEntry(start + 1) ?: break
            if (next.key > stop) break
            stop = maxOf(stop, next.value)
            filled.remove(next.key)
        }
        filled[start] = stop
        val fromZero = filled[0L]
        if (fromZero != null && fromZero > readable) {
            readable = fromZero
            (this as Object).notifyAll()
        }
    }

    /** Every byte is in: readers see the end of the stream once they reach [readable]. */
    @Synchronized
    fun complete() {
        done = true
        (this as Object).notifyAll()
    }

    /** The download failed or was abandoned: readers get [cause] instead of more bytes. */
    @Synchronized
    fun fail(cause: Throwable) {
        if (done) return
        failure = cause
        (this as Object).notifyAll()
    }

    /**
     * A stream over the bytes from the start, blocking for each next one until it lands.
     * [cancelled] is polled while waiting, so a reader nobody wants any more stops blocking.
     */
    fun open(cancelled: () -> Boolean): InputStream = Reader(cancelled)

    private inner class Reader(private val cancelled: () -> Boolean) : InputStream() {
        private var position = 0L

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) == 1) one[0].toInt() and 0xFF else -1
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            synchronized(this@PageBytes) {
                while (position >= readable) {
                    failure?.let { throw IOException("Page download failed", it) }
                    if (done) return -1
                    if (cancelled()) throw IOException("Page no longer wanted")
                    (this@PageBytes as Object).wait(WAIT_MS)
                }
                val n = minOf(len.toLong(), readable - position).toInt()
                System.arraycopy(data, position.toInt(), b, off, n)
                position += n
                return n
            }
        }
    }

    companion object {
        private const val INITIAL_CAPACITY = 256 * 1024

        /** Past this a page just downloads to the cache as before and decodes when it is complete. */
        const val MAX_LENGTH = 64 * 1024 * 1024

        private const val WAIT_MS = 200L
    }
}
