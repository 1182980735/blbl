package blbl.cat3399.core.ui

import android.app.Activity
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * 沉浸式全屏模式工具类
 *
 * 提供统一的沉浸式体验控制，用于视频播放等场景下隐藏系统状态栏和导航栏
 */
object Immersive {

    /**
     * 应用沉浸式全屏模式
     *
     * @param activity 目标Activity
     * @param enabled 是否启用沉浸式模式
     */
    fun apply(activity: Activity, enabled: Boolean) {
        val window = activity.window ?: return

        // 应用全屏窗口策略
        WindowDisplayPolicy.applyWindow(activity, fullscreen = enabled)

        // 获取窗口Insets控制器
        val controller = WindowInsetsControllerCompat(window, window.decorView)

        if (enabled) {
            // 启用沉浸式：设置滑动显示行为，然后隐藏系统栏
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            // 关闭沉浸式：显示系统栏
            controller.show(WindowInsetsCompat.Type.systemBars())
        }

        // 请求重新应用Insets
        WindowDisplayPolicy.requestApplyInsets(activity)
    }
}
