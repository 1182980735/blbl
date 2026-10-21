package blbl.cat3399.core.model

/**
 * 弹幕用户过滤器数据模型
 *
 * 存储用户自定义的弹幕过滤规则，包括关键词过滤、正则表达式过滤和屏蔽用户列表。
 */
data class DanmakuUserFilter(
    val keywords: List<String> = emptyList(),
    val regexes: List<Regex> = emptyList(),
    val blockedUserMidHashes: Set<String> = emptySet(),
) {
    /**
     * 判断过滤器是否为空
     */
    fun isEmpty(): Boolean = keywords.isEmpty() && regexes.isEmpty() && blockedUserMidHashes.isEmpty()

    /**
     * 判断过滤器是否非空
     */
    fun isNotEmpty(): Boolean = !isEmpty()

    companion object {
        /**
         * 空过滤器实例
         */
        val EMPTY = DanmakuUserFilter()
    }
}

