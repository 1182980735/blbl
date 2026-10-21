package blbl.cat3399.core.ui

import android.content.Context
import android.os.SystemClock

/**
 * 双击返回键退出处理器
 *
 * 实现"再按一次退出"的经典交互模式，用于防止用户误触返回键导致意外退出
 *
 * @param context 上下文，用于显示提示Toast
 * @param windowMs 两次返回键的时间窗口（毫秒），在该时间内连续按两次返回键才会触发退出
 * @param hintText 第一次按返回键时显示的提示文本（默认"再按一次退出播放器"）
 * @param onFirstBack 第一次按返回键时的回调
 */
internal class DoubleBackToExitHandler(
    private val context: Context,
    private val windowMs: Long,
    private val hintText: String = "再按一次退出播放器",
    private val onFirstBack: () -> Unit = {},
) {
    /** 上次按返回键的时间戳 */
    private var lastBackAtMs: Long = 0L

    /**
     * 判断是否应该退出应用
     *
     * @param enabled 是否启用双击退出功能
     * @return 如果应该退出返回true，否则返回false并显示提示
     */
    fun shouldExit(enabled: Boolean): Boolean {
        // 如果未启用，直接允许退出
        if (!enabled) return true

        val now = SystemClock.uptimeMillis()
        // 判断是否在时间窗口内按下第二次返回键
        val isSecond = now - lastBackAtMs <= windowMs

        if (isSecond) {
            // 在时间窗口内，允许退出
            return true
        }

        // 第一次按返回键，记录时间并显示提示
        lastBackAtMs = now
        AppToast.show(context, hintText)
        onFirstBack()
        return false
    }
}
