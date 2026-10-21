package blbl.cat3399.core.model

/**
 * 视频标签数据模型
 *
 * 表示视频的标签信息，支持视频分类和内容推荐。
 */
data class VideoTag(
    val tagId: Long,
    val tagName: String,
    val tagType: String? = null,
    val jumpUrl: String? = null,
    val musicId: String? = null,
) {
    /**
     * 生成稳定的唯一标识键
     *
     * 按优先级依次使用tagId和tagName哈希。
     *
     * @return 稳定的唯一标识字符串
     */
    fun stableKey(): String =
        when {
            tagId > 0L -> "tag:$tagId"
            tagName.isNotBlank() -> "name:${tagName.hashCode()}"
            else -> "unknown"
        }
}

