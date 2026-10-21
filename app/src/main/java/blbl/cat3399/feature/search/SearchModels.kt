package blbl.cat3399.feature.search

import blbl.cat3399.R

/**
 * 视频搜索排序方式枚举
 */
enum class VideoOrder(
    /** API请求参数值 */
    val apiValue: String,
    /** 显示标签资源ID */
    val labelRes: Int,
) {
    /** 综合排序 */
    TotalRank("totalrank", R.string.search_sort_totalrank),
    /** 播放量排序 */
    Click("click", R.string.search_sort_click),
    /** 发布时间排序 */
    PubDate("pubdate", R.string.search_sort_pubdate),
    /** 弹幕数排序 */
    Dm("dm", R.string.search_sort_dm),
    /** 收藏数排序 */
    Stow("stow", R.string.search_sort_stow),
    /** 评分排序 */
    Scores("scores", R.string.search_sort_scores),
}

/**
 * 直播搜索排序方式枚举
 */
enum class LiveOrder(
    /** API请求参数值 */
    val apiValue: String,
    /** 显示标签资源ID */
    val labelRes: Int,
) {
    /** 在线人数排序 */
    Online("online", R.string.search_sort_live_online),
    /** 开播时间排序 */
    LiveTime("live_time", R.string.search_sort_live_time),
}

/**
 * 用户搜索排序方式枚举
 */
enum class UserOrder(
    /** API请求参数值 */
    val apiValue: String,
    /** 显示标签资源ID */
    val labelRes: Int,
) {
    /** 默认排序 */
    Default("0", R.string.search_sort_user_default),
    /** 粉丝数排序 */
    Fans("fans", R.string.search_sort_user_fans),
    /** 等级排序 */
    Level("level", R.string.search_sort_user_level),
}

/**
 * 搜索标签页枚举
 */
enum class SearchTab(
    /** 标签页索引 */
    val index: Int,
) {
    /** 视频搜索 */
    Video(0),
    /** 番剧搜索 */
    Bangumi(1),
    /** 影视搜索 */
    Media(2),
    /** 直播搜索 */
    Live(3),
    /** 用户搜索 */
    User(4),
    ;

    companion object {
        /** 根据索引获取对应的搜索标签页 */
        fun forIndex(index: Int): SearchTab {
            return entries.firstOrNull { it.index == index } ?: Video
        }
    }
}
