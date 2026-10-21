package blbl.cat3399.core.download

import android.content.Context
import android.content.SharedPreferences
import blbl.cat3399.core.log.AppLog
import org.json.JSONArray

/**
 * 下载任务持久化存储（SharedPreferences + JSON）。
 *
 * 下载服务每完成一个阶段/状态变化会更新这里；下载记录页从这里读取。
 * 所有方法线程安全。
 */
object DownloadTaskStore {
    private const val TAG = "DownloadStore"
    private const val PREFS_NAME = "blbl_downloads"
    private const val KEY_TASKS = "tasks"

    /** 最多保留多少条记录，避免无限增长。 */
    private const val MAX_RECORDS = 200

    private val lock = Any()
    private val cache = LinkedHashMap<String, DownloadTask>()
    private var prefs: SharedPreferences? = null
    private var loaded = false

    fun init(context: Context) {
        synchronized(lock) {
            if (loaded) return
            val appContext = context.applicationContext
            prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            loadLocked()
            loaded = true
        }
    }

    fun all(): List<DownloadTask> =
        synchronized(lock) {
            cache.values.sortedByDescending { it.createdAt }
        }

    fun get(id: String): DownloadTask? = synchronized(lock) { cache[id] }

    fun upsert(task: DownloadTask) {
        synchronized(lock) {
            cache[task.id] = task
            trimLocked()
            persistLocked()
        }
    }

    fun update(
        id: String,
        transform: (DownloadTask) -> DownloadTask,
    ): DownloadTask? =
        synchronized(lock) {
            val current = cache[id] ?: return null
            val next = transform(current).copy(updatedAt = System.currentTimeMillis())
            cache[id] = next
            persistLocked()
            next
        }

    fun remove(id: String) {
        synchronized(lock) {
            cache.remove(id)
            persistLocked()
        }
    }

    fun clearFinished() {
        synchronized(lock) {
            cache.values.removeAll { it.isFinished }
            persistLocked()
        }
    }

    fun clearAll() {
        synchronized(lock) {
            cache.clear()
            persistLocked()
        }
    }

    /**
     * 进程启动时调用：把上次进程被系统回收时残留的“进行中”任务标记为失败，
     * 避免下载记录页一直显示假进度。
     */
    fun recoverInterruptedTasks() {
        synchronized(lock) {
            val now = System.currentTimeMillis()
            var changed = false
            cache.values.filter { it.isRunning }.forEach { task ->
                cache[task.id] =
                    task.copy(
                        status = DownloadTask.Status.FAILED,
                        progress = -1,
                        stageText = "已中断",
                        error = "应用进程被系统回收，任务已中断（可重试）",
                        updatedAt = now,
                    )
                changed = true
            }
            if (changed) persistLocked()
        }
    }

    private fun loadLocked() {
        val raw = prefs?.getString(KEY_TASKS, null)?.takeIf { it.isNotBlank() } ?: return
        runCatching {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val task = DownloadTask.fromJson(obj)
                if (task.id.isNotBlank()) cache[task.id] = task
            }
        }.onFailure { AppLog.w(TAG, "load failed", it) }
    }

    private fun persistLocked() {
        val p = prefs ?: return
        runCatching {
            val array = JSONArray()
            cache.values.forEach { array.put(it.toJson()) }
            p.edit().putString(KEY_TASKS, array.toString()).apply()
        }.onFailure { AppLog.w(TAG, "persist failed", it) }
    }

    private fun trimLocked() {
        if (cache.size <= MAX_RECORDS) return
        val removable =
            cache.values
                .filter { it.isFinished }
                .sortedBy { it.updatedAt }
                .take(cache.size - MAX_RECORDS)
        removable.forEach { cache.remove(it.id) }
    }
}
