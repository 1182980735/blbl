package blbl.cat3399.core.net

import android.util.Base64
import android.os.SystemClock
import blbl.cat3399.core.log.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.PublicKey
import java.security.spec.MGF1ParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec

/**
 * Web Cookie维护器
 *
 * 负责管理和维护B站Web端所需的各种Cookie，包括：
 * - Buvid设备指纹Cookie（buvid3, buvid4, b_nut）
 * - BiliTicket票据Cookie
 * - Cookie自动刷新
 * - Buvid每日激活
 */
object WebCookieMaintainer {
    private const val TAG = "WebCookieMaintainer"

    /** BiliTicket密钥ID */
    private const val BILI_TICKET_KEY_ID = "ec02"
    /** BiliTicket HMAC密钥 */
    private const val BILI_TICKET_HMAC_KEY = "XgwSnGZ1p"

    /** Cookie刷新来源标识 */
    private const val REFRESH_SOURCE = "main_web"

    /** 用于从网页中提取refresh_csrf的正则表达式 */
    private val refreshCsrfRegex = Regex("<div\\s+id=\"1-name\">\\s*([0-9a-fA-F]{16,})\\s*</div>")

    /**
     * 用于Cookie刷新的RSA公钥
     *
     * 来源于bilibili-api-docs的cookie_refresh.md文档
     */
    private val correspondPublicKey: PublicKey by lazy {
        val derBase64 =
            "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDLgd2OAkcGVtoE3ThUREbio0Eg" +
                "Uc/prcajMKXvkCKFCWhJYJcLkcM2DKKcSeFpD/j6Boy538YXnR6VhcuUJOhH2x71" +
                "nzPjfdTcqMz7djHum0qSZA0AyCBDABUqCrfNgCiJ00Ra7GmRj+YCK1NJEuewlb40" +
                "JNrRuoEUXpabUzGB8QIDAQAB"
        val keyBytes = Base64.decode(derBase64, Base64.DEFAULT)
        val spec = X509EncodedKeySpec(keyBytes)
        KeyFactory.getInstance("RSA").generatePublic(spec)
    }

    /** Cookie刷新互斥锁，防止并发刷新 */
    private val cookieRefreshMutex = Mutex()

    /**
     * 确保Buvid每日激活一次
     *
     * 通过向B站服务器发送设备指纹信息，保持buvid的活跃状态，这对于某些API的正常调用是必要的
     *
     * @param nowMs 当前时间戳（毫秒），默认使用系统当前时间
     */
    suspend fun ensureBuvidActiveOncePerDay(nowMs: Long = System.currentTimeMillis()) {
        val midStr = BiliClient.cookies.getCookieValue("DedeUserID")?.trim().orEmpty()
        val mid = midStr.toLongOrNull()?.takeIf { it > 0 } ?: return
        val epochDay = nowMs / 86_400_000L
        if (BiliClient.prefs.buvidActivatedMid == mid && BiliClient.prefs.buvidActivatedEpochDay == epochDay) return

        runCatching {
            val rand = ByteArray(32 + 8 + 4)
            java.security.SecureRandom().nextBytes(rand)
            // 覆盖中间字节以模拟PiliPlus的固定PNG尾部标记
            rand[32] = 0
            rand[33] = 0
            rand[34] = 0
            rand[35] = 0
            rand[36] = 73
            rand[37] = 69
            rand[38] = 78
            rand[39] = 68
            val tail = ByteArray(4)
            java.security.SecureRandom().nextBytes(tail)
            for (i in 0 until 4) rand[40 + i] = tail[i]

            val randPngEnd = android.util.Base64.encodeToString(rand, android.util.Base64.NO_WRAP)
            val jsonData =
                JSONObject()
                    .put("3064", 1)
                    .put("39c8", "333.1387.fp.risk")
                    .put(
                        "3c43",
                        JSONObject()
                            .put("adca", "Linux")
                            .put("bfe9", randPngEnd.takeLast(50)),
                    )
                    .toString()

            val body =
                JSONObject()
                    .put("payload", jsonData)
                    .toString()
                    .toRequestBody("application/json; charset=utf-8".toMediaType())

            val cookie =
                buildList {
                    listOf("SESSDATA", "bili_jct", "DedeUserID", "DedeUserID__ckMd5", "sid", "buvid3").forEach { name ->
                        val v = BiliClient.cookies.getCookieValue(name)?.takeIf { it.isNotBlank() } ?: return@forEach
                        add("$name=$v")
                    }
                }.joinToString("; ")

            val headers =
                buildMap {
                    put("Content-Type", "application/json")
                    put("env", "prod")
                    put("app-key", "android64")
                    put("x-bili-aurora-zone", "sh001")
                    put("x-bili-mid", mid.toString())
                    genAuroraEid(mid)?.let { put("x-bili-aurora-eid", it) }
                    put("Referer", "https://www.bilibili.com")
                    put("X-Blbl-Skip-Origin", "1")
                    if (cookie.isNotBlank()) put("Cookie", cookie)
                }

            BiliClient.requestString(
                "https://api.bilibili.com/x/internal/gaia-gateway/ExClimbWuzhi",
                method = "POST",
                headers = headers,
                body = body,
                noCookies = true,
            )

            BiliClient.prefs.buvidActivatedMid = mid
            BiliClient.prefs.buvidActivatedEpochDay = epochDay
            AppLog.i(TAG, "buvidActive ok mid=$mid")
        }.onFailure {
            AppLog.w(TAG, "buvidActive failed", it)
        }
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
        return android.util.Base64.encodeToString(out, android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)
    }

    /**
     * 确保播放所需的Cookie都处于健康状态
     *
     * 在播放视频/直播前调用，确保所有必要的Cookie都已准备好
     */
    suspend fun ensureHealthyForPlay() {
        ensureWebFingerprintCookies()
        ensureBuvidActiveOncePerDay()
        ensureBiliTicket()
        refreshCookieIfNeededOncePerDay()
    }

    /**
     * 执行每日Cookie维护任务
     *
     * 定期调用以确保Cookie的有效性和安全性
     */
    suspend fun ensureDailyMaintenance() {
        ensureBiliTicket()
        refreshCookieIfNeededOncePerDay()
    }

    /**
     * 确保Web指纹Cookie存在
     *
     * 检查并获取以下Cookie：
     * - buvid3: 通过访问B站首页或finger/spi接口获取
     * - buvid4: 通过finger/spi接口获取
     * - b_nut: 通过访问B站首页获取
     */
    suspend fun ensureWebFingerprintCookies() {
        val startMs = SystemClock.elapsedRealtime()
        val hasBuvid3 = !BiliClient.cookies.getCookieValue("buvid3").isNullOrBlank()
        val hasBNut = !BiliClient.cookies.getCookieValue("b_nut").isNullOrBlank()
        val needHomepage = !hasBuvid3 || !hasBNut
        val hasBuvid4 = !BiliClient.cookies.getCookieValue("buvid4").isNullOrBlank()
        val needSpi = !hasBuvid4
        AppLog.i(TAG, "ensureWebFingerprintCookies enter needHomepage=$needHomepage needSpi=$needSpi")
        if (needHomepage) {
            val t0 = SystemClock.elapsedRealtime()
            runCatching { BiliClient.getBytes("https://www.bilibili.com/") }
                .onFailure { AppLog.w(TAG, "ensureWebFingerprintCookies homepage failed", it) }
            AppLog.i(TAG, "ensureWebFingerprintCookies homepage cost=${SystemClock.elapsedRealtime() - t0}ms")
        }

        if (needSpi) {
            val t0 = SystemClock.elapsedRealtime()
            runCatching {
                val json = BiliClient.getJson("https://api.bilibili.com/x/frontend/finger/spi")
                val data = json.optJSONObject("data") ?: JSONObject()
                val b3 = data.optString("b_3", "").trim()
                val b4 = data.optString("b_4", "").trim()
                val expiresAt = System.currentTimeMillis() + 365L * 24 * 60 * 60 * 1000
                val out = ArrayList<Cookie>(2)
                if (b3.isNotBlank() && BiliClient.cookies.getCookieValue("buvid3").isNullOrBlank()) {
                    out.add(buildCookie(name = "buvid3", value = b3, expiresAt = expiresAt))
                }
                if (b4.isNotBlank()) {
                    out.add(buildCookie(name = "buvid4", value = b4, expiresAt = expiresAt))
                }
                if (out.isNotEmpty()) BiliClient.cookies.upsertAll(out)
            }.onFailure {
                AppLog.w(TAG, "ensureWebFingerprintCookies finger/spi failed", it)
            }
            AppLog.i(TAG, "ensureWebFingerprintCookies finger/spi cost=${SystemClock.elapsedRealtime() - t0}ms")
        }
        AppLog.i(TAG, "ensureWebFingerprintCookies exit cost=${SystemClock.elapsedRealtime() - startMs}ms")
    }

    /**
     * 确保BiliTicket存在且有效
     *
     * BiliTicket是B站用于验证用户身份的临时票据，有效期较短，需要定期更新
     *
     * 刷新流程：
     * 1. 检查现有ticket是否在6小时内过期
     * 2. 如果需要刷新，使用HMAC-SHA256生成签名
     * 3. 调用GenWebTicket接口获取新ticket
     * 4. 更新Cookie存储
     */
    suspend fun ensureBiliTicket() {
        val nowMs = System.currentTimeMillis()
        val epochDay = nowMs / 86_400_000L
        val existing = BiliClient.cookies.getCookie("bili_ticket")
        if (existing != null && existing.expiresAt - nowMs > 6 * 60 * 60 * 1000) return
        if (BiliClient.prefs.biliTicketCheckedEpochDay == epochDay) return
        BiliClient.prefs.biliTicketCheckedEpochDay = epochDay

        runCatching {
            val ts = (nowMs / 1000).toString()
            val hexsign = hmacSha256Hex(key = BILI_TICKET_HMAC_KEY, message = "ts$ts")
            val csrf = BiliClient.cookies.getCookieValue("bili_jct").orEmpty()
            val url =
                BiliClient.withQuery(
                    "https://api.bilibili.com/bapis/bilibili.api.ticket.v1.Ticket/GenWebTicket",
                    buildMap {
                        put("key_id", BILI_TICKET_KEY_ID)
                        put("hexsign", hexsign)
                        put("context[ts]", ts)
                        if (csrf.isNotBlank()) put("csrf", csrf)
                    },
                )
            val json =
                BiliClient.requestString(
                    url,
                    method = "POST",
                    body = ByteArray(0).toRequestBody(null),
                ).let { raw -> withContext(Dispatchers.Default) { JSONObject(raw) } }
            val data = json.optJSONObject("data") ?: JSONObject()
            val ticket = data.optString("ticket", "").trim()
            val createdAt = data.optLong("created_at", 0L)
            val ttl = data.optLong("ttl", 0L)
            if (ticket.isBlank() || createdAt <= 0L || ttl <= 0L) return@runCatching

            val expiresSec = createdAt + ttl
            val expiresAt = expiresSec * 1000L
            BiliClient.cookies.upsertAll(
                listOf(
                    buildCookie(name = "bili_ticket", value = ticket, expiresAt = expiresAt),
                    buildCookie(name = "bili_ticket_expires", value = expiresSec.toString(), expiresAt = expiresAt),
                ),
            )
            AppLog.i(TAG, "bili_ticket updated")
        }.onFailure {
            AppLog.w(TAG, "ensureBiliTicket failed", it)
        }
    }

    /**
     * 每日检查并刷新Cookie（如果需要）
     *
     * Cookie刷新流程：
     * 1. 检查是否有SESSDATA和refresh_token
     * 2. 查询Cookie状态，判断是否需要刷新
     * 3. 如果需要刷新，获取refresh_csrf（通过RSA加密时间戳获取对应路径）
     * 4. 调用cookie/refresh接口刷新Cookie
     * 5. 更新refresh_token
     * 6. 调用confirm/refresh确认刷新
     */
    suspend fun refreshCookieIfNeededOncePerDay() {
        if (!BiliClient.cookies.hasSessData()) return
        val refreshToken = BiliClient.prefs.webRefreshToken?.takeIf { it.isNotBlank() } ?: return

        cookieRefreshMutex.withLock {
            val nowMs = System.currentTimeMillis()
            val epochDay = nowMs / 86_400_000L
            if (BiliClient.prefs.webCookieRefreshCheckedEpochDay == epochDay) return@withLock

            val biliJct = BiliClient.cookies.getCookieValue("bili_jct")?.takeIf { it.isNotBlank() } ?: return@withLock
            runCatching {
                val infoUrl =
                    BiliClient.withQuery(
                        "https://passport.bilibili.com/x/passport-login/web/cookie/info",
                        mapOf("csrf" to biliJct),
                    )
                val info = BiliClient.getJson(infoUrl)
                val data = info.optJSONObject("data") ?: JSONObject()
                val shouldRefresh = data.optBoolean("refresh", false)
                if (!shouldRefresh) return@runCatching

                val ts = data.optLong("timestamp", nowMs).takeIf { it > 0 } ?: nowMs
                val correspondPath = withContext(Dispatchers.Default) { getCorrespondPath(ts) }
                val html = BiliClient.requestString("https://www.bilibili.com/correspond/1/$correspondPath")
                val refreshCsrf = refreshCsrfRegex.find(html)?.groupValues?.getOrNull(1).orEmpty()
                if (refreshCsrf.isBlank()) error("refresh_csrf not found")

                val refreshResult =
                    BiliClient.postFormJson(
                        "https://passport.bilibili.com/x/passport-login/web/cookie/refresh",
                        form =
                            mapOf(
                                "csrf" to biliJct,
                                "refresh_csrf" to refreshCsrf,
                                "source" to REFRESH_SOURCE,
                                "refresh_token" to refreshToken,
                            ),
                    )
                val refreshData = refreshResult.optJSONObject("data") ?: JSONObject()
                val newRefreshToken = refreshData.optString("refresh_token", "").trim()
                if (newRefreshToken.isNotBlank()) BiliClient.prefs.webRefreshToken = newRefreshToken

                val newBiliJct = BiliClient.cookies.getCookieValue("bili_jct")?.takeIf { it.isNotBlank() } ?: biliJct
                runCatching {
                    BiliClient.postFormJson(
                        "https://passport.bilibili.com/x/passport-login/web/confirm/refresh",
                        form =
                            mapOf(
                                "csrf" to newBiliJct,
                                "refresh_token" to refreshToken,
                            ),
                    )
                }

                AppLog.i(TAG, "web cookie refreshed")
            }.onFailure {
                AppLog.w(TAG, "refreshCookieIfNeededOncePerDay failed", it)
                return@withLock
            }

            BiliClient.prefs.webCookieRefreshCheckedEpochDay = epochDay
        }
    }

    /**
     * 创建B站Cookie
     *
     * @param name Cookie名称
     * @param value Cookie值
     * @param expiresAt 过期时间戳（毫秒）
     * @return Cookie实例
     */
    private fun buildCookie(name: String, value: String, expiresAt: Long): Cookie {
        return Cookie.Builder()
            .name(name)
            .value(value)
            .domain("bilibili.com")
            .path("/")
            .expiresAt(expiresAt)
            .secure()
            .build()
    }

    /**
     * 计算字符串的HMAC-SHA256哈希值并转换为十六进制字符串
     *
     * @param key HMAC密钥
     * @param message 消息内容
     * @return HMAC-SHA256哈希值的十六进制表示
     */
    private fun hmacSha256Hex(key: String, message: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        val out = mac.doFinal(message.toByteArray(StandardCharsets.UTF_8))
        val sb = StringBuilder(out.size * 2)
        for (b in out) sb.append(String.format("%02x", b))
        return sb.toString()
    }

    /**
     * 获取Cookie刷新对应的路径
     *
     * 使用RSA-OAEP加密"refresh_时间戳"字符串，用于获取refresh_csrf
     *
     * @param timestampMs 时间戳（毫秒）
     * @return RSA加密后的十六进制字符串，作为对应路径
     */
    private fun getCorrespondPath(timestampMs: Long): String {
        val plaintext = "refresh_$timestampMs"
        val input = plaintext.toByteArray(StandardCharsets.UTF_8)
        val cipher =
            runCatching { Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding") }
                .getOrElse { Cipher.getInstance("RSA/ECB/OAEPPadding") }
        cipher.init(
            Cipher.ENCRYPT_MODE,
            correspondPublicKey,
            OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT),
        )
        val encrypted = cipher.doFinal(input)
        val sb = StringBuilder(encrypted.size * 2)
        for (b in encrypted) sb.append(String.format("%02x", b))
        return sb.toString()
    }
}
