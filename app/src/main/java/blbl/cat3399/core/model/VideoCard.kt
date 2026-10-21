package blbl.cat3399.core.model

/**
 * 视频卡片数据模型
 *
 * 用于表示视频列表中的单个视频项，包含视频基本信息、封面、播放数据等。
 * 支持普通视频、番剧(PVC)、电视剧等多种类型。
 */
data class VideoCard(
    val bvid: String,
    val cid: Long?,
    val aid: Long? = null,
    val epId: Long? = null,
    val business: String? = null,
    val subType: Int? = null,
    val title: String,
    val coverUrl: String,
    val durationSec: Int,
    val ownerName: String,
    val ownerFace: String?,
    val ownerMid: Long? = null,
    val view: Long?,
    val danmaku: Long?,
    val pubDate: Long?,
    val pubDateText: String?,
    val isChargingArc: Boolean = false,
    val coverLeftBottomText: String? = null,
    val accessBadgeText: String? = null,
    val seasonId: Long? = null,
    val progressSec: Long? = null,
    val progressFinished: Boolean = false,
    val trackId: String? = null,
) {
    /**
     * 生成稳定的唯一标识键
     *
     * 按优先级依次使用bvid、epId、seasonId、aid、cid，最后使用title哈希。
     * 用于RecyclerView的DiffUtil和列表去重。
     *
     * @return 稳定的唯一标识字符串
     */
    fun stableKey(): String =
        when {
            bvid.isNotBlank() -> "bvid:$bvid"
            epId != null && epId > 0 -> "ep:$epId"
            seasonId != null && seasonId > 0 -> "sid:$seasonId"
            aid != null && aid > 0 -> "aid:$aid"
            cid != null && cid > 0 -> "cid:$cid"
            else -> "title:${title.hashCode()}"
        }
}
