package blbl.cat3399.core.model

/**
 * 番剧季数据模型
 *
 * 表示番剧的一个季（Season），包含季的基本信息和观看进度。
 */
data class BangumiSeason(
    val seasonId: Long,
    val seasonTypeName: String?,
    val title: String,
    val coverUrl: String?,
    val badge: String?,
    val badgeEp: String?,
    val progressText: String?,
    val totalCount: Int?,
    val lastEpIndex: Int?,
    val lastEpId: Long?,
    val newestEpIndex: Int?,
    val isFinish: Boolean?,
)

/**
 * 番剧分集数据模型
 *
 * 表示番剧的单集信息，包含播放所需的各种ID和标题。
 */
data class BangumiEpisode(
    val epId: Long,
    val aid: Long?,
    val cid: Long?,
    val bvid: String?,
    val title: String,
    val longTitle: String,
    val coverUrl: String?,
    val badge: String?,
)

/**
 * 番剧分集章节数据模型
 *
 * 将分集按章节分组，用于展示多章节番剧。
 */
data class BangumiEpisodeSection(
    val title: String,
    val episodes: List<BangumiEpisode>,
)

/**
 * 番剧季详情数据模型
 *
 * 包含番剧季的完整信息，包括评分、播放量、分集列表和观看进度。
 */
data class BangumiSeasonDetail(
    val seasonId: Long,
    val title: String,
    val coverUrl: String?,
    val subtitle: String?,
    val evaluate: String?,
    val ratingScore: Double?,
    val views: Long?,
    val danmaku: Long?,
    val episodes: List<BangumiEpisode>,
    val extraSections: List<BangumiEpisodeSection>,
    val progressLastEpId: Long?,
    val progressLastTimeSec: Long?,
    val isFollowed: Boolean?,
)
