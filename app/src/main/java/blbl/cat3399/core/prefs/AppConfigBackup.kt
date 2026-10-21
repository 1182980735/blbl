package blbl.cat3399.core.prefs

import blbl.cat3399.BuildConfig
import blbl.cat3399.core.account.AccountSessionStore
import blbl.cat3399.core.net.CookieStore
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 应用配置备份管理器
 *
 * 负责配置文件的导出、解析和应用，支持两种导出模式：
 * - 仅配置：不包含登录凭据，适合分享配置
 * - 配置+凭据：包含Cookie和账户信息，适合设备迁移
 */
object AppConfigBackup {
    /** JSON文件MIME类型 */
    const val JSON_MIME = "application/json"

    /** 配置文件Schema版本 */
    private const val SCHEMA_VERSION = 2
    private const val KEY_SCHEMA = "schema"
    private const val KEY_KIND = "kind"
    private const val KEY_EXPORTED_AT_MS = "exported_at_ms"
    private const val KEY_APP = "app"
    private const val KEY_CONFIG = "config"
    private const val KEY_PREFS = "prefs"
    private const val KEY_CREDENTIALS = "credentials"
    private const val KEY_COOKIES = "cookies"
    private const val KEY_ACCOUNTS = "accounts"

    /** 配置文件类型标识 */
    private const val KIND = "blbl_config_backup"

    /**
     * 导出模式枚举
     */
    enum class ExportMode {
        /** 仅导出配置，不包含登录凭据 */
        CONFIG_ONLY,
        /** 导出配置和登录凭据 */
        CONFIG_WITH_CREDENTIALS,
    }

    /**
     * 准备好的导出数据
     */
    data class PreparedExport(
        val mode: ExportMode,
        val fileName: String,
        val jsonText: String,
    )

    /**
     * 解析后的备份数据
     */
    data class ParsedBackup internal constructor(
        val includesCredentials: Boolean,
        internal val configPrefsJson: JSONObject,
        internal val credentialPrefsJson: JSONObject,
        internal val cookiesJson: JSONObject,
        internal val accountsJson: JSONObject?,
    )

    /**
     * 准备配置导出数据
     *
     * @param prefs 应用偏好设置
     * @param cookies Cookie存储
     * @param accounts 账户会话存储
     * @param mode 导出模式
     * @param nowMs 当前时间戳（毫秒）
     * @return 准备好的导出数据
     */
    fun prepareExport(
        prefs: AppPrefs,
        cookies: CookieStore,
        accounts: AccountSessionStore,
        mode: ExportMode,
        nowMs: Long = System.currentTimeMillis(),
    ): PreparedExport {
        // 实例化懒加载的本地ID，确保备份反映当前有效的本地状态
        prefs.deviceBuvid
        prefs.deviceUuid
        if (mode == ExportMode.CONFIG_WITH_CREDENTIALS) {
            accounts.saveCurrentSessionAsActive(appPrefs = prefs, cookies = cookies)
        }

        return PreparedExport(
            mode = mode,
            fileName = buildFileName(mode = mode, nowMs = nowMs),
            jsonText =
                buildJson(
                    mode = mode,
                    configPrefsJson = prefs.exportConfigSnapshotJson(),
                    credentialPrefsJson = prefs.exportCredentialsSnapshotJson(),
                    cookiesJson = cookies.exportSnapshotJson(includeExpired = false),
                    accountsJson = accounts.exportBackupJson(),
                    nowMs = nowMs,
                ),
        )
    }

    /**
     * 解析备份配置文件
     *
     * @param raw 配置文件原始文本
     * @return 解析后的备份数据
     * @throws IllegalArgumentException 配置文件无效时抛出
     */
    fun parse(raw: String): ParsedBackup {
        val text = raw.trim()
        if (text.isBlank()) error("配置文件为空")

        val root = runCatching { JSONObject(text) }.getOrElse { throw IllegalArgumentException("配置文件不是有效的 JSON") }
        if (root.optInt(KEY_SCHEMA, -1) != SCHEMA_VERSION) {
            error("不支持的配置文件版本")
        }
        if (root.optString(KEY_KIND, "").trim() != KIND) {
            error("不是 blbl 配置文件")
        }

        val config = root.optJSONObject(KEY_CONFIG) ?: error("配置文件缺少 config 字段")
        val credentials =
            if (root.has(KEY_CREDENTIALS)) {
                root.optJSONObject(KEY_CREDENTIALS) ?: error("配置文件 credentials 字段无效")
            } else {
                null
            }

        return ParsedBackup(
            includesCredentials = credentials != null,
            configPrefsJson = config.optJSONObject(KEY_PREFS) ?: error("配置文件缺少 config.prefs 字段"),
            credentialPrefsJson = credentials?.optJSONObject(KEY_PREFS) ?: JSONObject(),
            cookiesJson = credentials?.optJSONObject(KEY_COOKIES) ?: JSONObject(),
            accountsJson = credentials?.optJSONObject(KEY_ACCOUNTS),
        )
    }

    /**
     * 应用备份配置
     *
     * @param parsed 解析后的备份数据
     * @param prefs 应用偏好设置
     * @param cookies Cookie存储
     * @param accounts 账户会话存储
     */
    fun apply(
        parsed: ParsedBackup,
        prefs: AppPrefs,
        cookies: CookieStore,
        accounts: AccountSessionStore,
    ) {
        prefs.replaceConfigFromSnapshotJson(parsed.configPrefsJson)
        if (parsed.includesCredentials) {
            prefs.replaceCredentialsFromSnapshotJson(parsed.credentialPrefsJson)
            cookies.replaceAllFromJson(parsed.cookiesJson, sync = true)
            if (parsed.accountsJson != null) {
                accounts.replaceAllFromBackupJson(parsed.accountsJson)
                accounts.loadActiveAccount(appPrefs = prefs, cookies = cookies)
            } else {
                accounts.replaceWithCurrentSessionIfPresent(appPrefs = prefs, cookies = cookies)
            }
        }
    }

    private fun buildFileName(
        mode: ExportMode,
        nowMs: Long = System.currentTimeMillis(),
    ): String {
        val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        val ts = runCatching { sdf.format(Date(nowMs)) }.getOrNull() ?: nowMs.toString()
        val suffix =
            when (mode) {
                ExportMode.CONFIG_ONLY -> "config"
                ExportMode.CONFIG_WITH_CREDENTIALS -> "config_with_credentials"
            }
        return "blbl_${suffix}_$ts.json"
    }

    private fun buildJson(
        mode: ExportMode,
        configPrefsJson: JSONObject,
        credentialPrefsJson: JSONObject,
        cookiesJson: JSONObject,
        accountsJson: JSONObject,
        nowMs: Long,
    ): String {
        val root =
            JSONObject()
                .put(KEY_SCHEMA, SCHEMA_VERSION)
                .put(KEY_KIND, KIND)
                .put(KEY_EXPORTED_AT_MS, nowMs)
                .put(
                    KEY_APP,
                    JSONObject()
                        .put("package", BuildConfig.APPLICATION_ID)
                        .put("version_name", BuildConfig.VERSION_NAME)
                        .put("version_code", BuildConfig.VERSION_CODE),
                )
                .put(
                    KEY_CONFIG,
                    JSONObject()
                        .put(KEY_PREFS, configPrefsJson),
                )

        if (mode == ExportMode.CONFIG_WITH_CREDENTIALS) {
            root.put(
                KEY_CREDENTIALS,
                JSONObject()
                    .put(KEY_PREFS, credentialPrefsJson)
                    .put(KEY_COOKIES, cookiesJson)
                    .put(KEY_ACCOUNTS, accountsJson),
            )
        }
        return root.toString(2)
    }
}
