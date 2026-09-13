package eu.kanade.tachiyomi.debug.stress

import android.net.Uri
import eu.kanade.tachiyomi.data.backup.BackupFileValidator
import eu.kanade.tachiyomi.data.backup.BackupNotifier
import eu.kanade.tachiyomi.data.backup.create.BackupCreator
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.backup.restore.BackupRestorer
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import tachiyomi.core.common.util.lang.withIOContext
import java.io.File
import kotlin.system.measureTimeMillis

/**
 * A backup of the whole library, checked and restored straight back over it. Settings are left out of the restore,
 * so a run never rewrites its own configuration; the library, chapters and categories go through in full.
 */
object BackupScenario : StressScenario {
    override val name = "backup"

    override suspend fun run(context: StressContext, iteration: Int) {
        val app = context.app
        val file = File(app.cacheDir, "stress-backup.tachibk")
        withIOContext {
            file.delete()
            file.createNewFile()
        }
        val uri = Uri.fromFile(file)
        try {
            val createMs = measureTimeMillis { BackupCreator(app, isAutoBackup = false).backup(uri, BackupOptions()) }
            context.step("created", mapOf("bytes" to file.length(), "ms" to createMs) + StressProbes.memory())

            val validateMs = measureTimeMillis { BackupFileValidator(app).validate(uri) }
            context.step("validated", mapOf("ms" to validateMs))

            val restoreMs = measureTimeMillis {
                BackupRestorer(app, BackupNotifier(app), isSync = false).restore(
                    uri,
                    RestoreOptions(appSettings = false, extensionStores = false, sourceSettings = false),
                )
            }
            context.step("restored", mapOf("ms" to restoreMs) + StressProbes.memory())
        } finally {
            withIOContext { file.delete() }
        }
    }
}
