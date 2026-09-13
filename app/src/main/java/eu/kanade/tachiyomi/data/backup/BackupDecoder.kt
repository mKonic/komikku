package eu.kanade.tachiyomi.data.backup

import android.content.Context
import android.net.Uri
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.protobuf.ProtoBuf
import okio.BufferedSource
import okio.buffer
import okio.gzip
import okio.source
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.IOException

class BackupDecoder(
    private val context: Context,
    private val parser: ProtoBuf = Injekt.get(),
) {
    /**
     * Decode a potentially-gzipped backup.
     */
    fun decode(uri: Uri): Backup {
        return open(uri) { source ->
            val bytes = source.readByteArray()
            try {
                parser.decodeFromByteArray(Backup.serializer(), bytes)
            } catch (_: SerializationException) {
                throw IOException(context.stringResource(MR.strings.invalid_backup_file_unknown))
            }
        }
    }

    // KMK --> read a field at a time, so restoring and validating never hold the whole library (mihonapp/mihon#3850)
    /** How many entries the backup has, and everything but the entries. */
    fun decodeMetadata(uri: Uri): Pair<Int, Backup> {
        return open(uri) { source ->
            try {
                BackupStream.readMetadata(source, parser)
            } catch (_: SerializationException) {
                throw IOException(context.stringResource(MR.strings.invalid_backup_file_unknown))
            }
        }
    }

    /** The backup's entries, decoded one at a time as they are collected. */
    fun decodeManga(uri: Uri): Flow<BackupManga> = flow {
        open(uri) { source ->
            BackupStream.forEachManga(source) { bytes ->
                val manga = try {
                    parser.decodeFromByteArray(BackupManga.serializer(), bytes)
                } catch (_: SerializationException) {
                    throw IOException(context.stringResource(MR.strings.invalid_backup_file_unknown))
                }
                emit(manga)
            }
        }
    }
    // KMK <--

    private inline fun <T> open(uri: Uri, block: (BufferedSource) -> T): T {
        return context.contentResolver.openInputStream(uri)!!.use { inputStream ->
            val source = inputStream.source().buffer()

            val peeked = source.peek().apply {
                require(2)
            }
            val id1id2 = peeked.readShort()
            when (id1id2.toInt()) {
                0x1f8b -> source.gzip().buffer() // 0x1f8b is gzip magic bytes
                MAGIC_JSON_SIGNATURE1, MAGIC_JSON_SIGNATURE2, MAGIC_JSON_SIGNATURE3 -> {
                    throw IOException(context.stringResource(MR.strings.invalid_backup_file_json))
                }
                else -> source
            }.use(block)
        }
    }

    companion object {
        private const val MAGIC_JSON_SIGNATURE1 = 0x7b7d // `{}`
        private const val MAGIC_JSON_SIGNATURE2 = 0x7b22 // `{"`
        private const val MAGIC_JSON_SIGNATURE3 = 0x7b0a // `{\n`
    }
}
