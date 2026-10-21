package blbl.cat3399.core.net

import android.content.Context
import blbl.cat3399.core.account.AccountSessionStore
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.prefs.AppPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * B站网络请求客户端
 *
 * 提供统一的网络请求入口，管理多个OkHttpClient实例用于不同场景：
 * - apiOkHttp: 用于API请求，携带Cookie
 * - cdnOkHttp: 用于CDN媒体请求，携带Cookie
 * - appCdnOkHttp: 用于模拟APP的CDN请求，不携带Cookie
 */
object BiliClient {
    private const val TAG = "BiliClient"

    /** B站API基础地址 */
    private const val BASE = "https://api.bilibili.com"

    /** 跳过Origin头的自定义Header */
    private const val HDR_SKIP_ORIGIN = "X-Blbl-Skip-Origin"

    /** 是否记录HTTP请求日志 */
    private const val LOG_HTTP_REQUESTS = false

    /** APP CDN请求使用的User-Agent */
    internal const val APP_CDN_USER_AGENT = "Bilibili Freedoooooom/MarkII"

    /** 应用偏好设置 */
    lateinit var prefs: AppPrefs
        private set

    /** 账号会话存储 */
    lateinit var accounts: AccountSessionStore
        private set

    /** Cookie存储 */
    lateinit var cookies: CookieStore
        private set

    /** API请求用的OkHttpClient（携带Cookie） */
    lateinit var apiOkHttp: OkHttpClient
        private set

    /** API请求用的OkHttpClient（不携带Cookie） */
    private lateinit var apiOkHttpNoCookies: OkHttpClient

    /** CDN媒体请求用的OkHttpClient（携带Cookie） */
    lateinit var cdnOkHttp: OkHttpClient
        private set

    /** 模拟APP的CDN请求用的OkHttpClient（不携带Cookie） */
    lateinit var appCdnOkHttp: OkHttpClient
        private set

    /**
     * HTTP字符串响应封装
     */
    data class StringResponse(
        /** HTTP状态码 */
        val code: Int,
        /** HTTP状态消息 */
        val message: String,
        /** 响应体 */
        val body: String,
    ) {
        /** 是否请求成功（2xx状态码） */
        val isSuccessful: Boolean
            get() = code in 200..299
    }

    /** WBI签名密钥缓存 */
    @Volatile
    private var wbiKeys: WbiSigner.Keys? = null

    /**
     * 初始化BiliClient
     *
     * 创建各种OkHttpClient实例，配置拦截器和超时设置
     */
    fun init(context: Context) {
        prefs = AppPrefs(context.applicationContext)
        accounts = AccountSessionStore(context.applicationContext)
        cookies = CookieStore(context.applicationContext)

        // 创建仅IPv4的DNS解析器
        val dns = ipv4OnlyDns { prefs.ipv4OnlyEnabled }

        // 创建基础OkHttpClient，共享Cookie和DNS配置
        val baseClient = OkHttpClient.Builder()
            .cookieJar(cookies)
            .dns(dns)
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()

        // API请求客户端：自动添加User-Agent、Referer、Origin头
        apiOkHttp = baseClient.newBuilder()
            .addInterceptor { chain ->
                val ua = prefs.userAgent
                val original = chain.request()
                val builder = original.newBuilder()
                if (original.header("User-Agent").isNullOrBlank()) builder.header("User-Agent", ua)
                if (original.header("Referer").isNullOrBlank()) builder.header("Referer", "https://www.bilibili.com/")
                val skipOrigin = original.header(HDR_SKIP_ORIGIN) == "1"
                if (skipOrigin) builder.removeHeader(HDR_SKIP_ORIGIN)
                if (!skipOrigin && original.header("Origin").isNullOrBlank()) builder.header("Origin", "https://www.bilibili.com")
                val req = builder.build()
                val start = System.nanoTime()
                val res = chain.proceed(req)
                val costMs = (System.nanoTime() - start) / 1_000_000
                if (LOG_HTTP_REQUESTS) {
                    AppLog.d(TAG, "${req.method} ${req.url.host}${req.url.encodedPath} -> ${res.code} (${costMs}ms)")
                }
                res
            }
            .build()

        // API请求客户端（不携带Cookie）
        apiOkHttpNoCookies = apiOkHttp.newBuilder().cookieJar(CookieJar.NO_COOKIES).build()

        // CDN媒体请求客户端：自动添加User-Agent和Referer头，不添加Origin（避免触发403）
        cdnOkHttp = baseClient.newBuilder()
            .addInterceptor { chain ->
                val ua = prefs.userAgent
                val original = chain.request()
                val builder = original.newBuilder()
                if (original.header("User-Agent").isNullOrBlank()) builder.header("User-Agent", ua)
                if (original.header("Referer").isNullOrBlank()) builder.header("Referer", "https://www.bilibili.com/")
                // CDN/媒体请求通常不需要 Origin；某些 CDN 反而会因 Origin 触发 403。
                val req = builder.build()
                val start = System.nanoTime()
                val res = chain.proceed(req)
                val costMs = (System.nanoTime() - start) / 1_000_000
                if (LOG_HTTP_REQUESTS) {
                    AppLog.d(TAG, "CDN ${req.method} ${req.url.host}${req.url.encodedPath} -> ${res.code} (${costMs}ms)")
                }
                res
            }
            .build()

        // 模拟APP的CDN请求客户端：不携带Cookie，使用APP专用User-Agent
        appCdnOkHttp = baseClient.newBuilder()
            .cookieJar(CookieJar.NO_COOKIES)
            .protocols(listOf(Protocol.HTTP_1_1))
            .addInterceptor { chain ->
                val original = chain.request()
                val defaultPort = if (original.url.isHttps) 443 else 80
                val hostHeader =
                    if (original.url.port == defaultPort) {
                        original.url.host
                    } else {
                        "${original.url.host}:${original.url.port}"
                    }
                val req =
                    original
                        .newBuilder()
                        .header("User-Agent", APP_CDN_USER_AGENT)
                        .header("Accept-Encoding", "identity")
                        .header("Host", hostHeader)
                        .header("Connection", "Keep-Alive")
                        .build()
                val start = System.nanoTime()
                val res = chain.proceed(req)
                val costMs = (System.nanoTime() - start) / 1_000_000
                if (LOG_HTTP_REQUESTS) {
                    AppLog.d(TAG, "APP CDN ${req.method} ${req.url.host}${req.url.encodedPath} -> ${res.code} (${costMs}ms)")
                }
                res
            }
            .build()

        AppLog.i(TAG, "init ua=${prefs.userAgent.take(48)} cookiesSess=${cookies.hasSessData()}")
    }

    /**
     * 清除登录会话
     */
    fun clearLoginSession() {
        accounts.clearAllAccountsAndCurrentSession(appPrefs = prefs, cookies = cookies)
    }

    /**
     * 根据URL选择合适的OkHttpClient
     *
     * @param url 请求URL
     * @param noCookies 是否不携带Cookie
     * @return 对应的OkHttpClient
     */
    private fun clientFor(url: String, noCookies: Boolean = false): OkHttpClient {
        val host = runCatching { url.toHttpUrl().host }.getOrNull().orEmpty()
        // CDN域名使用cdnOkHttp，其他使用apiOkHttp
        return if (
            host.endsWith("hdslb.com") ||
            host.contains("bilivideo.com") ||
            host.contains("bilivideo.cn") ||
            host.contains("mcdn.bilivideo")
        ) cdnOkHttp else apiOkHttp
            .let { if (noCookies) apiOkHttpNoCookies else apiOkHttp }
    }

    /**
     * 发送HTTP请求并返回字符串响应
     *
     * @throws IOException 如果请求失败
     */
    suspend fun requestString(
        url: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        body: RequestBody? = null,
        noCookies: Boolean = false,
    ): String {
        val res = requestStringResponse(url = url, method = method, headers = headers, body = body, noCookies = noCookies)
        if (!res.isSuccessful) throw IOException("HTTP ${res.code} ${res.message} body=${res.body.take(200)}")
        return res.body
    }

    /**
     * 发送HTTP请求并返回完整响应信息
     */
    suspend fun requestStringResponse(
        url: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        body: RequestBody? = null,
        noCookies: Boolean = false,
    ): StringResponse {
        val reqBuilder = Request.Builder().url(url)
        for ((k, v) in headers) reqBuilder.header(k, v)
        when (method.uppercase()) {
            "GET" -> reqBuilder.get()
            "POST" -> reqBuilder.post(body ?: ByteArray(0).toRequestBody(null))
            else -> error("Unsupported method: $method")
        }
        val res = clientFor(url, noCookies = noCookies).newCall(reqBuilder.build()).await()
        res.use { r ->
            val raw = withContext(Dispatchers.IO) { r.body?.string() ?: "" }
            return StringResponse(code = r.code, message = r.message, body = raw)
        }
    }

    /**
     * 发送GET请求并解析为JSON对象
     */
    suspend fun getJson(url: String, headers: Map<String, String> = emptyMap(), noCookies: Boolean = false): JSONObject {
        val body = requestString(url, method = "GET", headers = headers, noCookies = noCookies)
        return withContext(Dispatchers.Default) { JSONObject(body) }
    }

    /**
     * 发送POST表单请求并解析为JSON对象
     */
    suspend fun postFormJson(
        url: String,
        form: Map<String, String>,
        headers: Map<String, String> = emptyMap(),
        noCookies: Boolean = false,
    ): JSONObject {
        val builder = FormBody.Builder()
        for ((k, v) in form) builder.add(k, v)
        val body = requestString(url, method = "POST", headers = headers, body = builder.build(), noCookies = noCookies)
        return withContext(Dispatchers.Default) { JSONObject(body) }
    }

    /**
     * 发送GET请求并返回字节数组
     */
    suspend fun getBytes(url: String, headers: Map<String, String> = emptyMap(), noCookies: Boolean = false): ByteArray {
        val reqBuilder = Request.Builder().url(url)
        for ((k, v) in headers) reqBuilder.header(k, v)
        val res = clientFor(url, noCookies = noCookies).newCall(reqBuilder.build()).await()
        res.use { r ->
            val bytes = withContext(Dispatchers.IO) { r.body?.bytes() ?: ByteArray(0) }
            if (!r.isSuccessful) {
                // 某些网络/代理可能返回非2xx状态码（如304）但仍然附带响应体；
                // 当响应体可用时优先使用，以避免破坏弹幕分段解析等流程。
                if (bytes.isNotEmpty()) return bytes
                throw IOException("HTTP ${r.code} ${r.message}")
            }
            return bytes
        }
    }

    /**
     * 获取WBI签名密钥（带缓存，有效期12小时）
     */
    suspend fun ensureWbiKeys(): WbiSigner.Keys {
        val cached = wbiKeys
        val nowSec = System.currentTimeMillis() / 1000
        // 如果缓存的密钥未过期，直接返回
        if (cached != null && nowSec - cached.fetchedAtEpochSec < 12 * 60 * 60) return cached

        // 从API获取新的密钥
        val url = "$BASE/x/web-interface/nav"
        val json = getJson(url)
        val data = json.optJSONObject("data") ?: JSONObject()
        val wbi = data.optJSONObject("wbi_img") ?: JSONObject()
        val imgUrl = wbi.optString("img_url", "")
        val subUrl = wbi.optString("sub_url", "")
        // 从URL中提取密钥（文件名去除扩展名）
        val imgKey = imgUrl.substringAfterLast('/').substringBefore('.')
        val subKey = subUrl.substringAfterLast('/').substringBefore('.')
        val keys = WbiSigner.Keys(imgKey = imgKey, subKey = subKey, fetchedAtEpochSec = nowSec)
        wbiKeys = keys
        AppLog.i(TAG, "ensureWbiKeys imgKey=${imgKey.take(6)} subKey=${subKey.take(6)} isLogin=${data.optBoolean("isLogin")}")
        return keys
    }

    /**
     * 为URL添加查询参数
     */
    fun withQuery(url: String, params: Map<String, String>): String {
        val httpUrl = url.toHttpUrl().newBuilder()
        for ((k, v) in params) httpUrl.addQueryParameter(k, v)
        return httpUrl.build().toString()
    }

    /**
     * 生成带WBI签名的URL（相对路径）
     */
    fun signedWbiUrl(path: String, params: Map<String, String>, keys: WbiSigner.Keys, nowEpochSec: Long = System.currentTimeMillis() / 1000): String {
        val base = "$BASE$path"
        val signed = WbiSigner.signQuery(params, keys, nowEpochSec)
        return withQuery(base, signed)
    }

    /**
     * 生成带WBI签名的URL（绝对路径）
     */
    fun signedWbiUrlAbsolute(url: String, params: Map<String, String>, keys: WbiSigner.Keys, nowEpochSec: Long = System.currentTimeMillis() / 1000): String {
        val signed = WbiSigner.signQuery(params, keys, nowEpochSec)
        return withQuery(url, signed)
    }
}
