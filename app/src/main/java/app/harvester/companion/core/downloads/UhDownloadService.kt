package app.harvester.companion.core.downloads

import android.app.DownloadManager
import android.content.Intent
import app.harvester.companion.R
import app.harvester.companion.UHApp
import app.harvester.companion.UHLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import mozilla.components.browser.state.action.DownloadAction
import mozilla.components.browser.state.state.content.DownloadState
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.concept.fetch.Client
import mozilla.components.concept.fetch.MutableHeaders
import mozilla.components.concept.fetch.Request
import mozilla.components.feature.downloads.AbstractFetchDownloadService
import mozilla.components.feature.downloads.DefaultPackageNameProvider
import mozilla.components.feature.downloads.DownloadEstimator
import mozilla.components.feature.downloads.FileSizeFormatter
import mozilla.components.feature.downloads.PackageNameProvider
import mozilla.components.feature.downloads.filewriter.DownloadFileWriter
import mozilla.components.support.base.android.NotificationsDelegate
import mozilla.components.support.utils.DefaultDownloadFileUtils
import mozilla.components.support.utils.DownloadFileUtils
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

class UhDownloadService : AbstractFetchDownloadService() {
    override val httpClient: Client by lazy { (application as UHApp).components.client }
    override val store: BrowserStore by lazy { (application as UHApp).components.store }
    override val style: Style by lazy { Style(R.color.accent) }
    override val notificationsDelegate: NotificationsDelegate by lazy { (application as UHApp).components.notificationsDelegate }
    override val fileSizeFormatter: FileSizeFormatter by lazy { (application as UHApp).components.fileSizeFormatter }
    override val downloadEstimator: DownloadEstimator by lazy { (application as UHApp).components.downloadEstimator }
    override val packageNameProvider: PackageNameProvider by lazy {
        DefaultPackageNameProvider(applicationContext)
    }
    override val downloadFileUtils: DownloadFileUtils by lazy {
        DefaultDownloadFileUtils(
            context = applicationContext,
            downloadLocation = {
                DownloadLocationResolver.resolveTargetDirectory(applicationContext)
            },
        )
    }
    override val downloadFileWriter: DownloadFileWriter by lazy {
        UhDownloadFileWriter(applicationContext)
    }

    private val resumeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val knownActiveJobIds: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val downloadId = intent?.getStringExtra("downloadId")
            ?: intent?.getStringExtra(DownloadManager.EXTRA_DOWNLOAD_ID)
            ?: intent?.getStringExtra("INTENT_EXTRA_DOWNLOAD_ID")

        val action = intent?.action
        if (!downloadId.isNullOrBlank()) {
            // Ensure AbstractFetchDownloadService always sees DownloadManager.EXTRA_DOWNLOAD_ID
            intent?.putExtra(DownloadManager.EXTRA_DOWNLOAD_ID, downloadId)
            val download = store.state.downloads[downloadId]

            if ((action == ACTION_RESUME || action == ACTION_TRY_AGAIN) && download != null) {
                if (knownActiveJobIds.contains(downloadId)) {
                    // Forward to AbstractFetchDownloadService broadcastReceiver which resumes from offset in memory
                    val broadcast = Intent(ACTION_RESUME).apply {
                        setPackage(packageName)
                        putExtra("downloadId", downloadId)
                        putExtra(DownloadManager.EXTRA_DOWNLOAD_ID, downloadId)
                        putExtra("INTENT_EXTRA_DOWNLOAD_ID", downloadId)
                    }
                    sendBroadcast(broadcast)
                    return START_STICKY
                } else if (download.currentBytesCopied > 0L) {
                    // Cold-start Range resume preserving currentBytesCopied
                    knownActiveJobIds.add(downloadId)
                    resumeDownloadWithRange(downloadId)
                    return START_STICKY
                }
            }
            knownActiveJobIds.add(downloadId)
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun resumeDownloadWithRange(downloadId: String) {
        resumeScope.launch {
            val initial = store.state.downloads[downloadId] ?: return@launch
            val components = (application as UHApp).components
            val offset = initial.currentBytesCopied
            var latestState = initial.copy(status = DownloadState.Status.DOWNLOADING)
            store.dispatch(DownloadAction.UpdateDownloadAction(latestState))

            try {
                val headers = MutableHeaders()
                if (offset > 0L) {
                    headers.set("Range", "bytes=$offset-")
                }
                val response = httpClient.fetch(
                    Request(
                        url = initial.url,
                        method = Request.Method.GET,
                        headers = headers,
                    )
                )
                val isPartial = response.status == 206
                val append = offset > 0L && isPartial
                var totalCopied = if (append) offset else 0L
                val contentRangeLen = response.headers["Content-Length"]?.toLongOrNull()
                val totalLength = initial.contentLength ?: contentRangeLen?.let { if (append) offset + it else it }
                latestState = latestState.copy(
                    currentBytesCopied = totalCopied,
                    contentLength = totalLength,
                    status = DownloadState.Status.DOWNLOADING
                )
                store.dispatch(DownloadAction.UpdateDownloadAction(latestState))

                val shouldScoped = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q &&
                    !latestState.directoryPath.startsWith("content://")
                downloadFileWriter.useFileStream(
                    download = latestState,
                    append = append,
                    shouldUseScopedStorage = shouldScoped,
                    onUpdateState = { updated ->
                        latestState = updated
                        store.dispatch(DownloadAction.UpdateDownloadAction(updated))
                    },
                ) { outStream ->
                    response.body.useStream { input ->
                        val buffer = ByteArray(16 * 1024)
                        var lastUiUpdate = 0L
                        while (true) {
                            val currentStatus = store.state.downloads[downloadId]?.status
                            if (currentStatus == DownloadState.Status.PAUSED || currentStatus == DownloadState.Status.CANCELLED) {
                                return@useStream
                            }
                            if (!components.isNetworkAvailable()) {
                                components.markWaitingForNetwork(downloadId)
                                latestState = latestState.copy(
                                    currentBytesCopied = totalCopied,
                                    status = DownloadState.Status.PAUSED
                                )
                                store.dispatch(DownloadAction.UpdateDownloadAction(latestState))
                                return@useStream
                            }
                            val read = input.read(buffer)
                            if (read == -1) break
                            outStream.write(buffer, 0, read)
                            totalCopied += read
                            val now = System.currentTimeMillis()
                            if (now - lastUiUpdate >= 250L) {
                                lastUiUpdate = now
                                latestState = latestState.copy(
                                    currentBytesCopied = totalCopied,
                                    contentLength = totalLength,
                                    status = DownloadState.Status.DOWNLOADING
                                )
                                store.dispatch(DownloadAction.UpdateDownloadAction(latestState))
                            }
                        }
                    }
                }

                val currentAfter = store.state.downloads[downloadId] ?: latestState
                if (currentAfter.status == DownloadState.Status.DOWNLOADING) {
                    val finalTotal = totalLength ?: totalCopied
                    store.dispatch(
                        DownloadAction.UpdateDownloadAction(
                            currentAfter.copy(
                                currentBytesCopied = totalCopied,
                                contentLength = finalTotal,
                                status = DownloadState.Status.COMPLETED
                            )
                        )
                    )
                    components.clearWaitingForNetwork(downloadId)
                }
            } catch (e: Exception) {
                UHLog.w("download_warn", "Range resume interrupted for $downloadId: ${e.message}")
                val latest = store.state.downloads[downloadId] ?: latestState
                if (!components.isNetworkAvailable()) {
                    components.markWaitingForNetwork(downloadId)
                    store.dispatch(
                        DownloadAction.UpdateDownloadAction(
                            latest.copy(status = DownloadState.Status.PAUSED)
                        )
                    )
                } else if (latest.status != DownloadState.Status.PAUSED && latest.status != DownloadState.Status.CANCELLED) {
                    store.dispatch(
                        DownloadAction.UpdateDownloadAction(
                            latest.copy(status = DownloadState.Status.FAILED)
                        )
                    )
                }
            }
        }
    }
}
