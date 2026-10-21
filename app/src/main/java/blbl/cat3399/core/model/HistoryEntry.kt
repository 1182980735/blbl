package blbl.cat3399.core.model

/**
 * 历史记录条目密封接口
 *
 * 定义历史记录的统一接口，支持视频和直播两种类型。
 */
sealed interface HistoryEntry {
    /**
     * 获取稳定的唯一标识键
     */
    fun stableKey(): String

    /**
     * 视频历史记录条目
     */
    data class Video(
        val card: VideoCard,
    ) : HistoryEntry {
        override fun stableKey(): String = card.stableKey()
    }

    /**
     * 直播历史记录条目
     */
    data class Live(
        val card: LiveRoomCard,
    ) : HistoryEntry {
        override fun stableKey(): String = "live:${card.roomId}"
    }
}
