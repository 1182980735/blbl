package blbl.cat3399.feature.player

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import blbl.cat3399.core.download.DownloadTask
import blbl.cat3399.databinding.ItemDownloadRecordBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 下载记录列表适配器。
 */
internal class DownloadRecordAdapter(
    private val onClick: (DownloadTask) -> Unit,
    private val onLongClick: (DownloadTask) -> Unit,
) : RecyclerView.Adapter<DownloadRecordAdapter.Holder>() {
    private var items: List<DownloadTask> = emptyList()

    fun submit(list: List<DownloadTask>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): Holder {
        val binding = ItemDownloadRecordBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(
        holder: Holder,
        position: Int,
    ) {
        holder.bind(items[position])
    }

    inner class Holder(
        private val binding: ItemDownloadRecordBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(task: DownloadTask) {
            binding.tvTitle.text = task.title.ifBlank { task.bvid }
            binding.tvStatus.text = statusText(task)
            val path = task.savedPath
            binding.tvPath.text = path ?: task.error.orEmpty()
            binding.tvPath.visibility = if (binding.tvPath.text.isNullOrBlank()) View.GONE else View.VISIBLE
            binding.root.setOnClickListener { onClick(task) }
            binding.root.setOnLongClickListener {
                onLongClick(task)
                true
            }
        }
    }

    companion object {
        private val dateFormat = SimpleDateFormat("MM-dd HH:mm", Locale.US)

        fun statusText(task: DownloadTask): String {
            val time = dateFormat.format(Date(task.createdAt))
            return when (task.status) {
                DownloadTask.Status.QUEUED -> "排队中 · $time"
                DownloadTask.Status.DOWNLOADING,
                DownloadTask.Status.MERGING,
                DownloadTask.Status.SAVING,
                -> "${task.stageText ?: "下载中"} · ${task.qnLabel}"
                DownloadTask.Status.COMPLETED -> "已完成 · ${task.qnLabel} · $time"
                DownloadTask.Status.FAILED -> "失败：${task.error ?: "未知错误"} · $time"
                DownloadTask.Status.CANCELED -> "已取消 · $time"
            }
        }
    }
}
