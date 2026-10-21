package blbl.cat3399.feature.player.engine

import android.view.Surface
import androidx.media3.common.MediaItem
import blbl.cat3399.feature.player.Playable

/**
 * 播放器引擎能力描述
 */
internal data class EngineCapabilities(
    /** 是否支持字幕 */
    val subtitlesSupported: Boolean,
)

/**
 * 播放源类型
 */
internal sealed interface PlaybackSource {
    /**
     * 点播视频源
     */
    data class Vod(
        /** 可播放的视频信息 */
        val playable: Playable,
        /** 字幕配置 */
        val subtitle: MediaItem.SubtitleConfiguration?,
        /** 视频时长（毫秒） */
        val durationMs: Long? = null,
        val initialPositionMs: Long? = null,
        val seamlessQualitySwitchEnabled: Boolean = false,
    ) : PlaybackSource

    /**
     * 直播视频源
     */
    data class Live(
        /** 直播流URL */
        val url: String,
    ) : PlaybackSource
}

/**
 * 播放器引擎接口，定义了统一的播放器控制API
 *
 * 实现包括：[ExoPlayerEngine] 和 [IjkPlayerEngine]
 */
internal interface BlblPlayerEngine {
    /** 引擎类型 */
    val kind: PlayerEngineKind

    /** 引擎能力 */
    val capabilities: EngineCapabilities

    /** 当前播放状态 */
    val playbackState: Int

    /** 是否正在播放 */
    val isPlaying: Boolean

    /** 是否准备好后自动播放 */
    var playWhenReady: Boolean

    /** 视频总时长（毫秒） */
    val duration: Long

    /** 当前播放位置（毫秒） */
    val currentPosition: Long

    /** 已缓冲位置（毫秒） */
    val bufferedPosition: Long

    /** 设置视频渲染表面 */
    fun setVideoSurface(surface: Surface?)

    /** 跳转到指定位置 */
    fun seekTo(positionMs: Long)

    /** 当前播放速度 */
    val playbackSpeed: Float

    /** 设置播放速度 */
    fun setPlaybackSpeed(speed: Float)

    /** 是否支持无缝切换清晰度 */
    val isSeamlessQualitySource: Boolean
        get() = false

    /** 切换视频清晰度（仅支持无缝切换的引擎实现） */
    fun selectVideoQuality(qn: Int): Boolean = false

    /** 循环播放模式 */
    var repeatMode: Int

    /** 设置播放源 */
    fun setSource(source: PlaybackSource)

    /** 准备播放器 */
    fun prepare()

    /** 开始播放 */
    fun play()

    /** 暂停播放 */
    fun pause()

    /** 停止播放 */
    fun stop()

    /** 释放播放器资源 */
    fun release()

    /** 添加播放监听器 */
    fun addListener(listener: Listener)

    /** 移除播放监听器 */
    fun removeListener(listener: Listener)

    /**
     * 播放事件监听器接口
     */
    interface Listener {
        /** 播放错误回调 */
        fun onPlayerError(error: Throwable) {}

        /** 播放状态变化回调 */
        fun onIsPlayingChanged(isPlaying: Boolean) {}

        /** 播放状态改变回调 */
        fun onPlaybackStateChanged(playbackState: Int) {}

        /** 播放位置不连续回调（如seek后） */
        fun onPositionDiscontinuity(newPositionMs: Long) {}

        /** 视频尺寸变化回调 */
        fun onVideoSizeChanged(width: Int, height: Int) {}

        /** 视频轨道变化回调（切换清晰度/编码时） */
        fun onVideoTrackChanged(qn: Int, codecid: Int) {}

        /** 首帧渲染完成回调 */
        fun onRenderedFirstFrame() {}
    }
}
