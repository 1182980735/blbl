package blbl.cat3399.feature.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import blbl.cat3399.core.api.video.VideoMediaRequestProfile
import blbl.cat3399.core.download.DownloadTask
import blbl.cat3399.core.download.DownloadTaskStore
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.ui.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * 前台下载服务。
 *
 * Activity 侧只负责解析播放地址，把音视频 URL 通过 Intent 交给本服务；
 * 服务在前台串行执行下载（下载 → 合并 → 保存到 Download/blbl），
 * 并在通知栏展示进度。退出播放器后任务继续执行。
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = Channel<DownloadJob>(Channel.UNLIMITED)
    private val cancelRequested = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var activeJob: DownloadJob? = null

    @Volatile
    private var lastNotifyAtMs: Long = 0L

    override fun onCreate() {
        super.onCreate()
        DownloadTaskStore.init(this)
        createNotificationChannel()
        startForegroundSafely(buildRunningNotification(job = null, percent = null, queued = 0))
        scope.launch {
            for (job in queue) {
                runJob(job)
            }
        }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        when (intent?.action) {
            ACTION_ENQUEUE -> enqueue(intent)
            ACTION_CANCEL -> cancelTask(intent.getStringExtra(EXTRA_TASK_ID))
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun enqueue(intent: Intent) {
        val job = parseJob(intent)
        if (job == null) {
            AppLog.w(TAG, "enqueue ignored: invalid intent")
            return
        }
        DownloadTaskStore.upsert(
            DownloadTask(
                id = job.taskId,
                bvid = job.bvid,
                cid = job.cid,
                epId = job.epId,
                title = job.title,
                qn = job.qn,
                qnLabel = job.qnLabel,
                status = DownloadTask.Status.QUEUED,
                progress = -1,
                stageText = "等待下载…",
                savedPath = null,
                savedUri = null,
                error = null,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
            ),
        )
        queue.trySend(job)
        notifyRunning(job = activeJob, percent = null, force = true)
        AppLog.i(TAG, "enqueued task=${job.taskId} title=${job.title.take(40)}")
    }

    private fun cancelTask(taskId: String?) {
        val id = taskId?.trim().orEmpty()
        if (id.isBlank()) return
        cancelRequested.add(id)
        DownloadTaskStore.update(id) { current ->
            if (current.isFinished) current else current.copy(status = DownloadTask.Status.CANCELED, stageText = "已取消", progress = -1)
        }
        AppLog.i(TAG, "cancel requested task=$id")
    }

    private suspend fun runJob(job: DownloadJob) {
        if (cancelRequested.remove(job.taskId)) {
            markCanceled(job.taskId)
            return
        }
        activeJob = job
        try {
            DownloadTaskStore.update(job.taskId) { current ->
                current.copy(status = DownloadTask.Status.DOWNLOADING, progress = 0, stageText = "准备下载…", error = null)
            }
            notifyRunning(job = job, percent = 0, force = true)

            val saved =
                VideoDownloadManager.download(
                    context = this,
                    source =
                        VideoDownloadManager.Source(
                            videoUrl = job.videoUrl,
                            videoProfile = job.videoProfile,
                            audioUrl = job.audioUrl,
                            audioProfile = job.audioProfile,
                            title = job.title,
                            qnLabel = job.qnLabel,
                        ),
                ) { progress ->
                    if (cancelRequested.contains(job.taskId)) {
                        throw CancellationException("canceled")
                    }
                    applyProgress(job, progress)
                }

            DownloadTaskStore.update(job.taskId) { current ->
                current.copy(
                    status = DownloadTask.Status.COMPLETED,
                    progress = 100,
                    stageText = "已完成",
                    savedPath = saved.displayPath,
                    savedUri = saved.uri?.toString(),
                    error = null,
                )
            }
            showFinishedNotification(
                title = "下载完成",
                text = "${job.title} → ${saved.displayPath}",
                failed = false,
            )
            AppLog.i(TAG, "task completed task=${job.taskId} path=${saved.displayPath}")
        } catch (_: CancellationException) {
            markCanceled(job.taskId)
        } catch (t: Throwable) {
            AppLog.w(TAG, "task failed task=${job.taskId} ${t.message}", t)
            DownloadTaskStore.update(job.taskId) { current ->
                current.copy(
                    status = DownloadTask.Status.FAILED,
                    progress = -1,
                    stageText = "下载失败",
                    error = t.message ?: "未知错误",
                )
            }
            showFinishedNotification(
                title = "下载失败",
                text = "${job.title}：${t.message ?: "未知错误"}",
                failed = true,
            )
        } finally {
            activeJob = null
            cancelRequested.remove(job.taskId)
            notifyRunning(job = null, percent = null, force = true)
            maybeStopSelf()
        }
    }

    private fun markCanceled(taskId: String) {
        DownloadTaskStore.update(taskId) { current ->
            current.copy(status = DownloadTask.Status.CANCELED, progress = -1, stageText = "已取消")
        }
        AppLog.i(TAG, "task canceled task=$taskId")
    }

    private fun applyProgress(
        job: DownloadJob,
        progress: VideoDownloadManager.Progress,
    ) {
        val status =
            when (progress) {
                VideoDownloadManager.Progress.Preparing,
                is VideoDownloadManager.Progress.Downloading,
                -> DownloadTask.Status.DOWNLOADING

                VideoDownloadManager.Progress.Merging -> DownloadTask.Status.MERGING
                VideoDownloadManager.Progress.Saving -> DownloadTask.Status.SAVING
            }
        val percent =
            when (progress) {
                is VideoDownloadManager.Progress.Downloading -> progress.percent ?: -1
                else -> -1
            }
        val text =
            when (progress) {
                VideoDownloadManager.Progress.Preparing -> "准备下载…"
                is VideoDownloadManager.Progress.Downloading -> {
                    val pct = progress.percent?.let { " $it%" }.orEmpty()
                    "${progress.label}…$pct"
                }

                VideoDownloadManager.Progress.Merging -> "合并音视频…"
                VideoDownloadManager.Progress.Saving -> "保存中…"
            }
        DownloadTaskStore.update(job.taskId) { current ->
            current.copy(status = status, progress = percent, stageText = text)
        }
        notifyRunning(job = job, percent = percent.takeIf { it >= 0 }, force = false)
    }

    private fun maybeStopSelf() {
        if (activeJob == null && queue.isEmpty) {
            stopForegroundCompat()
            stopSelf()
        }
    }

    // ---- 通知 ----

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel =
            NotificationChannel(CHANNEL_ID, "视频下载", NotificationManager.IMPORTANCE_LOW).apply {
                description = "视频下载进度"
                setShowBadge(false)
            }
        manager.createNotificationChannel(channel)
    }

    private fun startForegroundSafely(notification: Notification) {
        runCatching { startForeground(NOTIFICATION_ID, notification) }
            .onFailure { AppLog.w(TAG, "startForeground failed", it) }
    }

    private fun stopForegroundCompat() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        }
    }

    private fun notifyRunning(
        job: DownloadJob?,
        percent: Int?,
        force: Boolean,
    ) {
        val now = System.currentTimeMillis()
        if (!force && now - lastNotifyAtMs < NOTIFY_INTERVAL_MS) return
        lastNotifyAtMs = now
        val running = activeJob
        val notification =
            buildRunningNotification(
                job = job ?: running,
                percent = percent,
                queued = if (queue.isEmpty) 0 else 1,
            )
        runCatching {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, notification)
        }
    }

    private fun buildRunningNotification(
        job: DownloadJob?,
        percent: Int?,
        queued: Int,
    ): Notification {
        val title = job?.title?.take(40) ?: "blbl 视频下载"
        val text =
            when {
                job == null -> "等待下载任务…"
                percent != null -> "下载中… $percent%"
                else -> "准备下载…"
            }
        val builder =
            NotificationCompat
                .Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(contentPendingIntent())
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setProgress(100, percent ?: 0, percent == null)
        if (queued > 0) {
            builder.setSubText("队列中还有任务")
        }
        if (job != null) {
            builder.addAction(
                0,
                "取消",
                servicePendingIntent(ACTION_CANCEL, job.taskId),
            )
        }
        return builder.build()
    }

    private fun showFinishedNotification(
        title: String,
        text: String,
        failed: Boolean,
    ) {
        val notification =
            NotificationCompat
                .Builder(this, CHANNEL_ID)
                .setSmallIcon(
                    if (failed) android.R.drawable.stat_notify_error else android.R.drawable.stat_sys_download_done,
                )
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(contentPendingIntent())
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
        runCatching {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(FINISHED_NOTIFICATION_ID, notification)
        }
    }

    private fun contentPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(this, 0, intent, pendingIntentFlags())
    }

    private fun servicePendingIntent(
        action: String,
        taskId: String,
    ): PendingIntent {
        val intent =
            Intent(this, DownloadService::class.java).apply {
                this.action = action
                putExtra(EXTRA_TASK_ID, taskId)
            }
        return PendingIntent.getService(this, taskId.hashCode(), intent, pendingIntentFlags())
    }

    private fun pendingIntentFlags(): Int =
        PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0

    private fun parseJob(intent: Intent): DownloadJob? {
        val videoUrl = intent.getStringExtra(EXTRA_VIDEO_URL).orEmpty()
        val audioUrl = intent.getStringExtra(EXTRA_AUDIO_URL).orEmpty()
        val taskId = intent.getStringExtra(EXTRA_TASK_ID).orEmpty()
        if (videoUrl.isBlank() || audioUrl.isBlank() || taskId.isBlank()) return null
        return DownloadJob(
            taskId = taskId,
            bvid = intent.getStringExtra(EXTRA_BVID).orEmpty(),
            cid = intent.getLongExtra(EXTRA_CID, -1L),
            epId = intent.getLongExtra(EXTRA_EP_ID, -1L).takeIf { it > 0L },
            title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "video" },
            qn = intent.getIntExtra(EXTRA_QN, 0),
            qnLabel = intent.getStringExtra(EXTRA_QN_LABEL).orEmpty().ifBlank { "qn" },
            videoUrl = videoUrl,
            videoProfile = profileOf(intent.getStringExtra(EXTRA_VIDEO_PROFILE)),
            audioUrl = audioUrl,
            audioProfile = profileOf(intent.getStringExtra(EXTRA_AUDIO_PROFILE)),
        )
    }

    private fun profileOf(raw: String?): VideoMediaRequestProfile =
        if (raw == VideoMediaRequestProfile.APP.name) VideoMediaRequestProfile.APP else VideoMediaRequestProfile.WEB

    private data class DownloadJob(
        val taskId: String,
        val bvid: String,
        val cid: Long,
        val epId: Long?,
        val title: String,
        val qn: Int,
        val qnLabel: String,
        val videoUrl: String,
        val videoProfile: VideoMediaRequestProfile,
        val audioUrl: String,
        val audioProfile: VideoMediaRequestProfile,
    )

    /** 下载任务请求：由 Activity 解析播放地址后构造。 */
    data class Request(
        val taskId: String,
        val bvid: String,
        val cid: Long,
        val epId: Long?,
        val title: String,
        val qn: Int,
        val qnLabel: String,
        val videoUrl: String,
        val videoProfile: VideoMediaRequestProfile,
        val audioUrl: String,
        val audioProfile: VideoMediaRequestProfile,
    )

    companion object {
        private const val TAG = "DownloadService"
        private const val CHANNEL_ID = "blbl_video_download"
        private const val NOTIFICATION_ID = 3001
        private const val FINISHED_NOTIFICATION_ID = 3002
        private const val NOTIFY_INTERVAL_MS = 400L

        private const val ACTION_ENQUEUE = "blbl.download.ENQUEUE"
        private const val ACTION_CANCEL = "blbl.download.CANCEL"
        private const val EXTRA_TASK_ID = "task_id"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_BVID = "bvid"
        private const val EXTRA_CID = "cid"
        private const val EXTRA_EP_ID = "ep_id"
        private const val EXTRA_QN_LABEL = "qn_label"
        private const val EXTRA_QN = "qn"
        private const val EXTRA_VIDEO_URL = "video_url"
        private const val EXTRA_VIDEO_PROFILE = "video_profile"
        private const val EXTRA_AUDIO_URL = "audio_url"
        private const val EXTRA_AUDIO_PROFILE = "audio_profile"

        /**
         * 把一条已解析好地址的下载任务交给前台服务。
         */
        fun enqueue(
            context: Context,
            request: Request,
        ) {
            val intent =
                Intent(context, DownloadService::class.java).apply {
                    action = ACTION_ENQUEUE
                    putExtra(EXTRA_TASK_ID, request.taskId)
                    putExtra(EXTRA_TITLE, request.title)
                    putExtra(EXTRA_BVID, request.bvid)
                    putExtra(EXTRA_CID, request.cid)
                    putExtra(EXTRA_EP_ID, request.epId ?: -1L)
                    putExtra(EXTRA_QN_LABEL, request.qnLabel)
                    putExtra(EXTRA_QN, request.qn)
                    putExtra(EXTRA_VIDEO_URL, request.videoUrl)
                    putExtra(EXTRA_VIDEO_PROFILE, request.videoProfile.name)
                    putExtra(EXTRA_AUDIO_URL, request.audioUrl)
                    putExtra(EXTRA_AUDIO_PROFILE, request.audioProfile.name)
                }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun cancel(
            context: Context,
            taskId: String,
        ) {
            val intent =
                Intent(context, DownloadService::class.java).apply {
                    action = ACTION_CANCEL
                    putExtra(EXTRA_TASK_ID, taskId)
                }
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }

    }
}
