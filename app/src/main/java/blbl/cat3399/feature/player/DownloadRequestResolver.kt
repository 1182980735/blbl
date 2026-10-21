package blbl.cat3399.feature.player

import blbl.cat3399.core.api.BiliApi
import blbl.cat3399.core.api.video.AudioTrack
import blbl.cat3399.core.api.video.VideoAudioKind
import blbl.cat3399.core.api.video.VideoPlayKind
import blbl.cat3399.core.api.video.VideoPlayRequest
import blbl.cat3399.core.api.video.VideoPlayStream
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.net.BiliClient
import java.io.IOException
import java.util.UUID

/**
 * 下载任务地址解析器（不依赖播放器界面）。
 *
 * 下载记录页“重试”时用它把 bvid/cid/qn 重新解析成音视频地址，
 * 请求参数与播放器内的下载入口保持一致（最高清晰度 + 全部 DASH 能力 + try_look 兜底）。
 */
internal object DownloadRequestResolver {
    private const val TAG = "DownloadResolver"

    suspend fun resolve(
        bvid: String,
        cid: Long,
        epId: Long?,
        qn: Int,
        title: String,
    ): DownloadService.Request {
        val stream =
            requestStream(
                bvid = bvid,
                cid = cid,
                epId = epId,
                fnval = buildFnval(),
                preferCodec = BiliClient.prefs.playerPreferredCodec,
            )
        val dash = stream.dash ?: throw IOException("没有可用的 DASH 播放流")
        val videoTrack =
            dash.videos
                .filter { it.qn == qn && it.urls.isNotEmpty() }
                .maxByOrNull { it.info.bandwidth ?: 0L }
                ?: throw IOException("没有 ${qnLabel(qn)} 清晰度的视频流")
        val audioTrack = pickAudioTrack(dash.audios)
        return DownloadService.Request(
            taskId = UUID.randomUUID().toString(),
            bvid = bvid,
            cid = cid,
            epId = epId,
            title = title.ifBlank { bvid },
            qn = qn,
            qnLabel = qnLabel(qn),
            videoUrl = videoTrack.urls.first(),
            videoProfile = videoTrack.mediaRequestProfile,
            audioUrl = audioTrack.urls.first(),
            audioProfile = audioTrack.mediaRequestProfile,
        )
    }

    private suspend fun requestStream(
        bvid: String,
        cid: Long,
        epId: Long?,
        fnval: Int,
        preferCodec: String,
    ): VideoPlayStream {
        fun request(tryLook: Boolean): VideoPlayRequest =
            VideoPlayRequest(
                kind = if (epId != null) VideoPlayKind.PGC else VideoPlayKind.UGC,
                bvid = bvid.trim().takeIf { it.isNotBlank() },
                aid = null,
                cid = cid,
                epId = epId,
                qn = 127,
                fnval = fnval,
                tryLook = tryLook,
                preferCodec = preferCodec,
            )

        val primary = BiliApi.playUrl(request(tryLook = false))
        if (primary.hasPlayableStream()) return primary
        AppLog.w(TAG, "primary stream empty, retry with try_look bvid=$bvid cid=$cid")
        return runCatching { BiliApi.playUrl(request(tryLook = true)) }.getOrDefault(primary)
    }

    private fun pickAudioTrack(audios: List<AudioTrack>): AudioTrack {
        val usable = audios.filter { it.urls.isNotEmpty() }
        val preferred = BiliClient.prefs.playerPreferredAudioId
        return usable.firstOrNull { it.id == preferred }
            ?: usable.filter { it.kind == VideoAudioKind.NORMAL }.maxByOrNull { it.info.bandwidth ?: 0L }
            ?: usable.maxByOrNull { it.info.bandwidth ?: 0L }
            ?: throw IOException("没有可用的音频流")
    }

    /** 请求全部 DASH 能力（4K / 8K / HDR / 杜比），与播放器内的请求参数保持一致。 */
    private fun buildFnval(): Int {
        var fnval = 4048
        fnval = fnval or 128
        fnval = fnval or 1024
        fnval = fnval or 64
        fnval = fnval or 512
        return fnval
    }
}
