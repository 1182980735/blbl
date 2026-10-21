package blbl.cat3399.feature.player

import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import blbl.cat3399.core.download.DownloadTask
import blbl.cat3399.core.download.DownloadTaskStore
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.ui.AppToast
import blbl.cat3399.core.ui.BaseActivity
import blbl.cat3399.core.ui.Immersive
import blbl.cat3399.core.ui.cloneInUserScale
import blbl.cat3399.core.ui.popup.AppPopup
import blbl.cat3399.core.ui.popup.PopupAction
import blbl.cat3399.core.ui.popup.PopupActionRole
import blbl.cat3399.core.ui.popup.PopupHandle
import blbl.cat3399.databinding.ActivityDownloadsBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

/**
 * 下载记录页：展示历史/进行中的下载任务，支持打开、取消、重命名、删除。
 */
class DownloadsActivity : BaseActivity() {
    private lateinit var binding: ActivityDownloadsBinding
    private lateinit var adapter: DownloadRecordAdapter
    private var retryJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDownloadsBinding.inflate(layoutInflater.cloneInUserScale(this))
        setContentView(binding.root)
        Immersive.apply(this, BiliClient.prefs.fullscreenEnabled)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnClearFinished.setOnClickListener {
            DownloadTaskStore.clearFinished()
            AppToast.show(this, "已清空已完成记录")
            refresh()
        }

        adapter =
            DownloadRecordAdapter(
                onClick = { task -> onTaskClick(task) },
                onLongClick = { task -> showTaskActions(task) },
            )
        binding.recyclerDownloads.layoutManager = LinearLayoutManager(this)
        binding.recyclerDownloads.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        refresh()
        binding.recyclerDownloads.post { binding.recyclerDownloads.requestFocus() }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && isBackLikeKey(event.keyCode)) {
            finish()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun isBackLikeKey(keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_BACK ||
            keyCode == KeyEvent.KEYCODE_ESCAPE ||
            keyCode == KeyEvent.KEYCODE_BUTTON_B

    private fun refresh() {
        val tasks = DownloadTaskStore.all()
        adapter.submit(tasks)
        binding.tvEmpty.visibility = if (tasks.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun onTaskClick(task: DownloadTask) {
        when {
            task.isRunning -> {
                DownloadService.cancel(this, task.id)
                AppToast.show(this, "已请求取消")
            }

            task.status == DownloadTask.Status.COMPLETED -> openTask(task)
            else -> showTaskActions(task)
        }
    }

    private fun openTask(task: DownloadTask) {
        val uri = resolveTaskUri(task)
        if (uri == null) {
            AppToast.show(this, "找不到文件，可能已被删除")
            return
        }
        val intent =
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "video/mp4")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        runCatching { startActivity(intent) }
            .onFailure { AppToast.show(this, "没有可用的视频播放器") }
    }

    private fun resolveTaskUri(task: DownloadTask): Uri? {
        task.savedUri?.takeIf { it.isNotBlank() }?.let { return Uri.parse(it) }
        val path = task.savedPath?.takeIf { it.isNotBlank() } ?: return null
        val file = File(path)
        if (!file.exists()) return null
        return runCatching {
            FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        }.getOrNull()
    }

    private fun showTaskActions(task: DownloadTask) {
        val actions = mutableListOf<String>()
        if (task.status == DownloadTask.Status.COMPLETED) {
            actions += "打开"
            actions += "重命名"
        }
        if (task.isRunning) actions += "取消下载"
        if ((task.status == DownloadTask.Status.FAILED || task.status == DownloadTask.Status.CANCELED) && task.qn > 0) {
            actions += "重试"
        }
        actions += "删除"
        AppPopup.singleChoice(
            context = this,
            title = task.title.ifBlank { task.bvid },
            items = actions,
            checkedIndex = 0,
        ) { which, _ ->
            when (actions.getOrNull(which)) {
                "打开" -> openTask(task)
                "重命名" -> showRenameDialog(task)
                "取消下载" -> {
                    DownloadService.cancel(this, task.id)
                    AppToast.show(this, "已请求取消")
                }

                "重试" -> retryTask(task)
                "删除" -> deleteTask(task)
            }
        }
    }

    /** 重试失败/已取消的任务：重新解析播放地址后入队。 */
    private fun retryTask(task: DownloadTask) {
        if (retryJob?.isActive == true) {
            AppToast.show(this, "正在解析播放地址，请稍候")
            return
        }
        if (task.qn <= 0) {
            AppToast.show(this, "缺少清晰度信息，无法重试")
            return
        }
        var job: Job? = null
        val popup =
            AppPopup.progress(
                context = this,
                title = "重试下载",
                status = "获取播放地址…",
                negativeText = "取消",
                cancelable = false,
                onNegative = { job?.cancel() },
            )
        job =
            lifecycleScope.launch {
                try {
                    val request =
                        DownloadRequestResolver.resolve(
                            bvid = task.bvid,
                            cid = task.cid,
                            epId = task.epId,
                            qn = task.qn,
                            title = task.title,
                        )
                    DownloadService.enqueue(this@DownloadsActivity, request)
                    popup?.dismiss()
                    AppToast.showLong(this@DownloadsActivity, "已重新加入下载队列")
                    refresh()
                } catch (e: CancellationException) {
                    popup?.dismiss()
                } catch (t: Throwable) {
                    AppLog.w("Downloads", "retry failed", t)
                    popup?.dismiss()
                    AppToast.showLong(this@DownloadsActivity, "重试失败：${t.message ?: "未知错误"}")
                }
            }
        retryJob = job
    }

    private fun showRenameDialog(task: DownloadTask) {
        var input: EditText? = null
        var handle: PopupHandle? = null

        fun confirm() {
            val raw = input?.text?.toString()?.trim().orEmpty()
            if (raw.isBlank()) {
                AppToast.show(this, "名称不能为空")
                return
            }
            renameTask(task, raw)
            handle?.dismiss()
        }

        handle =
            AppPopup.custom(
                context = this,
                title = "重命名",
                cancelable = true,
                actions =
                    listOf(
                        PopupAction(role = PopupActionRole.NEGATIVE, text = "取消"),
                        PopupAction(role = PopupActionRole.POSITIVE, text = "确定") { confirm() },
                    ),
                preferredActionRole = PopupActionRole.POSITIVE,
            ) { dialogContext ->
                EditText(dialogContext).apply {
                    setSingleLine(true)
                    setText(File(task.savedPath.orEmpty()).nameWithoutExtension)
                    setSelection(text?.length ?: 0)
                    input = this
                }
            }
    }

    private fun renameTask(
        task: DownloadTask,
        rawName: String,
    ) {
        val displayName = if (rawName.endsWith(".mp4", ignoreCase = true)) rawName else "$rawName.mp4"
        val uri = task.savedUri?.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }
        var renamed = false
        if (uri != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            renamed =
                runCatching {
                    contentResolver.update(
                        uri,
                        ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, displayName) },
                        null,
                        null,
                    ) > 0
                }.getOrDefault(false)
        } else {
            val oldPath = task.savedPath?.takeIf { it.isNotBlank() }
            if (oldPath != null) {
                val oldFile = File(oldPath)
                if (oldFile.exists()) {
                    renamed = runCatching { oldFile.renameTo(File(oldFile.parentFile, displayName)) }.getOrDefault(false)
                }
            }
        }
        if (!renamed) {
            AppToast.show(this, "重命名失败")
            return
        }
        val newPath =
            task.savedPath?.let { path ->
                val parent = path.substringBeforeLast('/', missingDelimiterValue = "")
                if (parent.isBlank()) displayName else "$parent/$displayName"
            }
        DownloadTaskStore.update(task.id) { it.copy(savedPath = newPath) }
        AppToast.show(this, "已重命名")
        refresh()
    }

    private fun deleteTask(task: DownloadTask) {
        val uri = task.savedUri?.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }
        if (uri != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { contentResolver.delete(uri, null, null) }
                .onFailure { AppLog.w("Downloads", "delete via MediaStore failed", it) }
        } else {
            task.savedPath?.takeIf { it.isNotBlank() }?.let { path ->
                runCatching { File(path).delete() }
            }
        }
        DownloadTaskStore.remove(task.id)
        AppToast.show(this, "已删除")
        refresh()
    }
}
