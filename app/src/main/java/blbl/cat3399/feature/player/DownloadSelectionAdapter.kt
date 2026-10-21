package blbl.cat3399.feature.player

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import blbl.cat3399.databinding.ItemDownloadSelectBinding

/**
 * 批量下载的“分 P 选集”适配器：多选，点击整行切换选中状态。
 */
internal class DownloadSelectionAdapter(
    private val items: List<PlayerPlaylistItem>,
    initiallySelected: BooleanArray,
) : RecyclerView.Adapter<DownloadSelectionAdapter.Holder>() {
    private val selected: BooleanArray =
        BooleanArray(items.size) { index -> initiallySelected.getOrElse(index) { true } }

    fun isSelected(index: Int): Boolean = selected.getOrElse(index) { false }

    fun selectedCount(): Int = selected.count { it }

    fun selectedItems(): List<PlayerPlaylistItem> =
        items.filterIndexed { index, _ -> isSelected(index) }

    fun toggle(index: Int) {
        if (index !in selected.indices) return
        selected[index] = !selected[index]
        notifyItemChanged(index)
    }

    fun selectAll(value: Boolean) {
        selected.fill(value)
        notifyDataSetChanged()
    }

    fun allSelected(): Boolean = selected.all { it }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): Holder {
        val binding = ItemDownloadSelectBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(
        holder: Holder,
        position: Int,
    ) {
        holder.bind(items[position], position, isSelected(position))
    }

    inner class Holder(
        private val binding: ItemDownloadSelectBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(
            item: PlayerPlaylistItem,
            position: Int,
            isChecked: Boolean,
        ) {
            val title = item.title?.trim().orEmpty().ifBlank { item.bvid }
            binding.tvTitle.text = "${position + 1}. $title"
            binding.tvSubtitle.text = if (item.cid != null && item.cid > 0L) "P${position + 1}" else item.bvid
            binding.cbSelected.isChecked = isChecked
            binding.root.setOnClickListener { toggle(bindingAdapterPosition) }
        }
    }
}
