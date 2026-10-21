package blbl.cat3399.core.io

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import blbl.cat3399.core.log.AppLog
import java.io.File
import java.nio.ByteBuffer

/**
 * 将 B 站 DASH 的音视频分离流（两个 fMP4 文件）合并成一个标准 MP4。
 *
 * B 站的 DASH 播放地址是音视频分离的：视频轨和音频轨各是一个 fMP4 文件。
 * 这里用 Android 自带的 MediaExtractor 读取两条轨的采样帧，再用 MediaMuxer
 * 写回一个 MP4 容器，避免引入 ffmpeg 等第三方依赖。
 */
internal object Mp4Remuxer {
    private const val TAG = "Mp4Remuxer"

    /** 单帧采样缓冲区大小；B 站 4K 视频的 I 帧通常远小于该值。 */
    private const val BUFFER_SIZE = 2 * 1024 * 1024

    /**
     * 合并音视频文件。
     *
     * @return true 表示合并成功且输出文件非空；false 表示该设备/该封装格式不支持。
     */
    fun merge(
        videoFile: File,
        audioFile: File,
        outputFile: File,
    ): Boolean {
        var muxer: MediaMuxer? = null
        var videoExtractor: MediaExtractor? = null
        var audioExtractor: MediaExtractor? = null
        return try {
            videoExtractor = MediaExtractor().apply { setDataSource(videoFile.absolutePath) }
            audioExtractor = MediaExtractor().apply { setDataSource(audioFile.absolutePath) }

            val videoTrackIndex = findTrack(videoExtractor, "video/")
            val audioTrackIndex = findTrack(audioExtractor, "audio/")
            if (videoTrackIndex < 0 || audioTrackIndex < 0) {
                AppLog.w(TAG, "merge aborted: missing track video=$videoTrackIndex audio=$audioTrackIndex")
                return false
            }
            videoExtractor.selectTrack(videoTrackIndex)
            audioExtractor.selectTrack(audioTrackIndex)

            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val outVideoTrack = muxer.addTrack(videoExtractor.getTrackFormat(videoTrackIndex))
            val outAudioTrack = muxer.addTrack(audioExtractor.getTrackFormat(audioTrackIndex))
            muxer.start()

            val buffer = ByteBuffer.allocateDirect(BUFFER_SIZE)
            val info = MediaCodec.BufferInfo()
            copySamples(videoExtractor, muxer, outVideoTrack, buffer, info)
            copySamples(audioExtractor, muxer, outAudioTrack, buffer, info)

            muxer.stop()
            outputFile.length() > 0L
        } catch (t: Throwable) {
            AppLog.w(TAG, "merge failed: ${t.message}", t)
            false
        } finally {
            // 未 start 时 stop 会抛异常，统一吞掉。
            runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { videoExtractor?.release() }
            runCatching { audioExtractor?.release() }
        }
    }

    private fun findTrack(
        extractor: MediaExtractor,
        mimePrefix: String,
    ): Int {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME).orEmpty()
            if (mime.startsWith(mimePrefix)) return i
        }
        return -1
    }

    private fun copySamples(
        extractor: MediaExtractor,
        muxer: MediaMuxer,
        trackIndex: Int,
        buffer: ByteBuffer,
        info: MediaCodec.BufferInfo,
    ) {
        while (true) {
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
            info.offset = 0
            info.size = size
            info.presentationTimeUs = extractor.sampleTime
            info.flags = extractor.sampleFlags
            muxer.writeSampleData(trackIndex, buffer, info)
            extractor.advance()
        }
    }
}
