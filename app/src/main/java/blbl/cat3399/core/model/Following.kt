package blbl.cat3399.core.model

/**
 * 关注用户数据模型
 *
 * 表示用户关注列表中的一个UP主，包含基本信息和直播状态。
 */
data class Following(
    val mid: Long,
    val name: String,
    val avatarUrl: String?,
    val sign: String? = null,
    val isLive: Boolean = false,
    val liveRoomId: Long = 0L,
)
