package blbl.cat3399.core.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import blbl.cat3399.R

/**
 * 自定义Toast工具类
 *
 * 提供比系统Toast更美观的UI效果和更灵活的控制能力，支持淡入淡出动画
 * 当无法获取到Activity时，会回退到系统Toast
 */
object AppToast {
    /** 短时长显示时间（毫秒） */
    private const val DURATION_SHORT_MS: Long = 2000L

    /** 长时长显示时间（毫秒） */
    private const val DURATION_LONG_MS: Long = 3500L

    /** 淡入动画时长（毫秒） */
    private const val ANIM_IN_MS: Long = 180L

    /** 淡出动画时长（毫秒） */
    private const val ANIM_OUT_MS: Long = 160L

    /** 主线程Handler，用于线程切换 */
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 显示短时长Toast
     *
     * @param context 上下文
     * @param text 要显示的文本内容
     */
    fun show(context: Context, text: CharSequence) {
        showInternal(context = context, text = text, durationMs = DURATION_SHORT_MS)
    }

    /**
     * 显示长时长Toast
     *
     * @param context 上下文
     * @param text 要显示的文本内容
     */
    fun showLong(context: Context, text: CharSequence) {
        showInternal(context = context, text = text, durationMs = DURATION_LONG_MS)
    }

    /**
     * Toast内部显示逻辑
     *
     * @param context 上下文
     * @param text 要显示的文本内容
     * @param durationMs 显示时长（毫秒）
     */
    private fun showInternal(context: Context, text: CharSequence, durationMs: Long) {
        // 空文本不显示
        if (text.isBlank()) return

        // 如果不在主线程，切换到主线程执行
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { showInternal(context = context, text = text, durationMs = durationMs) }
            return
        }

        // 获取Activity并检查状态
        val activity =
            context.findActivity()
                ?.takeIf { !it.isFinishing && !it.isDestroyed }

        // 如果无法获取到有效Activity，使用系统Toast作为备选
        if (activity == null) {
            showFallbackSystemToast(context = context, text = text, long = durationMs > DURATION_SHORT_MS)
            return
        }

        // 获取Activity根视图
        val root = activity.findViewById<FrameLayout>(android.R.id.content)

        // 先关闭当前显示的Toast
        dismissCurrent(root)

        // 加载自定义Toast布局
        val view = LayoutInflater.from(root.context).inflate(R.layout.view_app_toast, root, false)
        view.isClickable = false
        view.isFocusable = false
        view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO

        // 设置应用图标
        view.findViewById<ImageView>(R.id.iv_app_icon).setImageDrawable(
            runCatching { root.context.applicationInfo.loadIcon(root.context.packageManager) }
                .getOrElse { root.context.getDrawable(R.mipmap.ic_launcher) },
        )

        // 设置文本内容和最大宽度
        val tvText = view.findViewById<TextView>(R.id.tv_text)
        tvText.text = text
        tvText.maxWidth =
            (root.context.resources.displayMetrics.widthPixels * 0.70f)
                .toInt()
                .coerceAtLeast(dp(root.context, 200f))

        // 获取底部系统栏高度
        val insetsBottom =
            ViewCompat.getRootWindowInsets(root)
                ?.getInsets(WindowInsetsCompat.Type.systemBars())
                ?.bottom
                ?: 0

        // 设置布局参数，居底部显示
        val lp =
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                val marginH = dp(root.context, 24f)
                val marginB = dp(root.context, 32f) + insetsBottom
                setMargins(marginH, 0, marginH, marginB)
            }

        // 添加到根视图并设置标签
        root.addView(view, lp)
        root.setTag(R.id.tag_app_toast_view, view)

        // 执行淡入动画
        val startOffsetPx = dp(root.context, 16f).toFloat()
        view.alpha = 0f
        view.translationY = startOffsetPx
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(ANIM_IN_MS)
            .setInterpolator(DecelerateInterpolator())
            .start()

        // 设置自动隐藏任务
        val hideRunnable = Runnable { hideInternal(root = root, view = view) }
        root.setTag(R.id.tag_app_toast_hide_runnable, hideRunnable)
        mainHandler.postDelayed(hideRunnable, durationMs)
    }

    /**
     * Toast内部隐藏逻辑
     *
     * @param root Activity根视图
     * @param view Toast视图
     */
    private fun hideInternal(root: FrameLayout, view: View) {
        // 如果不在主线程，切换到主线程执行
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { hideInternal(root = root, view = view) }
            return
        }

        // 如果当前显示的不是这个Toast，忽略
        if (root.getTag(R.id.tag_app_toast_view) !== view) return

        // 执行淡出动画
        val endOffsetPx = dp(root.context, 12f).toFloat()
        view.animate()
            .alpha(0f)
            .translationY(endOffsetPx)
            .setDuration(ANIM_OUT_MS)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction {
                // 动画结束后移除视图
                if (root.getTag(R.id.tag_app_toast_view) === view) {
                    dismissCurrent(root)
                }
            }
            .start()
    }

    /**
     * 关闭当前显示的Toast
     *
     * @param root Activity根视图
     */
    private fun dismissCurrent(root: FrameLayout) {
        // 移除隐藏任务
        val hide = root.getTag(R.id.tag_app_toast_hide_runnable) as? Runnable
        if (hide != null) mainHandler.removeCallbacks(hide)

        // 移除视图
        val current = root.getTag(R.id.tag_app_toast_view) as? View
        if (current != null) root.removeView(current)

        // 清空标签
        root.setTag(R.id.tag_app_toast_view, null)
        root.setTag(R.id.tag_app_toast_hide_runnable, null)
    }

    /**
     * 使用系统Toast作为备选方案
     *
     * @param context 上下文
     * @param text 要显示的文本内容
     * @param long 是否使用长时长
     */
    private fun showFallbackSystemToast(context: Context, text: CharSequence, long: Boolean) {
        Toast.makeText(context.applicationContext, text, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }

    /**
     * 将dp转换为像素值
     *
     * @param context 上下文
     * @param value dp值
     * @return 像素值
     */
    private fun dp(context: Context, value: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics).toInt()

    /**
     * 从Context中查找对应的Activity
     *
     * @return 如果找到返回Activity，否则返回null
     */
    private fun Context.findActivity(): Activity? {
        var current: Context? = this
        while (current is ContextWrapper) {
            if (current is Activity) return current
            current = current.baseContext
        }
        return current as? Activity
    }
}

