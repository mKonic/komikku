package eu.kanade.tachiyomi.network

import okhttp3.MediaType
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.Source
import okio.buffer
import java.io.IOException

class ProgressResponseBody(
    /**
     * The body being measured. Exposed so a caller that reports progress for the same
     * [ProgressListener] itself can read the bytes from underneath this wrapper, rather than having
     * both of them report a different figure for the same transfer.
     */
    val responseBody: ResponseBody,
    private val progressListener: ProgressListener,
    /** Bytes already on disk from an earlier, interrupted attempt. */
    private val existingSize: Long = 0L,
) : ResponseBody() {

    private val bufferedSource: BufferedSource by lazy {
        source(responseBody.source()).buffer()
    }

    override fun contentType(): MediaType? {
        return responseBody.contentType()
    }

    override fun contentLength(): Long {
        return responseBody.contentLength()
    }

    override fun source(): BufferedSource {
        return bufferedSource
    }

    private fun source(source: Source): Source {
        return object : ForwardingSource(source) {
            var totalBytesRead = existingSize

            @Throws(IOException::class)
            override fun read(sink: Buffer, byteCount: Long): Long {
                val bytesRead = super.read(sink, byteCount)
                // read() returns the number of bytes read, or -1 if this source is exhausted.
                totalBytesRead += if (bytesRead != -1L) bytesRead else 0
                // contentLength() is the remaining bytes on a 206, not the size of the whole
                // file, so the part already on disk has to be added back on. It returns -1 when
                // the header is missing, which has to stay -1.
                val totalLength = responseBody.contentLength().let {
                    if (it != -1L) it + existingSize else -1L
                }
                progressListener.update(
                    totalBytesRead,
                    totalLength,
                    bytesRead == -1L,
                )
                return bytesRead
            }
        }
    }
}
