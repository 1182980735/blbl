package blbl.cat3399.core.model

/**
 * 视频分区数据模型
 *
 * 表示B站视频的分区信息，用于筛选和分类视频。
 */
data class Zone(
    val title: String,
    val rid: Int?, // null表示全站
)
