package blbl.cat3399.feature.player

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import blbl.cat3399.core.api.video.VideoMediaRequestProfile
import blbl.cat3399.core.io.Mp4Remuxer
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.net.BiliClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * 视频下载管理器：下载 DASH 音视频两条流 → 合并为 MP4 → 保存到系统 Download/blbl 目录。
 *
 * 保存策略：
 * - Android 10（API 29）及以上：使用 MediaStore.Downloads，无需存储权限；
 * - Android 9 及以下：写入外部存储公共下载目录，调用方需先取得 WRITE_EXTERNAL_STORAGE 权限。
 */
internal object VideoDownloadManager {
    private const val TAG = "VideoDownload"
    private const val DOWNLOAD_DIR_NAME = "blbl"
    private const val BUFFER_SIZE = 64 * 1024
    private const val PROGRESS_INTERVAL_MS = 200L

    /** 一次下载所需的音视频地址与请求配置。 */
    data class Source(
        val videoUrl: String,
        val videoProfile: VideoMediaRequestProfile,
        val audioUrl: String,
        val audioProfile: VideoMediaRequestProfile,
        val title: String,
        val qnLabel: String,
    )

    /** 下载进度。 */
    sealed interface Progress {
        data object Preparing : Progress

        data class Downloading(
            val label: String,
            val percent: Int?,
            val downloadedBytes: Long,
            val totalBytes: Long?,
        ) : Progress

        data object Merging : Progress

        data object Saving : Progress
    }

    /** 下载结果；displayPath 用于提示用户，uri 在旧系统上可能为 null。 */
    data class Result(
        val displayPath: String,
        val uri: Uri?,
    )

    suspend fun download(
        context: Context,
        source: Source,
        onProgress: (Progress) -> Unit,
    ): Result =
        withContext(Dispatchers.IO) {
            onProgress(Progress.Preparing)
            val tempDir = File(context.cacheDir, "video_download").apply { mkdirs() }
            val stamp = System.currentTimeMillis()
            val videoFile = File(tempDir, "video_$stamp.m4s")
            val audioFile = File(tempDir, "audio_$stamp.m4s")
            val mergedFile = File(tempDir, "merged_$stamp.mp4")
            try {
                downloadStream(
                    url = source.videoUrl,
                    profile = source.videoProfile,
                    dest = videoFile,
                    label = "视频",
                ) { downloaded, total ->
                    onProgress(Progress.Downloading("下载视频", percentOf(downloaded, total), downloaded, total))
                }
                downloadStream(
                    url = source.audioUrl,
                    profile = source.audioProfile,
                    dest = audioFile,
                    label = "音频",
                ) { downloaded, total ->
                    onProgress(Progress.Downloading("下载音频", percentOf(downloaded, total), downloaded, total))
                }

                onProgress(Progress.Merging)
                if (!Mp4Remuxer.merge(videoFile, audioFile, mergedFile)) {
                    throw IOException("音视频合并失败（设备可能不支持该视频的封装格式）")
                }

                onProgress(Progress.Saving)
                saveToDownloads(context, mergedFile, buildFileName(source.title, source.qnLabel))
            } finally {
                runCatching { videoFile.delete() }
                runCatching { audioFile.delete() }
                runCatching { mergedFile.delete() }
            }
        }

    private suspend fun downloadStream(
        url: String,
        profile: VideoMediaRequestProfile,
        dest: File,
        label: String,
        onBytes: (downloaded: Long, total: Long?) -> Unit,
    ) {
        val client: OkHttpClient =
            if (profile == VideoMediaRequestProfile.APP) {
                BiliClient.appCdnOkHttp
            } else {
                BiliClient.cdnOkHttp
            }
        val request =
            Request
                .Builder()
                .url(url)
                // 媒体文件不能走 gzip，否则 content-length 与实际字节数对不上。
                .header("Accept-Encoding", "identity")
                .get()
                .build()

        val call = client.newCall(request)
        call.execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("$label 下载失败：HTTP ${response.code}")
            }
            val body = response.body ?: throw IOException("$label 下载失败：响应为空")
            val total = body.contentLength().takeIf { it > 0L }
            var downloaded = 0L
            var lastEmitAt = 0L
            body.byteStream().use { input ->
                FileOutputStream(dest).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        val now = System.currentTimeMillis()
                        if (now - lastEmitAt >= PROGRESS_INTERVAL_MS) {
                            lastEmitAt = now
                            onBytes(downloaded, total)
                        }
                    }
                    output.fd.sync()
                }
            }
            if (downloaded <= 0L) throw IOException("$label 下载失败：内容为空")
            onBytes(downloaded, total)
        }
    }

    private fun percentOf(
        downloaded: Long,
        total: Long?,
    ): Int? {
        val safeTotal = total?.takeIf { it > 0L } ?: return null
        return ((downloaded * 100L) / safeTotal).toInt().coerceIn(0, 100)
    }

    private fun buildFileName(
        title: String,
        qnLabel: String,
    ): String {
        val safeTitle =
            title
                .replace(Regex("[\\\\/:*?\"<>|\\r\\n]"), "_")
                .trim()
                .ifBlank { "video" }
                .take(80)
        val safeQn =
            qnLabel
                .replace(Regex("[\\\\/:*?\"<>|\\r\\n]"), "_")
                .trim()
                .ifBlank { "qn" }
        return "${safeTitle}_${safeQn}.mp4"
    }

    private fun saveToDownloads(
        context: Context,
        source: File,
        fileName: String,
    ): Result =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveViaMediaStore(context, source, fileName)
        } else {
            saveViaLegacyStorage(source, fileName)
        }

    private fun saveViaMediaStore(
        context: Context,
        source: File,
        fileName: String,
    ): Result {
        val resolver = context.contentResolver
        val values =
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + File.separator + DOWNLOAD_DIR_NAME,
                )
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val uri = resolver.insert(collection, values) ?: throw IOException("无法创建下载文件")
        try {
            resolver.openOutputStream(uri)?.use { output ->
                source.inputStream().use { input -> input.copyTo(output, BUFFER_SIZE) }
            } ?: throw IOException("无法写入下载文件")
            val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
        } catch (t: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw t
        }
        AppLog.i(TAG, "saved via MediaStore: Download/$DOWNLOAD_DIR_NAME/$fileName")
        return Result(displayPath = "Download/$DOWNLOAD_DIR_NAME/$fileName", uri = uri)
    }

    @Suppress("DEPRECATION")
    private fun saveViaLegacyStorage(
        source: File,
        fileName: String,
    ): Result {
        val dir =
            File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                DOWNLOAD_DIR_NAME,
            )
        if (!dir.exists() && !dir.mkdirs()) {
            throw IOException("无法创建 Download/$DOWNLOAD_DIR_NAME 目录")
        }
        val target = uniqueFile(dir, fileName)
        source.inputStream().use { input ->
            FileOutputStream(target).use { output -> input.copyTo(output, BUFFER_SIZE) }
        }
        AppLog.i(TAG, "saved via legacy storage: ${target.absolutePath}")
        return Result(displayPath = target.absolutePath, uri = null)
    }

    private fun uniqueFile(
        dir: File,
        fileName: String,
    ): File {
        val base = fileName.substringBeforeLast('.', fileName)
        val ext = fileName.substringAfterLast('.', "")
        var candidate = File(dir, fileName)
        var index = 1
        while (candidate.exists()) {
            val name = if (ext.isBlank()) "$base($index)" else "$base($index).$ext"
            candidate = File(dir, name)
            index++
        }
        return candidate
    }
}
