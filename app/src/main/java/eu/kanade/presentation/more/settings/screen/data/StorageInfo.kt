package eu.kanade.presentation.more.settings.screen.data

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.util.storage.DiskUtil
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.theme.header
import tachiyomi.presentation.core.util.secondaryItemAlpha
import java.io.File

/** A volume and how much space it has, read together so none of it is asked from the main thread. */
private data class Volume(val file: File, val available: Long, val total: Long)

@Composable
fun StorageInfo(
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // KMK: listing the volumes and asking each one for its space both read storage state, so they are done off the
    // main thread; the rows below only format what comes back
    val volumes by produceState(emptyList<Volume>()) {
        value = withIOContext {
            DiskUtil.getExternalStorages(context).map {
                Volume(it, DiskUtil.getAvailableStorageSpace(it), DiskUtil.getTotalStorageSpace(it))
            }
        }
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
    ) {
        volumes.forEach {
            StorageInfo(it)
        }
    }
}

@Composable
private fun StorageInfo(
    volume: Volume,
) {
    val context = LocalContext.current

    val availableText = remember(volume.available) { Formatter.formatFileSize(context, volume.available) }
    val totalText = remember(volume.total) { Formatter.formatFileSize(context, volume.total) }

    Column(
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
    ) {
        Text(
            text = volume.file.absolutePath,
            style = MaterialTheme.typography.header,
        )

        LinearProgressIndicator(
            modifier = Modifier
                .clip(MaterialTheme.shapes.small)
                .fillMaxWidth()
                .height(12.dp),
            progress = { (1 - (volume.available / volume.total.toFloat())) },
        )

        Text(
            text = stringResource(MR.strings.available_disk_space_info, availableText, totalText),
            modifier = Modifier.secondaryItemAlpha(),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
