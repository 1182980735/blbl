package blbl.cat3399.core.net

import android.util.Base64

/**
 * PiliWeb请求头生成器
 *
 * 为Pili播放器生成符合B站规范的请求头，用于获取直播流等媒体资源
 */
internal object PiliWebHeaders {
    /** Referer头值，固定为B站首页 */
    private const val REFERER = "https://www.bilibili.com"

    /**
     * 为目标URL生成PiliWeb请求头
     *
     * @param targetUrl 目标URL
     * @param includeCookie 是否包含Cookie，默认为true
     * @return 请求头Map
     */
    fun forUrl(
        targetUrl: String,
        includeCookie: Boolean = true,
    ): Map<String, String> {
        val out = baseHeaders(includeMid = includeCookie).toMutableMap()
        out["Referer"] = REFERER
        // 告诉OkHttp拦截器不要添加Origin头（PiliPlus在这个流程中不发送Origin）
        out["X-Blbl-Skip-Origin"] = "1"
        if (includeCookie) {
            val cookie = BiliClient.cookies.cookieHeaderFor(targetUrl)
            if (!cookie.isNullOrBlank()) out["Cookie"] = cookie
        }
        return out
    }

    /**
     * 生成基础请求头
     *
     * @param includeMid 是否包含用户ID（mid），默认为true
     * @return 基础请求头Map
     */
    private fun baseHeaders(includeMid: Boolean = true): Map<String, String> {
        val headers =
            mutableMapOf(
                "env" to "prod",
                "app-key" to "android64",
                "x-bili-aurora-zone" to "sh001",
            )
        if (!includeMid) return headers
        val midStr = BiliClient.cookies.getCookieValue("DedeUserID")?.trim().orEmpty()
        val mid = midStr.toLongOrNull()?.takeIf { it > 0 } ?: return headers
        headers["x-bili-mid"] = mid.toString()
        genAuroraEid(mid)?.let { headers["x-bili-aurora-eid"] = it }
        return headers
    }

    /**
     * 生成Aurora设备ID
     *
     * 使用简单的XOR加密算法对用户ID进行加密，用于设备识别
     *
     * @param mid 用户ID
     * @return Base64编码后的Aurora设备ID，如果mid无效则返回null
     */
    private fun genAuroraEid(mid: Long): String? {
        if (mid <= 0) return null
        val key = "ad1va46a7lza".toByteArray()
        val input = mid.toString().toByteArray()
        val out = ByteArray(input.size)
        for (i in input.indices) out[i] = (input[i].toInt() xor key[i % key.size].toInt()).toByte()
        return Base64.encodeToString(out, Base64.NO_PADDING or Base64.NO_WRAP)
    }
}
