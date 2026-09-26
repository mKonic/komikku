package eu.kanade.tachiyomi.ui.reader.loader

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class PageBytesTest {

    private val data = ByteArray(1000) { (it * 31 + 7).toByte() }

    private fun PageBytes.put(from: Int, to: Int) = write(from.toLong(), data, from, to - from)

    @Test
    fun `in-order writes read back whole`() {
        val bytes = PageBytes(data.size.toLong())
        bytes.put(0, 300)
        bytes.put(300, 1000)
        bytes.complete()
        assertArrayEquals(data, bytes.open { false }.readBytes())
    }

    @Test
    fun `out-of-order stretches only become readable once joined to the start`() {
        val bytes = PageBytes(data.size.toLong())
        bytes.put(500, 1000)
        bytes.put(250, 500)
        val reader = bytes.open { false }
        val head = ByteArray(1000)

        bytes.put(0, 100)
        assertEquals(100, reader.read(head, 0, 1000))

        // Fills the gap to the stretch already at 250, which carries on to the end.
        bytes.put(100, 250)
        assertEquals(900, reader.read(head, 100, 900))
        bytes.complete()
        assertEquals(-1, reader.read(head, 0, 1))
        assertArrayEquals(data, head)
    }

    @Test
    fun `overlapping rewrites of the same bytes change nothing`() {
        val bytes = PageBytes(data.size.toLong())
        bytes.put(0, 600)
        bytes.put(200, 800)
        bytes.put(0, 1000)
        bytes.complete()
        assertArrayEquals(data, bytes.open { false }.readBytes())
    }

    @Test
    fun `an unknown length grows past the initial buffer`() {
        val big = ByteArray(700_000) { it.toByte() }
        val bytes = PageBytes(-1)
        var at = 0
        while (at < big.size) {
            val n = minOf(65_536, big.size - at)
            bytes.write(at.toLong(), big, at, n)
            at += n
        }
        bytes.complete()
        assertArrayEquals(big, bytes.open { false }.readBytes())
    }

    @Test
    @Timeout(5, unit = TimeUnit.SECONDS)
    fun `a reader blocks until the bytes it needs land`() {
        val bytes = PageBytes(data.size.toLong())
        val reader = bytes.open { false }
        val writer = thread {
            Thread.sleep(100)
            bytes.put(0, 1000)
            bytes.complete()
        }
        assertArrayEquals(data, reader.readBytes())
        writer.join()
    }

    @Test
    @Timeout(5, unit = TimeUnit.SECONDS)
    fun `a failed download fails the reader waiting on it`() {
        val bytes = PageBytes(data.size.toLong())
        bytes.put(0, 10)
        val reader = bytes.open { false }
        reader.read(ByteArray(10))
        thread {
            Thread.sleep(100)
            bytes.fail(IOException("gone"))
        }
        assertThrows(IOException::class.java) { reader.read(ByteArray(10)) }
    }

    @Test
    @Timeout(5, unit = TimeUnit.SECONDS)
    fun `a reader nobody wants stops waiting`() {
        val bytes = PageBytes(data.size.toLong())
        val cancelled = AtomicBoolean(false)
        val reader = bytes.open { cancelled.get() }
        thread {
            Thread.sleep(100)
            cancelled.set(true)
        }
        assertThrows(IOException::class.java) { reader.read(ByteArray(10)) }
    }
}
