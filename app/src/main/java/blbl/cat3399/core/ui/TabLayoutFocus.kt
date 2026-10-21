package blbl.cat3399.core.ui

import android.animation.AnimatorInflater
import android.view.ViewGroup
import blbl.cat3399.R
import com.google.android.material.tabs.TabLayout

/**
 * 为TabLayout启用遥控器DPAD焦点导航支持
 *
 * 主要用于Android TV设备，使TabLayout的每个Tab都可以通过遥控器方向键进行聚焦和切换
 *
 * @param selectOnFocus 是否在聚焦时自动选中对应的Tab（默认true）
 * @param onTabFocused 当Tab获得焦点时的回调，返回Tab的位置索引
 */
fun TabLayout.enableDpadTabFocus(
    selectOnFocus: Boolean = true,
    onTabFocused: ((position: Int) -> Unit)? = null,
) {
    enableDpadTabFocus(selectOnFocusProvider = { selectOnFocus }, onTabFocused = onTabFocused)
}

/**
 * 为TabLayout启用遥控器DPAD焦点导航支持（带动态配置）
 *
 * 与简化版本不同，此版本支持通过lambda动态决定是否在聚焦时选中Tab
 *
 * @param selectOnFocusProvider 返回是否在聚焦时自动选中Tab的lambda
 * @param onTabFocused 当Tab获得焦点时的回调，返回Tab的位置索引
 */
fun TabLayout.enableDpadTabFocus(
    selectOnFocusProvider: () -> Boolean,
    onTabFocused: ((position: Int) -> Unit)? = null,
) {
    // 获取TabLayout内部的TabStrip容器
    val tabStrip = getChildAt(0) as? ViewGroup ?: return

    // 遍历所有Tab，为每个Tab设置焦点支持
    for (i in 0 until tabStrip.childCount) {
        val index = i
        val tabView = tabStrip.getChildAt(i)

        // 启用焦点和点击能力
        tabView.isFocusable = true
        tabView.isClickable = true

        // 设置焦点缩放动画效果
        tabView.stateListAnimator = AnimatorInflater.loadStateListAnimator(context, R.animator.blbl_focus_scale)

        // 设置焦点变化监听器
        tabView.setOnFocusChangeListener { _, hasFocus ->
            // 仅在获得焦点时处理
            if (!hasFocus) return@setOnFocusChangeListener

            // 根据配置决定是否自动选中
            if (selectOnFocusProvider() && selectedTabPosition != index) {
                getTabAt(index)?.select()
            }

            // 通知外部焦点变化回调
            onTabFocused?.invoke(index)
        }
    }
}
