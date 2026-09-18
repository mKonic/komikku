package eu.kanade.tachiyomi.debug.bridge

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle

/**
 * How adb reaches [DevBridge]:
 *
 * ```
 * adb shell content call --uri content://app.komikku.dev.devbridge --method go --arg settings_webgpu
 * ```
 *
 * A provider rather than a broadcast because `content call` hands the answer straight back, so a caller can ask the
 * app where it is instead of reading a screenshot. It exists only in the debug source set: a release build has no
 * such component to call.
 */
class DevBridgeProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        (context?.applicationContext as? Application)?.let(DevBridge::install)
        return true
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val context = context ?: return Bundle().apply { putString(RESULT, "error: no context") }
        return Bundle().apply { putString(RESULT, DevBridge.call(context, method, arg)) }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    private companion object {
        const val RESULT = "result"
    }
}
