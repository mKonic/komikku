package eu.kanade.tachiyomi.data.download

import android.content.Context
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.asFlow
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.NetworkState
import eu.kanade.tachiyomi.util.system.activeNetworkState
import eu.kanade.tachiyomi.util.system.networkStateFlow
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.setForegroundSafely
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.job
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.atomic.AtomicBoolean

/**
 * This worker is used to manage the downloader. The system can decide to stop the worker, in
 * which case the downloader is also stopped. Losing the network pauses the active downloads, and they carry on
 * once it is back (mihonapp/mihon#3500).
 */
class DownloadJob(private val context: Context, workerParams: WorkerParameters) : CoroutineWorker(context, workerParams) {

    private val downloadManager: DownloadManager = Injekt.get()
    private val downloadPreferences: DownloadPreferences = Injekt.get()
    private val sourceManager: SourceManager = Injekt.get()

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification = applicationContext.notificationBuilder(Notifications.CHANNEL_DOWNLOADER_PROGRESS) {
            setContentTitle(applicationContext.getString(R.string.download_notifier_downloader_title))
            setSmallIcon(android.R.drawable.stat_sys_download)
            setColor(ContextCompat.getColor(applicationContext, R.color.ic_launcher))
            setLargeIcon(BitmapFactory.decodeResource(context.resources, R.drawable.komikku))
        }.build()
        return ForegroundInfo(
            Notifications.ID_DOWNLOAD_CHAPTER_PROGRESS,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
    }

    override suspend fun doWork(): Result {
        // KMK: the restored queue drops every download whose source has not loaded yet
        sourceManager.isInitialized.first { it }

        val workerJob = currentCoroutineContext().job
        synchronized(session.lock) {
            if (isStopped || !session.attach(id, workerJob)) return Result.success()
        }
        val waitingForNetwork = AtomicBoolean()

        fun pauseForNetwork(status: DownloadNetworkStatus) {
            val reason = when (status) {
                DownloadNetworkStatus.NoWifi -> applicationContext.getString(R.string.download_notifier_text_only_wifi)
                DownloadNetworkStatus.NoNetwork -> applicationContext.getString(R.string.download_notifier_no_network)
                DownloadNetworkStatus.Available -> return
            }
            waitingForNetwork.set(downloadManager.queueState.value.isNotEmpty())
            downloadManager.downloaderPauseForNetwork(reason)
        }

        fun handleNetworkStatus(status: DownloadNetworkStatus, allowStart: Boolean) {
            synchronized(session.lock) {
                if (isStopped || !session.isActive(workerJob) || downloadManager.queueState.value.isEmpty()) return

                when (status) {
                    DownloadNetworkStatus.Available -> {
                        if (waitingForNetwork.get() || allowStart) {
                            downloadManager.downloaderStart()
                            waitingForNetwork.set(false)
                        }
                    }
                    DownloadNetworkStatus.NoNetwork,
                    DownloadNetworkStatus.NoWifi,
                    -> pauseForNetwork(status)
                }
            }
        }

        try {
            downloadManager.awaitQueueRestored()
            if (downloadManager.queueState.value.isEmpty()) return Result.success()

            setForegroundSafely()

            val initialNetworkStatus = applicationContext.activeNetworkState()
                .toDownloadNetworkStatus(downloadPreferences.downloadOnlyOverWifi().get())
            handleNetworkStatus(initialNetworkStatus, allowStart = true)

            if (!downloadManager.isRunning && !waitingForNetwork.get()) {
                return Result.failure()
            }

            coroutineScope {
                val networkStatusJob = combine(
                    applicationContext.networkStateFlow()
                        .onStart { emit(applicationContext.activeNetworkState()) },
                    downloadPreferences.downloadOnlyOverWifi().changes(),
                ) { networkState, requireWifi -> networkState.toDownloadNetworkStatus(requireWifi) }
                    .distinctUntilChanged()
                    .onEach { handleNetworkStatus(it, allowStart = false) }
                    .launchIn(this)

                try {
                    // KMK: polled; this loop used to spin a core flat out for as long as downloads ran
                    while (
                        !isStopped &&
                        session.isActive(workerJob) &&
                        downloadManager.queueState.value.isNotEmpty() &&
                        (downloadManager.isRunning || waitingForNetwork.get())
                    ) {
                        delay(POLL_MILLIS)
                    }
                } finally {
                    networkStatusJob.cancel()
                }
            }

            return Result.success()
        } finally {
            synchronized(session.lock) {
                if (session.owns(workerJob)) {
                    if (downloadManager.isRunning && downloadManager.queueState.value.isNotEmpty()) {
                        val latestNetworkStatus = applicationContext.activeNetworkState()
                            .toDownloadNetworkStatus(downloadPreferences.downloadOnlyOverWifi().get())
                        if (latestNetworkStatus == DownloadNetworkStatus.Available) {
                            downloadManager.downloaderPause()
                        } else {
                            pauseForNetwork(latestNetworkStatus)
                        }
                    }
                    session.detach(workerJob)
                }
            }
        }
    }

    companion object {
        private const val TAG = "Downloader"
        private const val POLL_MILLIS = 1_000L

        internal val session = DownloadWorkerSession()

        fun start(context: Context): Unit = synchronized(session.lock) {
            val request = OneTimeWorkRequestBuilder<DownloadJob>()
                .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
                .addTag(TAG)
                .build()
            session.request(request.id)
            WorkManager.getInstance(context)
                .enqueueUniqueWork(TAG, ExistingWorkPolicy.REPLACE, request)
        }

        fun stop(context: Context): Unit = synchronized(session.lock) {
            session.stop()
            WorkManager.getInstance(context)
                .cancelUniqueWork(TAG)
        }

        fun isRunning(context: Context): Boolean {
            return WorkManager.getInstance(context)
                .getWorkInfosForUniqueWork(TAG)
                .get()
                .let { list -> list.count { it.state == WorkInfo.State.RUNNING } == 1 }
        }

        fun isRunningFlow(context: Context): Flow<Boolean> {
            return WorkManager.getInstance(context)
                .getWorkInfosForUniqueWorkLiveData(TAG)
                .asFlow()
                .map { list -> list.count { it.state == WorkInfo.State.RUNNING } == 1 }
        }

        /** Whether downloading was asked for and not yet finished, including while it waits for a network. */
        internal fun isRequestedFlow(context: Context): Flow<Boolean> {
            return WorkManager.getInstance(context)
                .getWorkInfosForUniqueWorkFlow(TAG)
                .map { list -> list.any { !it.state.isFinished } }
                .distinctUntilChanged()
        }
    }
}

internal sealed interface DownloadNetworkStatus {
    data object Available : DownloadNetworkStatus
    data object NoNetwork : DownloadNetworkStatus
    data object NoWifi : DownloadNetworkStatus
}

internal fun NetworkState.toDownloadNetworkStatus(requireWifi: Boolean): DownloadNetworkStatus {
    return when {
        !isOnline -> DownloadNetworkStatus.NoNetwork
        requireWifi && !isWifi -> DownloadNetworkStatus.NoWifi
        else -> DownloadNetworkStatus.Available
    }
}
