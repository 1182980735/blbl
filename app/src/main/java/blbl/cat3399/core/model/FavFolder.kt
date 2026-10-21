package blbl.cat3399.core.model

/**
 * 收藏夹数据模型
 *
 * 表示用户的一个收藏夹，包含收藏夹ID、标题、封面和资源数量。
 */
data class FavFolder(
    val mediaId: Long,
    val title: String,
    val coverUrl: String?,
    val mediaCount: Int,
)

