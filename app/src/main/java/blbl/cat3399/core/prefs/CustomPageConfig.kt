package blbl.cat3399.core.prefs

import org.json.JSONArray
import org.json.JSONObject

/**
 * 自定义页面配置数据模型
 *
 * 用于定义用户自定义页面的配置，包括是否启用和包含的标签页列表。
 */
data class CustomPageConfig(
    val enabled: Boolean,
    val tabs: List<CustomPageTabConfig>,
)

/**
 * 自定义页面标签页配置数据模型
 *
 * 表示自定义页面中的单个标签页，包含数据源类型和可选的数据源键。
 */
data class CustomPageTabConfig(
    val sourceType: String,
    val sourceKey: String? = null,
) {
    /**
     * 生成稳定的唯一标识键
     */
    fun stableKey(): String = buildStableKey(sourceType = sourceType, sourceKey = sourceKey)

    companion object {
        /**
         * 构建稳定的唯一标识键
         *
         * @param sourceType 数据源类型
         * @param sourceKey 数据源键
         * @return 稳定的唯一标识字符串
         */
        fun buildStableKey(
            sourceType: String,
            sourceKey: String?,
        ): String {
            val type = sourceType.trim().lowercase()
            val key = sourceKey?.trim()?.takeIf { it.isNotBlank() }
            return if (key == null) type else "$type:$key"
        }
    }
}

/**
 * 自定义页面配置存储管理器
 */
object CustomPageConfigStore {
    private const val JSON_VERSION = 1
    private const val KEY_VERSION = "v"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_TABS = "tabs"
    private const val KEY_SOURCE_TYPE = "type"
    private const val KEY_SOURCE_KEY = "key"

    /**
     * 从JSON字符串解析自定义页面配置
     *
     * @param raw JSON字符串
     * @return 解析后的自定义页面配置，解析失败则返回空配置
     */
    fun parse(raw: String?): CustomPageConfig {
        val text = raw?.trim().orEmpty()
        if (text.isBlank()) return empty()
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return empty()
        if (root.optInt(KEY_VERSION, JSON_VERSION) != JSON_VERSION) return empty()

        val enabled = root.optBoolean(KEY_ENABLED, false)
        val tabsArray = root.optJSONArray(KEY_TABS) ?: JSONArray()
        val tabs = ArrayList<CustomPageTabConfig>(tabsArray.length().coerceAtLeast(0))
        for (index in 0 until tabsArray.length()) {
            val item = tabsArray.optJSONObject(index) ?: continue
            val sourceType = item.optString(KEY_SOURCE_TYPE, "").trim().lowercase()
            if (sourceType.isBlank()) continue
            val sourceKey = item.optString(KEY_SOURCE_KEY, "").trim().takeIf { it.isNotBlank() }
            tabs.add(CustomPageTabConfig(sourceType = sourceType, sourceKey = sourceKey))
        }
        return normalize(CustomPageConfig(enabled = enabled, tabs = tabs))
    }

    /**
     * 将自定义页面配置序列化为JSON字符串
     *
     * @param config 自定义页面配置
     * @return JSON字符串
     */
    fun serialize(config: CustomPageConfig): String {
        val normalized = normalize(config)
        val tabs = JSONArray()
        normalized.tabs.forEach { tab ->
            tabs.put(
                JSONObject()
                    .put(KEY_SOURCE_TYPE, tab.sourceType)
                    .put(KEY_SOURCE_KEY, tab.sourceKey),
            )
        }
        return JSONObject()
            .put(KEY_VERSION, JSON_VERSION)
            .put(KEY_ENABLED, normalized.enabled)
            .put(KEY_TABS, tabs)
            .toString()
    }

    /**
     * 规范化自定义页面配置
     *
     * 去除空白和重复的标签页，确保配置的有效性。
     *
     * @param config 原始配置
     * @return 规范化后的配置
     */
    fun normalize(config: CustomPageConfig): CustomPageConfig {
        if (config.tabs.isEmpty()) return CustomPageConfig(enabled = config.enabled, tabs = emptyList())
        val seen = HashSet<String>(config.tabs.size * 2)
        val normalizedTabs = ArrayList<CustomPageTabConfig>(config.tabs.size)
        config.tabs.forEach { tab ->
            val sourceType = tab.sourceType.trim().lowercase()
            if (sourceType.isBlank()) return@forEach
            val sourceKey = tab.sourceKey?.trim()?.takeIf { it.isNotBlank() }
            val normalizedTab = CustomPageTabConfig(sourceType = sourceType, sourceKey = sourceKey)
            if (seen.add(normalizedTab.stableKey())) {
                normalizedTabs.add(normalizedTab)
            }
        }
        return CustomPageConfig(enabled = config.enabled, tabs = normalizedTabs)
    }

    /**
     * 创建空的自定义页面配置
     *
     * @return 禁用状态的空配置
     */
    fun empty(): CustomPageConfig = CustomPageConfig(enabled = false, tabs = emptyList())
}
