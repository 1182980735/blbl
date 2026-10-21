package blbl.cat3399.core.model

/**
 * 直播分区父类数据模型
 *
 * 表示直播分区的一级分类，包含多个子分区。
 */
data class LiveAreaParent(
    val id: Int,
    val name: String,
    val children: List<Child>,
) {
    /**
     * 直播子分区数据模型
     */
    data class Child(
        val id: Int,
        val parentId: Int,
        val name: String,
        val hot: Boolean,
        val coverUrl: String?,
    )
}

/**
 * 直播间卡片数据模型
 *
 * 表示单个直播间的基本信息，用于直播列表展示。
 */
data class LiveRoomCard(
    val roomId: Long,
    val uid: Long,
    val title: String,
    val uname: String,
    val coverUrl: String,
    val faceUrl: String?,
    val online: Long,
    val isLive: Boolean,
    val parentAreaId: Int?,
    val parentAreaName: String?,
    val areaId: Int?,
    val areaName: String?,
    val keyframe: String?,
)

