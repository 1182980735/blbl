package blbl.cat3399.core.download

import org.json.JSONObject

/**
 * 一条视频下载任务。持久化到 DownloadTaskStore，供下载记录页展示。
 */
data class DownloadTask(
    val id: String,
    val bvid: String,
    val cid: Long,
    val epId: Long?,
    val title: String,
    val qnLabel: String,
    /** 清晰度 qn（重试时使用）。 */
    val qn: Int,
    val status: Status,
    /** 0..100；未知时为 -1。 */
    val progress: Int,
    /** 当前阶段文案，如“下载视频 45%”。 */
    val stageText: String?,
    /** 完成后保存的展示路径，如 Download/blbl/xxx.mp4。 */
    val savedPath: String?,
    /** 完成后保存的 MediaStore Uri（旧系统可能为空）。 */
    val savedUri: String?,
    /** 失败原因。 */
    val error: String?,
    val createdAt: Long,
    val updatedAt: Long,
) {
    val isFinished: Boolean
        get() = status == Status.COMPLETED || status == Status.FAILED || status == Status.CANCELED

    val isRunning: Boolean
        get() = status == Status.QUEUED ||
            status == Status.DOWNLOADING ||
            status == Status.MERGING ||
            status == Status.SAVING

    fun toJson(): JSONObject =
        JSONObject()
            .put("id", id)
            .put("bvid", bvid)
            .put("cid", cid)
            .put("epId", epId ?: -1L)
            .put("title", title)
            .put("qnLabel", qnLabel)
            .put("qn", qn)
            .put("status", status.name)
            .put("progress", progress)
            .put("stageText", stageText)
            .put("savedPath", savedPath)
            .put("savedUri", savedUri)
            .put("error", error)
            .put("createdAt", createdAt)
            .put("updatedAt", updatedAt)

    companion object {
        fun fromJson(obj: JSONObject): DownloadTask =
            DownloadTask(
                id = obj.optString("id"),
                bvid = obj.optString("bvid"),
                cid = obj.optLong("cid", -1L),
                epId = obj.optLong("epId", -1L).takeIf { it > 0L },
                title = obj.optString("title"),
                qnLabel = obj.optString("qnLabel"),
                qn = obj.optInt("qn", 0),
                status = runCatching { Status.valueOf(obj.optString("status")) }.getOrDefault(Status.FAILED),
                progress = obj.optInt("progress", -1),
                stageText = obj.optString("stageText").takeIf { it.isNotBlank() && it != "null" },
                savedPath = obj.optString("savedPath").takeIf { it.isNotBlank() && it != "null" },
                savedUri = obj.optString("savedUri").takeIf { it.isNotBlank() && it != "null" },
                error = obj.optString("error").takeIf { it.isNotBlank() && it != "null" },
                createdAt = obj.optLong("createdAt"),
                updatedAt = obj.optLong("updatedAt"),
            )
    }

    enum class Status {
        QUEUED,
        DOWNLOADING,
        MERGING,
        SAVING,
        COMPLETED,
        FAILED,
        CANCELED,
    }
}
