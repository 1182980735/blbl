package blbl.cat3399.core.model

import java.util.Locale

/**
 * 弹幕屏蔽规则数据模型
 *
 * 包含完整的弹幕过滤配置，支持按类型、颜色、AI权重、关键词、正则表达式和屏蔽用户进行过滤。
 */
data class DanmakuShield(
    val allowScroll: Boolean = true,
    val allowTop: Boolean = true,
    val allowBottom: Boolean = true,
    val allowColor: Boolean = true,
    val allowSpecial: Boolean = true,
    val aiEnabled: Boolean = false,
    val aiLevel: Int = 3,
    val keywords: List<String> = emptyList(),
    val regexes: List<Regex> = emptyList(),
    val blockedUserMidHashes: Set<String> = emptySet(),
) {
    /**
     * 判断弹幕是否允许显示
     *
     * 按以下顺序进行过滤判断：
     * 1. 弹幕类型过滤（滚动/顶部/底部/特殊）
     * 2. 颜色过滤（仅黑白）
     * 3. AI权重过滤
     * 4. 屏蔽用户过滤
     * 5. 关键词过滤
     * 6. 正则表达式过滤
     *
     * @param danmaku 待判断的弹幕
     * @return true表示允许显示，false表示应被屏蔽
     */
    fun allow(danmaku: Danmaku): Boolean {
        val mode = danmaku.mode
        val typeAllowed = when (mode) {
            1, 2, 3, 6 -> allowScroll
            4 -> allowBottom
            5 -> allowTop
            7, 8, 9 -> false
            else -> allowSpecial
        }
        if (!typeAllowed) return false

        if (!allowColor) {
            val rgb = danmaku.color and 0xFFFFFF
            val isWhite = (rgb == 0) || (rgb == 0xFFFFFF)
            if (!isWhite) return false
        }

        if (aiEnabled) {
            val level = aiLevel.coerceIn(0, 10).let { if (it == 0) 3 else it }
            if (danmaku.weight < level) return false
        }

        if (blockedUserMidHashes.isNotEmpty()) {
            val hash = danmaku.midHash?.trim()?.lowercase(Locale.US)
            if (!hash.isNullOrBlank() && blockedUserMidHashes.contains(hash)) return false
        }

        if (keywords.isNotEmpty()) {
            val text = danmaku.text
            for (k in keywords) {
                if (k.isNotBlank() && text.contains(k)) return false
            }
        }

        if (regexes.isNotEmpty()) {
            val text = danmaku.text
            for (r in regexes) {
                if (r.containsMatchIn(text)) return false
            }
        }

        return true
    }
}
