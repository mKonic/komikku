package eu.kanade.tachiyomi.ui.reader.loader

import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.source.model.Page
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import logcat.LogPriority
import okhttp3.OkHttpClient
import okhttp3.Response
import okio.BufferedSource
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection.HTTP_OK
import java.net.HttpURLConnection.HTTP_PARTIAL
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.ceil

/**
 * Fetches a single page image over several concurrent byte-range requests.
 *
 * A page normally arrives over one connection, so a host that throttles per connection caps the
 * transfer far below the link speed: a large page can take seconds on an otherwise idle
 * connection that could have delivered it immediately. Splitting the body into ranges lifts that
 * cap without asking the server for anything it hasn't advertised.
 *
 * Requests go through the source's own client, so extension headers, auth and rate limits all
 * still apply. That last one is why the split stays coarse: on a rate limited source every extra
 * range costs a permit, so a handful of large chunks pays off where many small ones would not.
 */
internal class ParallelImageDownloader(
    private val client: OkHttpClient,
    private val tmpDir: File,
) {

    sealed interface Result {
        /** Body fetched into [file]. The caller owns it and is responsible for deleting it. */
        data class Success(val file: File) : Result

        /** Not applicable. The response was left untouched and still has to be consumed. */
        data object Declined : Result

        /** Was applicable but failed. The response has been closed; refetch from scratch. */
        data object Failed : Result
    }

    suspend fun fetch(response: Response, page: Page): Result {
        val contentLength = response.body.contentLength()
        if (!supportsRanges(response, contentLength)) return Result.Declined

        val chunks = splitIntoChunks(contentLength)
        if (chunks.size < 2) return Result.Declined

        // Past this point the response belongs to us: the first range is read straight out of it
        // rather than throwing away a connection that is already streaming.
        val file = File(tmpDir, "${page.hashCode()}-${System.nanoTime()}.tmp")
        return try {
            tmpDir.mkdirs()
            RandomAccessFile(file, "rw").use { it.setLength(contentLength) }

            val downloaded = AtomicLong(0)
            coroutineScope {
                chunks.mapIndexed { index, range ->
                    async(Dispatchers.IO) {
                        if (index == 0) {
                            writeChunk(response.body.source(), file, range, downloaded, contentLength, page)
                        } else {
                            fetchChunk(response, file, range, downloaded, contentLength, page)
                        }
                    }
                }.awaitAll()
            }
            response.close()
            Result.Success(file)
        } catch (e: Throwable) {
            file.delete()
            response.close()
            if (e is CancellationException) throw e
            logcat(LogPriority.WARN, e) { "Ranged fetch failed, falling back to a single request" }
            Result.Failed
        }
    }

    /**
     * Only split when the server has said it will honour ranges and the body is big enough for the
     * extra round-trips to be worth it.
     */
    private fun supportsRanges(response: Response, contentLength: Long): Boolean {
        return response.code == HTTP_OK &&
            contentLength >= MIN_PARALLEL_SIZE &&
            response.header("Accept-Ranges")?.equals("bytes", ignoreCase = true) == true
    }

    private fun splitIntoChunks(contentLength: Long): List<LongRange> {
        val count = ceil(contentLength.toDouble() / TARGET_CHUNK_SIZE)
            .toInt()
            .coerceIn(1, MAX_PARALLEL_CHUNKS)
        if (count < 2) return emptyList()

        val chunkSize = contentLength / count
        return (0 until count).map { index ->
            val start = index * chunkSize
            val end = if (index == count - 1) contentLength - 1 else start + chunkSize - 1
            start..end
        }
    }

    private suspend fun fetchChunk(
        original: Response,
        file: File,
        range: LongRange,
        downloaded: AtomicLong,
        contentLength: Long,
        page: Page,
    ) {
        // Reuse the request that produced the original response so per-request headers the
        // extension set -- auth, referer, one-shot tokens -- carry over unchanged.
        val request = original.request.newBuilder()
            .header("Range", "bytes=${range.first}-${range.last}")
            .build()

        client.newCall(request).await().use { chunkResponse ->
            if (chunkResponse.code != HTTP_PARTIAL) {
                throw IOException("Expected 206 for bytes=${range.first}-${range.last}, got ${chunkResponse.code}")
            }
            withContext(Dispatchers.IO) {
                writeChunk(chunkResponse.body.source(), file, range, downloaded, contentLength, page)
            }
        }
    }

    /**
     * Each chunk opens its own handle so the writes can proceed at their own offsets without
     * sharing a file pointer.
     */
    private fun writeChunk(
        source: BufferedSource,
        file: File,
        range: LongRange,
        downloaded: AtomicLong,
        contentLength: Long,
        page: Page,
    ) {
        RandomAccessFile(file, "rw").use { out ->
            out.seek(range.first)

            val buffer = ByteArray(BUFFER_SIZE)
            var remaining = range.last - range.first + 1
            while (remaining > 0) {
                val read = source.read(buffer, 0, minOf(remaining, BUFFER_SIZE.toLong()).toInt())
                if (read == -1) throw IOException("Stream ended $remaining bytes short of the requested range")
                out.write(buffer, 0, read)
                remaining -= read
                page.update(downloaded.addAndGet(read.toLong()), contentLength, false)
            }
        }
    }
}

/** Bodies below this arrive fast enough that the extra requests would cost more than they save. */
private const val MIN_PARALLEL_SIZE = 2L * 1024 * 1024

/** Chunk count is derived from this, so bigger images get more connections rather than bigger reads. */
private const val TARGET_CHUNK_SIZE = 1L * 1024 * 1024

/**
 * Kept low so the chunks of one image, plus the other reader threads, stay near OkHttp's default
 * ceiling of five concurrent requests per host.
 */
private const val MAX_PARALLEL_CHUNKS = 4

private const val BUFFER_SIZE = 8 * 1024
