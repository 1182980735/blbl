package blbl.cat3399.core.api

import blbl.cat3399.core.model.LiveAreaParent
import blbl.cat3399.core.model.LiveRoomCard
import blbl.cat3399.core.model.LiveSuperChat
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.net.WebCookieMaintainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 直播相关API内部封装类
 *
 * 提供直播功能相关的API封装，包括：
 * - 直播分区列表
 * - 推荐直播间
 * - 指定分区直播间列表
 * - 关注直播间列表
 * - 直播间信息
 * - 直播播放地址
 * - 直播弹幕服务器信息
 */
internal object LiveApi {
    /** 直播分区缓存时间（12小时） */
    private const val LIVE_AREAS_CACHE_TTL_MS = 12 * 60 * 60 * 1000L
    /** 直播原始流索引m3u8路径匹配正则 */
    private val LIVE_ORIGIN_INDEX_M3U8_REGEX = Regex("""(/live-bvc/\d+/live_\d+_\d+)(?:_[^/]+)?(/index\.m3u8)$""")
    /** 直播原始流m3u8路径匹配正则 */
    private val LIVE_ORIGIN_M3U8_REGEX = Regex("""(/live-bvc/\d+/live_\d+_\d+)(?:_[^/\.]+)?(\.m3u8)$""")
    /**
     * 已知可用的原始流主机列表（用于高码率直播）
     *
     * Issue #35: 优先使用已知可用的原始流主机，而不是重写每个API主机。
     * 保持列表精简，便于扩展。
     */
    private val LIVE_ORIGIN_HOST_OVERRIDES = listOf("https://d1--cn-gotcha204.bilivideo.com")

    /** 直播分区缓存数据类 */
    private data class LiveAreasCache(
        val fetchedAtMs: Long,
        val items: List<LiveAreaParent>,
    )

    /** 直播分区缓存实例 */
    @Volatile private var liveAreasCache: LiveAreasCache? = null

    /** JSON对象抽象接口，用于统一解析不同来源的JSON数据 */
    internal interface JsonObj {
        fun optString(name: String, fallback: String): String
        fun optLong(name: String): Long
        fun optLong(name: String, fallback: Long): Long
        fun optInt(name: String, fallback: Int): Int
    }

    /** JSONObject的JsonObj接口实现 */
    private class OrgJsonObj(
        private val obj: JSONObject,
    ) : JsonObj {
        override fun optString(name: String, fallback: String): String = obj.optString(name, fallback)
        override fun optLong(name: String): Long = obj.optLong(name)
        override fun optLong(name: String, fallback: Long): Long = obj.optLong(name, fallback)
        override fun optInt(name: String, fallback: Int): Int = obj.optInt(name, fallback)
    }

    /**
     * 获取直播分区列表
     *
     * 支持缓存机制，默认缓存12小时。
     *
     * @param force 是否强制刷新缓存
     * @return 直播父分区列表
     * @throws BiliApiException API调用失败时抛出
     */
    suspend fun liveAreas(force: Boolean = false): List<LiveAreaParent> {
        val now = System.currentTimeMillis()
        val cached = liveAreasCache
        if (!force && cached != null && now - cached.fetchedAtMs < LIVE_AREAS_CACHE_TTL_MS) {
            return cached.items
        }
        val json = BiliClient.getJson("https://api.live.bilibili.com/room/v1/Area/getList")
        val code = json.optInt("code", 0)
        if (code != 0) {
            val msg = json.optString("message", json.optString("msg", ""))
            throw BiliApiException(apiCode = code, apiMessage = msg)
        }
        val data = json.optJSONArray("data") ?: JSONArray()
        val areas = withContext(Dispatchers.Default) { parseLiveAreas(data) }
        liveAreasCache = LiveAreasCache(fetchedAtMs = now, items = areas)
        return areas
    }

    /**
     * 获取推荐直播间列表
     *
     * 优先尝试从index接口获取，如果失败或返回空列表则回退到webMain接口。
     * 该接口是公开接口，参数保持最小化以避免额外的风控。
     *
     * @param page 页码，默认1
     * @return 推荐直播间列表
     */
    suspend fun liveRecommend(page: Int = 1): List<LiveRoomCard> {
        val p = page.coerceAtLeast(1)
        val listFromIndex = runCatching { liveRecommendFromIndex(page = p) }.getOrNull()
        if (!listFromIndex.isNullOrEmpty()) return listFromIndex
        return liveRecommendFromWebMain(page = p)
    }

    /**
     * 从index接口获取推荐直播间（主接口）
     *
     * @param page 页码
     * @return 推荐直播间列表
     * @throws BiliApiException API调用失败时抛出
     */
    private suspend fun liveRecommendFromIndex(page: Int): List<LiveRoomCard> {
        val url =
            BiliClient.withQuery(
                "https://api.live.bilibili.com/xlive/web-interface/v1/index/getList",
                mapOf(
                    "platform" to "web",
                    "page" to page.coerceAtLeast(1).toString(),
                ),
            )
        val json = BiliClient.getJson(url)
        val code = json.optInt("code", 0)
        if (code != 0) {
            val msg = json.optString("message", json.optString("msg", ""))
            throw BiliApiException(apiCode = code, apiMessage = msg)
        }
        val data = json.optJSONObject("data") ?: JSONObject()
        val roomList = data.optJSONArray("room_list") ?: JSONArray()
        var picked: JSONArray? = null
        for (i in 0 until roomList.length()) {
            val obj = roomList.optJSONObject(i) ?: continue
            val mi = obj.optJSONObject("module_info") ?: JSONObject()
            val id = mi.optInt("id", 0)
            val title = mi.optString("title", "").trim()
            if (id == 3 || title == "推荐直播") {
                picked = obj.optJSONArray("list") ?: JSONArray()
                break
            }
        }
        val list = picked ?: JSONArray()
        return withContext(Dispatchers.Default) { parseLiveRecommendRooms(list) }
    }

    /**
     * 从webMain接口获取推荐直播间（备用接口）
     *
     * @param page 页码
     * @return 推荐直播间列表
     * @throws BiliApiException API调用失败时抛出
     */
    private suspend fun liveRecommendFromWebMain(page: Int): List<LiveRoomCard> {
        val url =
            BiliClient.withQuery(
                "https://api.live.bilibili.com/xlive/web-interface/v1/webMain/getMoreRecList",
                buildMap {
                    put("platform", "web")
                    put("page", page.coerceAtLeast(1).toString())
                },
            )
        val json = BiliClient.getJson(url)
        val code = json.optInt("code", 0)
        if (code != 0) {
            val msg = json.optString("message", json.optString("msg", ""))
            throw BiliApiException(apiCode = code, apiMessage = msg)
        }
        val list = json.optJSONObject("data")?.optJSONArray("recommend_room_list") ?: JSONArray()
        return withContext(Dispatchers.Default) { parseLiveRecommendRooms(list) }
    }

    /**
     * 获取指定分区的直播间列表
     *
     * @param parentAreaId 父分区ID
     * @param areaId 子分区ID
     * @param page 页码，默认1
     * @param pageSize 每页大小，默认30，范围1-60
     * @param sortType 排序方式，默认online（在线人数）
     * @return 直播间列表
     * @throws BiliApiException API调用失败时抛出
     */
    suspend fun liveAreaRooms(
        parentAreaId: Int,
        areaId: Int,
        page: Int = 1,
        pageSize: Int = 30,
        sortType: String = "online",
    ): List<LiveRoomCard> {
        val url =
            BiliClient.withQuery(
                "https://api.live.bilibili.com/room/v1/Area/getRoomList",
                buildMap {
                    put("parent_area_id", parentAreaId.coerceAtLeast(0).toString())
                    put("area_id", areaId.coerceAtLeast(0).toString())
                    put("page", page.coerceAtLeast(1).toString())
                    put("page_size", pageSize.coerceIn(1, 60).toString())
                    put("sort_type", sortType.trim())
                },
            )
        val json = BiliClient.getJson(url)
        val code = json.optInt("code", 0)
        if (code != 0) {
            val msg = json.optString("message", json.optString("msg", ""))
            throw BiliApiException(apiCode = code, apiMessage = msg)
        }
        val data = json.optJSONArray("data") ?: JSONArray()
        return withContext(Dispatchers.Default) { parseLiveAreaRooms(data) }
    }

    /**
     * 获取关注的直播间列表
     *
     * 需要登录（SESSDATA）才能获取。未登录时返回空列表。
     *
     * @param page 页码，默认1
     * @param pageSize 每页大小，默认10，范围1-10
     * @return 关注直播间分页结果
     * @throws BiliApiException API调用失败时抛出
     */
    suspend fun liveFollowing(
        page: Int = 1,
        pageSize: Int = 10,
    ): BiliApi.HasMorePage<LiveRoomCard> {
        if (!BiliClient.cookies.hasSessData()) {
            return BiliApi.HasMorePage(items = emptyList(), page = page.coerceAtLeast(1), hasMore = false, total = 0)
        }
        WebCookieMaintainer.ensureHealthyForPlay()
        val url =
            BiliClient.withQuery(
                "https://api.live.bilibili.com/xlive/web-ucenter/user/following",
                mapOf(
                    "page" to page.coerceAtLeast(1).toString(),
                    "page_size" to pageSize.coerceIn(1, 10).toString(),
                    "ignoreRecord" to "1",
                    "hit_ab" to "true",
                ),
            )
        val json = BiliClient.getJson(url)
        val code = json.optInt("code", 0)
        if (code != 0) {
            val msg = json.optString("message", json.optString("msg", ""))
            throw BiliApiException(apiCode = code, apiMessage = msg)
        }
        val data = json.optJSONObject("data") ?: JSONObject()
        val totalPage = data.optInt("totalPage", 1).coerceAtLeast(1)
        val list = data.optJSONArray("list") ?: JSONArray()
        val items = withContext(Dispatchers.Default) { parseLiveFollowingRooms(list) }
        val p = page.coerceAtLeast(1)
        return BiliApi.HasMorePage(items = items, page = p, hasMore = p < totalPage, total = data.optInt("count", 0))
    }

    /**
     * 获取直播间详细信息
     *
     * @param roomId 房间ID
     * @return 直播间信息
     * @throws BiliApiException API调用失败时抛出
     */
    suspend fun liveRoomInfo(roomId: Long): BiliApi.LiveRoomInfo {
        val url =
            BiliClient.withQuery(
                "https://api.live.bilibili.com/room/v1/Room/get_info",
                mapOf("room_id" to roomId.toString()),
            )
        val json = BiliClient.getJson(url)
        val code = json.optInt("code", 0)
        if (code != 0) {
            val msg = json.optString("message", json.optString("msg", ""))
            throw BiliApiException(apiCode = code, apiMessage = msg)
        }
        val data = json.optJSONObject("data") ?: JSONObject()
        return BiliApi.LiveRoomInfo(
            roomId = data.optLong("room_id").takeIf { it > 0 } ?: roomId,
            uid = data.optLong("uid").takeIf { it > 0 } ?: 0L,
            uname = data.optString("uname", "").trim().takeIf { it.isNotBlank() },
            faceUrl = data.optString("face", "").trim().takeIf { it.isNotBlank() },
            title = data.optString("title", ""),
            liveStatus = data.optInt("live_status", 0),
            areaName = data.optString("area_name", "").trim().takeIf { it.isNotBlank() },
            parentAreaName = data.optString("parent_area_name", "").trim().takeIf { it.isNotBlank() },
        )
    }

    /**
     * 执行进入直播间操作（用于记录访问记录）
     *
     * @param roomId 房间ID
     * @throws BiliApiException API调用失败时抛出
     */
    suspend fun liveRoomEntryAction(roomId: Long) {
        if (roomId <= 0L) error("live_room_entry_invalid_room_id")
        val csrf = BiliClient.cookies.getCookieValue("bili_jct").orEmpty().trim()
        if (csrf.isBlank()) throw BiliApiException(apiCode = -111, apiMessage = "missing_csrf")
        val url =
            BiliClient.withQuery(
                "https://api.live.bilibili.com/xlive/web-room/v1/index/roomEntryAction",
                mapOf("csrf" to csrf),
            )
        val json =
            BiliClient.postFormJson(
                url,
                form =
                    mapOf(
                        "room_id" to roomId.toString(),
                        "platform" to "pc",
                    ),
                headers =
                    mapOf(
                        "Referer" to "https://live.bilibili.com/$roomId",
                        "Origin" to "https://live.bilibili.com",
                    ),
            )
        val code = json.optInt("code", 0)
        if (code != 0) {
            val msg = json.optString("message", json.optString("msg", json.optString("error", "")))
            throw BiliApiException(apiCode = code, apiMessage = msg)
        }
    }

    /**
     * 获取直播播放地址
     *
     * B站直播间的可用直播流组合会动态变化；不少房间只返回 http_hls/fmp4(m3u8)。
     * 为提高兼容性，按官方文档传多选，让服务端返回可用集合，再在客户端挑最合适的组合。
     *
     * 默认行为：优先选择 http_stream (flv/fmp4)，因为其启动延迟更低且稳定性更好。
     * 仅当用户显式启用高码率直播模式时，才优先选择 http_hls。
     *
     * @param roomId 房间ID
     * @param qn 清晰度等级
     * @param highBitrateEnabled 是否开启高码率
     * @return 直播播放地址信息
     * @throws BiliApiException API调用失败时抛出
     */
    suspend fun livePlayUrl(
        roomId: Long,
        qn: Int,
        highBitrateEnabled: Boolean,
    ): BiliApi.LivePlayUrl {
        val url =
            BiliClient.withQuery(
                "https://api.live.bilibili.com/xlive/web-room/v2/index/getRoomPlayInfo",
                mapOf(
                    "room_id" to roomId.toString(),
                    "protocol" to "0,1",
                    "format" to "0,1,2",
                    "codec" to "0,1",
                    "qn" to qn.coerceAtLeast(1).toString(),
                ),
            )
        val json = BiliClient.getJson(url)
        val code = json.optInt("code", 0)
        if (code != 0) {
            val msg = json.optString("message", json.optString("msg", ""))
            throw BiliApiException(apiCode = code, apiMessage = msg)
        }
        val data = json.optJSONObject("data") ?: JSONObject()
        val playurl = data.optJSONObject("playurl_info")?.optJSONObject("playurl") ?: JSONObject()

        val qnDesc = HashMap<Int, String>()
        val descArr = playurl.optJSONArray("g_qn_desc") ?: JSONArray()
        for (i in 0 until descArr.length()) {
            val obj = descArr.optJSONObject(i) ?: continue
            val q = obj.optInt("qn", 0).takeIf { it > 0 } ?: continue
            val d = obj.optString("desc", "").trim()
            if (d.isNotBlank()) qnDesc[q] = d
        }

        val streamArr = playurl.optJSONArray("stream") ?: JSONArray()
        val protocolOrder =
            if (highBitrateEnabled) {
                listOf("http_hls", "http_stream")
            } else {
                listOf("http_stream", "http_hls")
            }
        val formatOrderForProtocol =
            mapOf(
                "http_stream" to listOf("flv", "fmp4", "ts"),
                "http_hls" to listOf("fmp4", "ts", "flv"),
            )
        val codecOrder = listOf("avc", "hevc")

        fun normalize(v: String): String = v.trim().lowercase()
        fun pickCodec(protocol: String, format: String, codec: String): JSONObject? {
            val targetProtocol = normalize(protocol)
            val targetFormat = normalize(format)
            val targetCodec = normalize(codec)
            for (i in 0 until streamArr.length()) {
                val stream = streamArr.optJSONObject(i) ?: continue
                val protocolName = normalize(stream.optString("protocol_name", ""))
                if (protocolName != targetProtocol) continue
                val formats = stream.optJSONArray("format") ?: continue
                for (j in 0 until formats.length()) {
                    val fmt = formats.optJSONObject(j) ?: continue
                    val formatName = normalize(fmt.optString("format_name", ""))
                    if (formatName != targetFormat) continue
                    val codecs = fmt.optJSONArray("codec") ?: continue
                    for (k in 0 until codecs.length()) {
                        val c = codecs.optJSONObject(k) ?: continue
                        val codecName = normalize(c.optString("codec_name", ""))
                        if (codecName != targetCodec) continue
                        val baseUrl = c.optString("base_url", "").trim()
                        val urlInfo = c.optJSONArray("url_info")
                        if (baseUrl.isBlank() || urlInfo == null || urlInfo.length() <= 0) continue
                        return c
                    }
                }
            }
            return null
        }

        var pickedCodec: JSONObject? = null
        pick@ for (codecName in codecOrder) {
            for (protocol in protocolOrder) {
                val formats = formatOrderForProtocol[protocol].orEmpty()
                for (fmt in formats) {
                    pickedCodec = pickCodec(protocol = protocol, format = fmt, codec = codecName)
                    if (pickedCodec != null) break@pick
                }
            }
        }
        val codec = pickedCodec ?: JSONObject()
        val currentQn = codec.optInt("current_qn", 0).takeIf { it > 0 } ?: qn
        val accept = codec.optJSONArray("accept_qn") ?: JSONArray()
        val acceptQn =
            buildList {
                for (i in 0 until accept.length()) {
                    val v = accept.optInt(i, 0).takeIf { it > 0 } ?: continue
                    add(v)
                }
            }.distinct()
        val baseUrl = codec.optString("base_url", "").trim()
        val urlInfo = codec.optJSONArray("url_info") ?: JSONArray()
        val enableHighBitrate = highBitrateEnabled
        val originUrls = ArrayList<String>(LIVE_ORIGIN_HOST_OVERRIDES.size + 1)
        val signedUrls = ArrayList<String>(urlInfo.length() + 2)
        val seen = HashSet<String>(urlInfo.length() * 2 + LIVE_ORIGIN_HOST_OVERRIDES.size * 2)

        if (enableHighBitrate && baseUrl.isNotBlank()) {
            for (overrideHost in LIVE_ORIGIN_HOST_OVERRIDES) {
                val alt = tryBuildLiveOriginHlsUrl(host = overrideHost, baseUrl = baseUrl)
                if (!alt.isNullOrBlank() && seen.add(alt)) originUrls.add(alt)
            }
        }

        for (i in 0 until urlInfo.length()) {
            val obj = urlInfo.optJSONObject(i) ?: continue
            val host = obj.optString("host", "").trim()
            val extra = obj.optString("extra", "").trim()
            if (host.isBlank() || baseUrl.isBlank()) continue

            val signed = host + baseUrl + extra
            if (signed.isNotBlank() && seen.add(signed)) signedUrls.add(signed)
        }

        val lines =
            buildList {
                var order = 1
                if (enableHighBitrate) {
                    for (u in originUrls) {
                        add(BiliApi.LivePlayLine(order = order, url = u))
                        order += 1
                    }
                }
                for (u in signedUrls) {
                    add(BiliApi.LivePlayLine(order = order, url = u))
                    order += 1
                }
            }

        return BiliApi.LivePlayUrl(currentQn = currentQn, acceptQn = acceptQn, qnDesc = qnDesc, lines = lines)
    }

    /**
     * 尝试构建直播原始流HLS地址
     *
     * 使用已知可用的原始流主机构建高码率直播地址。
     *
     * @param host 原始流主机地址
     * @param baseUrl 基础路径
     * @return 构建的HLS地址，无效输入返回null
     */
    internal fun tryBuildLiveOriginHlsUrl(host: String, baseUrl: String): String? {
        val h = host.trim().trimEnd('/')
        val base = baseUrl.trim()
        if (h.isBlank() || base.isBlank()) return null
        val path = base.substringBefore('?').trim()
        val lower = path.lowercase()
        if (!lower.endsWith(".m3u8")) return null
        return h + rewriteLiveOriginM3u8Path(path)
    }

    /**
     * 重写直播原始流m3u8路径
     *
     * 将带后缀的m3u8路径重写为原始流路径，以获取更高码率的直播流。
     *
     * @param path 原始路径
     * @return 重写后的路径
     */
    internal fun rewriteLiveOriginM3u8Path(path: String): String {
        val p = path.trim()
        val lower = p.lowercase()
        return when {
            lower.endsWith("/index.m3u8") ->
                LIVE_ORIGIN_INDEX_M3U8_REGEX.replace(p) { m ->
                    m.groupValues[1] + m.groupValues[2]
                }

            lower.endsWith(".m3u8") ->
                LIVE_ORIGIN_M3U8_REGEX.replace(p) { m ->
                    m.groupValues[1] + m.groupValues[2]
                }

            else -> p
        }
    }

    /**
     * 获取直播弹幕服务器信息
     *
     * @param roomId 房间ID
     * @return 弹幕服务器信息，包含token和服务器列表
     * @throws BiliApiException API调用失败时抛出
     */
    suspend fun liveDanmuInfo(roomId: Long): BiliApi.LiveDanmuInfo {
        WebCookieMaintainer.ensureWebFingerprintCookies()
        val keys = BiliClient.ensureWbiKeys()
        val url =
            BiliClient.signedWbiUrlAbsolute(
                "https://api.live.bilibili.com/xlive/web-room/v1/index/getDanmuInfo",
                params =
                    mapOf(
                        "id" to roomId.toString(),
                        "type" to "0",
                        "web_location" to "444.8",
                    ),
                keys = keys,
            )
        val json =
            BiliClient.getJson(
                url,
                headers =
                    mapOf(
                        "Referer" to "https://live.bilibili.com/",
                        "Origin" to "https://live.bilibili.com",
                    ),
            )
        val code = json.optInt("code", 0)
        if (code != 0) {
            val msg = json.optString("message", json.optString("msg", ""))
            throw BiliApiException(apiCode = code, apiMessage = msg)
        }
        val data = json.optJSONObject("data") ?: JSONObject()
        val token = data.optString("token", "").trim()
        val hostList = data.optJSONArray("host_list") ?: JSONArray()
        val hosts =
            buildList {
                for (i in 0 until hostList.length()) {
                    val obj = hostList.optJSONObject(i) ?: continue
                    val host = obj.optString("host", "").trim()
                    val wssPort = obj.optInt("wss_port", 0)
                    val wsPort = obj.optInt("ws_port", 0)
                    if (host.isBlank() || (wssPort <= 0 && wsPort <= 0)) continue
                    add(BiliApi.LiveDanmuHost(host = host, wssPort = wssPort, wsPort = wsPort))
                }
            }.distinctBy { "${it.host}:${it.wssPort}:${it.wsPort}" }
        return BiliApi.LiveDanmuInfo(token = token, hosts = hosts)
    }

    /**
     * 获取直播 SC（Super Chat）消息列表
     *
     * @param roomId 直播间ID
     * @return SC 消息列表
     */
    suspend fun liveSuperChats(roomId: Long): List<LiveSuperChat> {
        if (roomId <= 0L) return emptyList()
        val url =
            BiliClient.withQuery(
                "https://api.live.bilibili.com/av/v1/SuperChat/getMessageList",
                mapOf("room_id" to roomId.toString()),
            )
        val json =
            BiliClient.getJson(
                url,
                headers =
                    mapOf(
                        "Referer" to "https://live.bilibili.com/$roomId",
                        "Origin" to "https://live.bilibili.com",
                    ),
            )
        val code = json.optInt("code", 0)
        if (code != 0) {
            val msg = json.optString("message", json.optString("msg", ""))
            throw BiliApiException(apiCode = code, apiMessage = msg)
        }
        val list = json.optJSONObject("data")?.optJSONArray("list") ?: return emptyList()
        return withContext(Dispatchers.Default) { parseLiveSuperChats(list) }
    }

    /**
     * 解析 SC 消息列表
     *
     * @param source SC 消息 JSON 数组
     * @return 解析后的 SC 消息列表
     */
    internal fun parseLiveSuperChats(source: JSONArray): List<LiveSuperChat> =
        buildList {
            for (index in 0 until source.length()) {
                val item = source.optJSONObject(index) ?: continue
                LiveSuperChatJsonParser.parse(item)?.let(::add)
            }
        }

    /**
     * 解析直播分区列表
     *
     * @param arr 分区数据JSON数组
     * @return 解析后的父分区列表
     */
    private fun parseLiveAreas(arr: JSONArray): List<LiveAreaParent> {
        val out = ArrayList<LiveAreaParent>(arr.length())
        for (i in 0 until arr.length()) {
            val p = arr.optJSONObject(i) ?: continue
            val id = p.optInt("id", 0)
            val name = p.optString("name", "").trim()
            if (id <= 0 || name.isBlank()) continue
            val children = ArrayList<LiveAreaParent.Child>()
            val list = p.optJSONArray("list") ?: JSONArray()
            for (j in 0 until list.length()) {
                val c = list.optJSONObject(j) ?: continue
                val cid = c.optString("id", "").trim().toIntOrNull() ?: continue
                val parentId = c.optString("parent_id", "").trim().toIntOrNull() ?: id
                val cname = c.optString("name", "").trim()
                if (cname.isBlank()) continue
                children.add(
                    LiveAreaParent.Child(
                        id = cid,
                        parentId = parentId,
                        name = cname,
                        hot = c.optInt("hot_status", 0) == 1,
                        coverUrl = c.optString("pic", "").trim().takeIf { it.isNotBlank() },
                    ),
                )
            }
            out.add(LiveAreaParent(id = id, name = name, children = children))
        }
        return out
    }

    /**
     * 解析推荐直播间列表
     *
     * @param arr 直播间数据JSON数组
     * @return 解析后的直播间列表
     */
    private fun parseLiveRecommendRooms(arr: JSONArray): List<LiveRoomCard> {
        val out = ArrayList<LiveRoomCard>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val roomId = obj.optLong("roomid").takeIf { it > 0 } ?: continue
            val online = obj.optLong("online").takeIf { it > 0 } ?: 0L
            out.add(
                LiveRoomCard(
                    roomId = roomId,
                    uid = obj.optLong("uid").takeIf { it > 0 } ?: 0L,
                    title = obj.optString("title", ""),
                    uname = obj.optString("uname", ""),
                    coverUrl = obj.optString("cover", "").trim(),
                    faceUrl = obj.optString("face", "").trim().takeIf { it.isNotBlank() },
                    online = online,
                    isLive = true,
                    parentAreaId = obj.optInt("area_v2_parent_id").takeIf { it > 0 },
                    parentAreaName = obj.optString("area_v2_parent_name", "").trim().takeIf { it.isNotBlank() },
                    areaId = obj.optInt("area_v2_id").takeIf { it > 0 },
                    areaName = obj.optString("area_v2_name", "").trim().takeIf { it.isNotBlank() },
                    keyframe = obj.optString("keyframe", "").trim().takeIf { it.isNotBlank() },
                ),
            )
        }
        return out
    }

    /**
     * 解析分区直播间列表
     *
     * @param arr 直播间数据JSON数组
     * @return 解析后的直播间列表
     */
    private fun parseLiveAreaRooms(arr: JSONArray): List<LiveRoomCard> {
        val out = ArrayList<LiveRoomCard>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val roomId = obj.optLong("roomid").takeIf { it > 0 } ?: continue
            val online = obj.optLong("online").takeIf { it > 0 } ?: 0L
            val coverUrl = obj.optString("user_cover").ifBlank { obj.optString("cover") }.trim()
            val areaId = obj.optInt("area_v2_id", obj.optInt("area_id", 0)).takeIf { it > 0 }
            val parentAreaId = obj.optInt("area_v2_parent_id", obj.optInt("parent_id", 0)).takeIf { it > 0 }
            out.add(
                LiveRoomCard(
                    roomId = roomId,
                    uid = obj.optLong("uid").takeIf { it > 0 } ?: 0L,
                    title = obj.optString("title", ""),
                    uname = obj.optString("uname", ""),
                    coverUrl = coverUrl,
                    faceUrl = obj.optString("face", "").trim().takeIf { it.isNotBlank() },
                    online = online,
                    isLive = true,
                    parentAreaId = parentAreaId,
                    parentAreaName =
                        obj
                            .optString("area_v2_parent_name", obj.optString("parent_name", ""))
                            .trim()
                            .takeIf { it.isNotBlank() },
                    areaId = areaId,
                    areaName =
                        obj
                            .optString("area_v2_name", obj.optString("area_name", ""))
                            .trim()
                            .takeIf { it.isNotBlank() },
                    keyframe = obj.optString("system_cover", "").trim().takeIf { it.isNotBlank() },
                ),
            )
        }
        return out
    }

    /**
     * 解析关注直播间信息
     *
     * @param obj 直播间信息JSON对象
     * @return 解析后的直播间卡片，解析失败返回null
     */
    internal fun parseLiveFollowingRoom(obj: JsonObj): LiveRoomCard? {
        val roomId = obj.optLong("roomid").takeIf { it > 0 } ?: return null
        val online = parseCnCount(obj.optString("text_small", "0").trim())
        val liveStatus = obj.optInt("live_status", 0)
        return LiveRoomCard(
            roomId = roomId,
            uid = obj.optLong("uid").takeIf { it > 0 } ?: 0L,
            title = obj.optString("title", ""),
            uname = obj.optString("uname", ""),
            coverUrl = obj.optString("room_cover", obj.optString("cover_from_user", "")).trim(),
            faceUrl = obj.optString("face", "").trim().takeIf { it.isNotBlank() },
            online = online,
            isLive = liveStatus == 1,
            parentAreaId = obj.optInt("parent_area_id", 0).takeIf { it > 0 },
            parentAreaName = obj.optString("area_v2_parent_name", "").trim().takeIf { it.isNotBlank() },
            areaId = obj.optInt("area_id", 0).takeIf { it > 0 },
            areaName = obj.optString("area_name_v2", obj.optString("area_name", "")).trim().takeIf { it.isNotBlank() },
            keyframe = null,
        )
    }

    /**
     * 解析关注直播间列表
     *
     * @param arr 直播间数据JSON数组
     * @return 解析后的直播间列表
     */
    private fun parseLiveFollowingRooms(arr: JSONArray): List<LiveRoomCard> {
        val out = ArrayList<LiveRoomCard>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            parseLiveFollowingRoom(OrgJsonObj(obj))?.let { out.add(it) }
        }
        return out
    }

    /**
     * 解析中文数字格式的计数文本
     *
     * 支持解析"123"、"12.3万"、"1.2亿"等格式。
     *
     * @param text 原始计数文本
     * @return 解析后的数字
     */
    internal fun parseCnCount(text: String): Long {
        val t = text.trim()
        if (t.isBlank()) return 0L
        val m = Regex("^([0-9]+(?:\\.[0-9]+)?)([万亿]?)$").find(t) ?: return t.toLongOrNull() ?: 0L
        val num = m.groupValues[1].toDoubleOrNull() ?: return 0L
        val unit = m.groupValues[2]
        val mul =
            when (unit) {
                "万" -> 10_000.0
                "亿" -> 100_000_000.0
                else -> 1.0
            }
        return (num * mul).toLong()
    }
}
