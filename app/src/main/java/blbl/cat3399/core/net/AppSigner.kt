package blbl.cat3399.core.net

import java.security.MessageDigest
import java.util.Locale

/**
 * APP签名工具类
 *
 * 用于生成B站APP端API请求所需的签名参数，采用与PiliPlus android_hd一致的签名方案
 */
object AppSigner {
    /** Android HD端APP密钥（与PiliPlus保持一致） */
    const val APP_KEY_ANDROID_HD = "dfca71928277209b"
    /** Android HD端APP密钥对应的签名密钥 */
    const val APP_SEC_ANDROID_HD = "b5475a8825547a4fc26c7d518eaaa02e"

    /**
     * 对查询参数进行APP签名
     *
     * 签名过程：
     * 1. 添加appkey和ts参数
     * 2. 按键名排序
     * 3. 拼接成查询字符串并进行URL编码
     * 4. 使用MD5加密查询字符串+appSec生成签名
     *
     * @param params 原始查询参数
     * @param appKey APP密钥，默认为Android HD端密钥
     * @param appSec APP签名密钥，默认为Android HD端密钥
     * @param nowEpochSec 当前时间戳（秒），默认使用系统当前时间
     * @return 添加了appkey、ts和sign参数的签名后参数
     */
    fun signQuery(
        params: Map<String, String>,
        appKey: String = APP_KEY_ANDROID_HD,
        appSec: String = APP_SEC_ANDROID_HD,
        nowEpochSec: Long = System.currentTimeMillis() / 1000,
    ): Map<String, String> {
        val out = params.toMutableMap()
        out["appkey"] = appKey
        out["ts"] = nowEpochSec.toString()
        val sorted = out.entries.sortedBy { it.key }
        val query = sorted.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }
        out["sign"] = md5Hex(query + appSec)
        return out
    }

    /** URL编码函数，委托给percentEncodeUtf8 */
    private fun enc(s: String): String = percentEncodeUtf8(s)

    /**
     * URL编码函数，行为类似于JavaScript的encodeURIComponent（空格编码为%20，十六进制大写）
     *
     * @param s 需要编码的字符串
     * @return URL编码后的字符串
     */
    private fun percentEncodeUtf8(s: String): String {
        val bytes = s.toByteArray(Charsets.UTF_8)
        val sb = StringBuilder(bytes.size * 3)
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            val isUnreserved =
                (c in 'a'.code..'z'.code) ||
                    (c in 'A'.code..'Z'.code) ||
                    (c in '0'.code..'9'.code) ||
                    c == '-'.code || c == '_'.code || c == '.'.code || c == '~'.code
            if (isUnreserved) {
                sb.append(c.toChar())
            } else {
                sb.append('%')
                sb.append("0123456789ABCDEF"[c ushr 4])
                sb.append("0123456789ABCDEF"[c and 0x0F])
            }
        }
        return sb.toString()
    }

    /**
     * 计算字符串的MD5哈希值并转换为十六进制字符串
     *
     * @param s 输入字符串
     * @return MD5哈希值的十六进制表示
     */
    private fun md5Hex(s: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(s.toByteArray())
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) sb.append(String.format(Locale.US, "%02x", b))
        return sb.toString()
    }
}

