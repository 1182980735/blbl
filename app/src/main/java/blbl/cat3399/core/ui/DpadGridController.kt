package blbl.cat3399.core.ui

import android.os.Build
import android.os.SystemClock
import android.view.FocusFinder
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import blbl.cat3399.R

internal fun interface DpadItemKeyHandler {
    fun onInterceptKey(
        itemView: View,
        keyCode: Int,
        event: KeyEvent,
    ): Boolean
}

internal fun View.setDpadItemKeyHandler(handler: DpadItemKeyHandler?) {
    setTag(R.id.tag_dpad_item_key_handler, handler)
}

internal fun View.dispatchToDpadItemKeyHandler(
    keyCode: Int,
    event: KeyEvent,
): Boolean {
    val handler = getTag(R.id.tag_dpad_item_key_handler) as? DpadItemKeyHandler ?: return false
    return handler.onInterceptKey(this, keyCode, event)
}

internal fun View.dispatchToAncestorDpadItemKeyHandler(
    keyCode: Int,
    event: KeyEvent,
): Boolean {
    var current: View? = this
    while (current != null) {
        if (current.dispatchToDpadItemKeyHandler(keyCode, event)) {
            return true
        }
        current = current.parent as? View
    }
    return false
}

/**
 * 可复用的DPAD焦点控制器，用于网格状RecyclerView
 *
 * 主要功能：
 * - 在RIGHT/DOWN边缘保持焦点在网格内部（不逃逸到其他容器）
 * - 在LEFT/RIGHT边缘可选切换Tab
 * - 支持"无限滚动"体验：当DPAD_DOWN触底触发加载更多数据后，焦点会自动移动到下一行（同列）的新项
 */
internal class DpadGridController(
    private val recyclerView: RecyclerView,
    private val callbacks: Callbacks,
    private val config: Config = Config(),
) {
    /**
     * 配置参数
     */
    data class Config(
        /**
         * 每次按键事件时调用，返回false可禁用所有处理
         * 典型用法（在Fragment中）：`{ _binding != null && isResumed }`
         */
        val isEnabled: () -> Boolean = { true },

        /**
         * 启用后，长按DPAD_CENTER/ENTER会触发performLongClick()（repeatCount>0）
         * 在TV上非常有用，"OK"长按通常用于打开上下文菜单
         */
        val enableCenterLongPressToLongClick: Boolean = false,

        /**
         * 当DPAD_DOWN到达底部但RecyclerView仍可滚动时，按此因子滚动聚焦项高度，然后尝试保持焦点在网格内
         */
        val scrollOnDownEdgeFactor: Float = 0.8f,

        /**
         * 如果为true，右侧边缘的DPAD_RIGHT会被消费（不会逃逸）
         * 某些布局（如左侧垂直列表需要将焦点移到右侧内容面板）可能需要允许RIGHT冒泡到系统焦点搜索
         */
        val consumeRightEdge: Boolean = true,

        /**
         * 如果为true，顶部边缘的DPAD_UP总是被消费（即使回调未能将焦点移到其他地方）
         * 这有助于防止焦点意外"逃逸"
         */
        val consumeUpAtTopEdge: Boolean = true,
    )

    /**
     * 回调接口，用于处理边缘事件和加载更多
     */
    interface Callbacks {
        /**
         * 当在第一行按下DPAD_UP且列表无法继续向上滚动时调用
         * 如果处理了焦点（如聚焦TabLayout/返回按钮），返回true
         */
        fun onTopEdge(): Boolean

        /**
         * 当按下DPAD_LEFT且焦点搜索无法在网格中找到另一个项时调用
         * 返回true消费事件（如切换到上一个Tab），或返回false允许事件冒泡（如让Activity处理进入侧边栏）
         */
        fun onLeftEdge(): Boolean

        /**
         * 当按下DPAD_RIGHT且焦点搜索无法在网格中找到另一个项时调用
         * 按键事件总是被消费（不会逃逸）
         */
        fun onRightEdge()

        /**
         * 到达底部边缘时是否可以加载更多数据
         * 注意：即使请求正在进行中，如果希望DPAD_DOWN"排队"一个焦点跳转到下一行，也应返回true
         */
        fun canLoadMore(): Boolean

        /**
         * 触发加载下一页（如果适用）。实现应该是幂等的
         */
        fun loadMore()
    }

    /** 是否已安装控制器 */
    private var installed: Boolean = false

    /** 加载更多后待聚焦的锚点位置 */
    private var pendingFocusAfterLoadMoreAnchorPos: Int = RecyclerView.NO_POSITION

    /** 加载更多后待聚焦的目标位置 */
    private var pendingFocusAfterLoadMoreTargetPos: Int = RecyclerView.NO_POSITION

    /** 焦点重试延迟时间（毫秒） */
    private val focusRetryDelayMillis: Long = 16L

    /** 焦点重试最大次数 */
    private val focusRetryMaxAttempts: Int = 30

    /** 焦点驻留时保存的descendantFocusability */
    private var focusParkedDescendantFocusability: Int? = null

    /** 焦点保护窗口时间（毫秒） */
    private val focusProtectWindowMs: Long = 500L

    /** 上次垂直导航的时间戳 */
    private var lastVerticalNavAtMs: Long = 0L

    /** 上次垂直导航的方向 */
    private var lastVerticalNavDirection: Int = View.FOCUS_DOWN

    /** 上次已知的聚焦位置 */
    private var lastKnownFocusedAdapterPos: Int = RecyclerView.NO_POSITION

    /** 上次已知的聚焦列索引 */
    private var lastKnownFocusedSpanIndex: Int? = null

    /** 分离焦点恢复令牌，用于防止过时的焦点恢复操作 */
    private var detachFocusRestoreToken: Int = 0

    /** 原始的overScrollMode */
    private var originalOverScrollMode: Int? = null

    /** 原始的defaultFocusHighlightEnabled */
    private var originalDefaultFocusHighlightEnabled: Boolean? = null

    /** 是否已抑制默认焦点高亮 */
    private var didSuppressDefaultFocusHighlight: Boolean = false

    /** 焦点高亮监听器 */
    private var focusHighlightListener: ViewTreeObserver.OnGlobalFocusChangeListener? = null

    /**
     * RecyclerView级别的后备按键监听器，用于处理以下短暂场景：
     * - 网格获得焦点但没有子项当前持有焦点（如初始加载、异步更新）
     * - 聚焦项正在被重新绑定，无法可靠提供adapter位置
     *
     * 没有这个监听器，DPAD_DOWN可能会冒泡到系统，框架可能会选择内容区域外的"任意"
     * 后备焦点目标（通常是左侧边栏）
     */
    private val recyclerKeyListener =
        View.OnKeyListener { _, keyCode, event ->
            if (!installed) return@OnKeyListener false
            if (!config.isEnabled()) return@OnKeyListener false
            if (event.action != KeyEvent.ACTION_DOWN) return@OnKeyListener false
            if (!recyclerView.isFocused) return@OnKeyListener false

            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    markVerticalNav(View.FOCUS_DOWN)
                    handleRecyclerFocusedDpadDown()
                }

                KeyEvent.KEYCODE_DPAD_UP -> {
                    markVerticalNav(View.FOCUS_UP)
                    // 如果用户在加载更多的焦点跳转待处理时导航离开，取消它并恢复正常焦点行为
                    if (pendingFocusAfterLoadMoreAnchorPos != RecyclerView.NO_POSITION) {
                        clearPendingFocusAfterLoadMore()
                    }
                    // 此处故意不添加特殊处理：让系统焦点搜索将焦点移出RecyclerView（如到Tab/标题）
                    false
                }

                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT,
                -> {
                    if (pendingFocusAfterLoadMoreAnchorPos != RecyclerView.NO_POSITION) {
                        clearPendingFocusAfterLoadMore()
                    }
                    false
                }

                else -> false
            }
        }

    /**
     * 子项状态变化监听器，在子项附加到窗口时设置按键监听，分离时清理
     */
    private val childListener =
        object : RecyclerView.OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(view: View) {
                if (config.enableCenterLongPressToLongClick) {
                    view.setTag(R.id.tag_long_press_handled, false)
                }
                view.setOnKeyListener { v, keyCode, event ->
                    if (!installed) return@setOnKeyListener false
                    if (!config.isEnabled()) return@setOnKeyListener false

                    // 先尝试让子项自己处理按键
                    if (v.dispatchToDpadItemKeyHandler(keyCode, event)) {
                        return@setOnKeyListener true
                    }

                    // 处理长按OK键
                    if (config.enableCenterLongPressToLongClick && handleCenterLongPress(v, keyCode, event)) {
                        return@setOnKeyListener true
                    }

                    if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false

                    when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_UP -> {
                            markVerticalNav(View.FOCUS_UP)
                            val pos = resolveAdapterPosition(v)
                            if (pos != null) {
                                rememberLastKnownFocus(position = pos)
                                handleDpadUp(pos)
                            } else {
                                // 在adapter更新期间，聚焦视图可能暂时报告NO_POSITION
                                // 消费事件以防止焦点逃逸到其他容器
                                config.consumeUpAtTopEdge
                            }
                        }

                        KeyEvent.KEYCODE_DPAD_LEFT -> handleDpadLeft(v)
                        KeyEvent.KEYCODE_DPAD_RIGHT -> handleDpadRight(v)
                        KeyEvent.KEYCODE_DPAD_DOWN -> {
                            markVerticalNav(View.FOCUS_DOWN)
                            val pos = resolveAdapterPosition(v)
                            if (pos != null) {
                                rememberLastKnownFocus(position = pos)
                                handleDpadDown(v, pos)
                            } else {
                                handleDpadDownFallback(v)
                            }
                        }

                        else -> false
                    }
                }
            }

            override fun onChildViewDetachedFromWindow(view: View) {
                // 子项分离时可能需要保护焦点
                maybeProtectFocusOnChildDetach(view)
                view.setOnKeyListener(null)
                if (config.enableCenterLongPressToLongClick) {
                    view.setTag(R.id.tag_long_press_handled, false)
                }
            }
        }

    /**
     * 安装控制器，开始处理RecyclerView的焦点事件
     */
    fun install() {
        if (installed) return
        installed = true
        if (originalOverScrollMode == null) {
            originalOverScrollMode = recyclerView.overScrollMode
        }
        recyclerView.overScrollMode = View.OVER_SCROLL_NEVER
        ensureDefaultFocusHighlightSuppressionHook()
        recyclerView.setOnKeyListener(recyclerKeyListener)
        recyclerView.addOnChildAttachStateChangeListener(childListener)
        // 确保已附加的子项也被覆盖（监听器只对未来的附加事件触发）
        for (i in 0 until recyclerView.childCount) {
            childListener.onChildViewAttachedToWindow(recyclerView.getChildAt(i))
        }
    }

    /**
     * 释放控制器，清理所有监听器和状态
     */
    fun release() {
        if (!installed) return
        installed = false
        unparkFocusInRecyclerViewIfNeeded()
        removeDefaultFocusHighlightSuppressionHook()
        originalOverScrollMode?.let { mode ->
            if (recyclerView.overScrollMode == View.OVER_SCROLL_NEVER) {
                recyclerView.overScrollMode = mode
            }
        }
        originalOverScrollMode = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            originalDefaultFocusHighlightEnabled?.let { enabled ->
                if (didSuppressDefaultFocusHighlight && !recyclerView.defaultFocusHighlightEnabled) {
                    recyclerView.defaultFocusHighlightEnabled = enabled
                }
            }
        }
        originalDefaultFocusHighlightEnabled = null
        didSuppressDefaultFocusHighlight = false
        detachFocusRestoreToken++
        clearPendingFocusAfterLoadMore()
        recyclerView.removeOnChildAttachStateChangeListener(childListener)
        recyclerView.setOnKeyListener(null)
        for (i in 0 until recyclerView.childCount) {
            childListener.onChildViewDetachedFromWindow(recyclerView.getChildAt(i))
        }
    }

    /**
     * 清除加载更多后的待处理焦点跳转状态
     */
    fun clearPendingFocusAfterLoadMore() {
        pendingFocusAfterLoadMoreAnchorPos = RecyclerView.NO_POSITION
        pendingFocusAfterLoadMoreTargetPos = RecyclerView.NO_POSITION
        unparkFocusInRecyclerViewIfNeeded()
    }

    /**
     * 当页面刷新时，某些实现会重置adapter（如submit(emptyList())然后submit(newItems)）。
     * 如果焦点当前在子项上，框架可能会在短暂的分离/重新绑定期间回退到不相关的容器（通常是侧边栏/头像）。
     *
     * 此方法主动将焦点驻留在RecyclerView上，以保持焦点在内容区域内。
     *
     * 注意：
     * - 不会从其他容器窃取焦点（仅在焦点已经在RecyclerView内部或当前为null时生效）
     * - 焦点捕获后立即恢复descendant focusability（没有长期覆盖）
     */
    fun parkFocusForDataSetReset() {
        // 复用加载更多的焦点驻留实现，但不保持descendant focusability覆盖：只需要捕获一帧的焦点
        parkFocusInRecyclerViewForLoadMore(keepDescendantFocusabilityOverridden = false)
    }

    /**
     * 如果[parkFocusForDataSetReset]临时覆盖了descendant focusability，恢复它。
     *
     * 通常不需要，因为[parkFocusForDataSetReset]在焦点捕获后立即恢复，但保持此方法公开
     * 可以让调用方在内部行为变化时保持弹性。
     */
    fun unparkFocusAfterDataSetReset() {
        unparkFocusInRecyclerViewIfNeeded()
    }

    /**
     * 消费加载更多后的待处理焦点跳转
     *
     * 当加载更多完成后，调用此方法将焦点移动到新添加行的对应位置
     * @return 如果成功处理了焦点跳转返回true，否则返回false
     */
    fun consumePendingFocusAfterLoadMore(): Boolean {
        val anchorPos = pendingFocusAfterLoadMoreAnchorPos
        if (anchorPos == RecyclerView.NO_POSITION) return false

        if (!config.isEnabled()) {
            clearPendingFocusAfterLoadMore()
            return false
        }

        val adapter = recyclerView.adapter
        if (adapter == null) {
            clearPendingFocusAfterLoadMore()
            return false
        }

        val spanCount = spanCountForLayoutManager()
        if (spanCount == null) {
            clearPendingFocusAfterLoadMore()
            return false
        }

        val focused = recyclerView.rootView?.findFocus()
        if (focused != null && !FocusTreeUtils.isDescendantOf(focused, recyclerView)) {
            clearPendingFocusAfterLoadMore()
            return false
        }

        val itemCount = adapter.itemCount
        if (itemCount <= 0) {
            clearPendingFocusAfterLoadMore()
            return false
        }

        val existingTarget = pendingFocusAfterLoadMoreTargetPos
        if (existingTarget != RecyclerView.NO_POSITION) {
            // A focus jump is already in progress; don't restart it, but still validate state.
            if (existingTarget !in 0 until itemCount) {
                clearPendingFocusAfterLoadMore()
                return false
            }
            return true
        }

        val candidatePos =
            when {
                anchorPos + spanCount in 0 until itemCount -> anchorPos + spanCount
                anchorPos + 1 in 0 until itemCount -> anchorPos + 1
                else -> null
            }
        if (candidatePos == null) {
            // No "next row" available (e.g. load-more returned empty / end reached). If focus was
            // parked on RecyclerView, restore it back to the last known anchor item so the user
            // doesn't get stuck on an unfocused container.
            val fallbackPos = anchorPos.coerceIn(0, itemCount - 1)
            clearPendingFocusAfterLoadMore()
            scrollAndFocusAdapterPosition(fallbackPos, smooth = false)
            return true
        }

        pendingFocusAfterLoadMoreTargetPos = candidatePos
        // Keep focus "frozen" on RecyclerView while new children are being attached/recycled.
        parkFocusInRecyclerViewForLoadMore(keepDescendantFocusabilityOverridden = true)

        scrollAndFocusAdapterPosition(
            candidatePos,
            smooth = true,
            onFocused = {
                // Only clear when the intended target focus jump succeeds.
                if (pendingFocusAfterLoadMoreTargetPos == candidatePos) {
                    clearPendingFocusAfterLoadMore()
                }
            },
        )
        return true
    }

    /**
     * 处理长按OK键（DPAD_CENTER/ENTER）
     *
     * 当repeatCount>0时触发performLongClick()，实现TV上的长按功能
     */
    private fun handleCenterLongPress(v: View, keyCode: Int, event: KeyEvent): Boolean {
        if (
            keyCode != KeyEvent.KEYCODE_DPAD_CENTER &&
            keyCode != KeyEvent.KEYCODE_ENTER &&
            keyCode != KeyEvent.KEYCODE_NUMPAD_ENTER
        ) {
            return false
        }

        val handled = (v.getTag(R.id.tag_long_press_handled) as? Boolean) == true
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount > 0) {
            if (!handled) {
                v.setTag(R.id.tag_long_press_handled, true)
                v.performLongClick()
            }
            return true
        }
        if (event.action == KeyEvent.ACTION_UP && handled) {
            v.setTag(R.id.tag_long_press_handled, false)
            return true
        }
        return false
    }

    /**
     * 当RecyclerView本身获得焦点时处理DPAD_DOWN
     *
     * 用于初始加载或异步更新期间的焦点后备处理
     */
    private fun handleRecyclerFocusedDpadDown(): Boolean {
        // 如果加载更多的焦点跳转待处理，保持焦点驻留在RecyclerView上，直到新项被追加
        // 并由consumePendingFocusAfterLoadMore()将焦点移动到目标
        if (pendingFocusAfterLoadMoreAnchorPos != RecyclerView.NO_POSITION) {
            parkFocusInRecyclerViewForLoadMore(keepDescendantFocusabilityOverridden = true)
            return true
        }

        val itemCount = recyclerView.adapter?.itemCount ?: 0
        if (itemCount <= 0) {
            // 数据还未准备好：保持焦点驻留在内容区域
            return true
        }

        val candidatePos = (firstVisibleAdapterPosition() ?: 0).coerceIn(0, itemCount - 1)
        scrollAndFocusAdapterPosition(candidatePos, smooth = false)
        return true
    }

    /**
     * 标记垂直导航的时间和方向
     */
    private fun markVerticalNav(direction: Int) {
        lastVerticalNavAtMs = SystemClock.uptimeMillis()
        lastVerticalNavDirection = direction
    }

    /**
     * 记录上次已知的焦点位置和列索引
     */
    private fun rememberLastKnownFocus(position: Int) {
        lastKnownFocusedAdapterPos = position
        val spanCount = spanCountForLayoutManager()
        if (spanCount == null || spanCount <= 0) {
            lastKnownFocusedSpanIndex = null
            return
        }
        val lm = recyclerView.layoutManager
        lastKnownFocusedSpanIndex =
            when (lm) {
                is GridLayoutManager -> lm.spanSizeLookup.getSpanIndex(position, spanCount)
                else -> position % spanCount
            }
    }

    /**
     * 子项分离时保护焦点，防止焦点逃逸到不相关的容器
     */
    private fun maybeProtectFocusOnChildDetach(detachedChild: View) {
        if (!installed) return
        if (!recyclerView.isAttachedToWindow) return
        if (!config.isEnabled()) return

        val hasPendingLoadMore = pendingFocusAfterLoadMoreAnchorPos != RecyclerView.NO_POSITION
        if (!hasPendingLoadMore) {
            // 只在主动垂直导航期间保护；否则后台更新不应窃取焦点或意外重新聚焦内容
            val now = SystemClock.uptimeMillis()
            if (now - lastVerticalNavAtMs > focusProtectWindowMs) return
        }

        val focused = recyclerView.rootView?.findFocus()
        val focusWasInThisRecycler = focused != null && FocusTreeUtils.isDescendantOf(focused, recyclerView)
        if (!focusWasInThisRecycler && focused != null) return

        val detachingContainedFocus =
            focused != null && FocusTreeUtils.isDescendantOf(focused, detachedChild)
        if (!detachingContainedFocus && focused != null) return

        // 将焦点驻留在RecyclerView上，以便框架不会在子项被回收的短暂帧期间回退到不相关容器
        parkFocusInRecyclerViewForLoadMore(keepDescendantFocusabilityOverridden = hasPendingLoadMore)

        val token = ++detachFocusRestoreToken
        recyclerView.postIfAlive(
            isAlive = { installed && recyclerView.isAttachedToWindow && config.isEnabled() && detachFocusRestoreToken == token },
        ) {
            // 如果加载更多焦点跳转已排队，保持焦点驻留直到新项被追加
            if (pendingFocusAfterLoadMoreAnchorPos != RecyclerView.NO_POSITION) return@postIfAlive
            restoreFocusAfterDetach()
        }
    }

    /**
     * 子项分离后恢复焦点到合适的位置
     */
    private fun restoreFocusAfterDetach(): Boolean {
        val adapter = recyclerView.adapter ?: return false
        val itemCount = adapter.itemCount
        if (itemCount <= 0) return false

        val focused = recyclerView.rootView?.findFocus()
        if (focused != null && focused !== recyclerView && FocusTreeUtils.isDescendantOf(focused, recyclerView)) {
            return true
        }

        val firstVisible = firstVisibleAdapterPosition() ?: return false
        val lastVisible = lastVisibleAdapterPosition() ?: firstVisible
        if (firstVisible !in 0 until itemCount) return false

        val anchor =
            pendingFocusAfterLoadMoreAnchorPos
                .takeIf { it != RecyclerView.NO_POSITION }
                ?: lastKnownFocusedAdapterPos
                    .takeIf { it != RecyclerView.NO_POSITION }
                    ?: firstVisible
        val spanCount = spanCountForLayoutManager()?.coerceAtLeast(1) ?: 1
        val desired =
            when (lastVerticalNavDirection) {
                View.FOCUS_UP -> anchor - spanCount
                else -> anchor + spanCount
            }.coerceIn(0, itemCount - 1)

        val candidateInVisibleRange = desired.coerceIn(firstVisible, lastVisible)

        val candidate =
            when (val lm = recyclerView.layoutManager) {
                is GridLayoutManager -> {
                    val targetSpan = lastKnownFocusedSpanIndex
                    if (targetSpan == null) {
                        candidateInVisibleRange
                    } else {
                        findVisiblePositionWithSpanIndex(
                            lm = lm,
                            firstVisible = firstVisible,
                            lastVisible = lastVisible,
                            targetSpanIndex = targetSpan,
                            direction = lastVerticalNavDirection,
                        ) ?: candidateInVisibleRange
                    }
                }

                else -> candidateInVisibleRange
            }

        return scrollAndFocusAdapterPosition(candidate, smooth = false)
    }

    /**
     * 获取最后一个可见项的adapter位置
     */
    private fun lastVisibleAdapterPosition(): Int? {
        val lm = recyclerView.layoutManager ?: return null
        val pos =
            when (lm) {
                is GridLayoutManager -> lm.findLastVisibleItemPosition()
                is LinearLayoutManager -> lm.findLastVisibleItemPosition()
                is StaggeredGridLayoutManager -> {
                    val spanCount = lm.spanCount.coerceAtLeast(1)
                    val last = IntArray(spanCount)
                    lm.findLastVisibleItemPositions(last)
                    var max = Int.MIN_VALUE
                    for (p in last) {
                        if (p != RecyclerView.NO_POSITION && p > max) max = p
                    }
                    if (max == Int.MIN_VALUE) RecyclerView.NO_POSITION else max
                }

                else -> RecyclerView.NO_POSITION
            }
        return pos.takeIf { it != RecyclerView.NO_POSITION }
    }

    /**
     * 在可见范围内查找具有指定列索引的位置
     */
    private fun findVisiblePositionWithSpanIndex(
        lm: GridLayoutManager,
        firstVisible: Int,
        lastVisible: Int,
        targetSpanIndex: Int,
        direction: Int,
    ): Int? {
        val spanCount = lm.spanCount.coerceAtLeast(1)
        val lookup = lm.spanSizeLookup
        return if (direction == View.FOCUS_UP) {
            for (pos in lastVisible downTo firstVisible) {
                if (lookup.getSpanIndex(pos, spanCount) == targetSpanIndex) return pos
            }
            null
        } else {
            for (pos in firstVisible..lastVisible) {
                if (lookup.getSpanIndex(pos, spanCount) == targetSpanIndex) return pos
            }
            null
        }
    }

    /**
     * 解析视图对应的adapter位置，按优先级尝试多种方式
     */
    private fun resolveAdapterPosition(view: View): Int? {
        val rootItem = recyclerView.findContainingItemView(view) ?: view
        val holder = recyclerView.findContainingViewHolder(rootItem) ?: return null

        return (
            holder.bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }
                ?: holder.absoluteAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }
                ?: holder.layoutPosition.takeIf { it != RecyclerView.NO_POSITION }
                ?: recyclerView.getChildLayoutPosition(rootItem).takeIf { it != RecyclerView.NO_POSITION }
        )
    }

    /**
     * 获取第一个可见项的adapter位置
     */
    private fun firstVisibleAdapterPosition(): Int? {
        val lm = recyclerView.layoutManager ?: return null
        val pos =
            when (lm) {
                is GridLayoutManager -> lm.findFirstVisibleItemPosition()
                is LinearLayoutManager -> lm.findFirstVisibleItemPosition()
                is StaggeredGridLayoutManager -> {
                    val spanCount = lm.spanCount.coerceAtLeast(1)
                    val first = IntArray(spanCount)
                    lm.findFirstVisibleItemPositions(first)
                    var min = Int.MAX_VALUE
                    for (p in first) {
                        if (p != RecyclerView.NO_POSITION && p < min) min = p
                    }
                    if (min == Int.MAX_VALUE) RecyclerView.NO_POSITION else min
                }

                else -> RecyclerView.NO_POSITION
            }
        return pos.takeIf { it != RecyclerView.NO_POSITION }
    }

    /**
     * 处理DPAD_UP按键，当在顶部行时调用回调
     */
    private fun handleDpadUp(position: Int): Boolean {
        if (!isInTopRowAtTop(position)) return false
        val handled = callbacks.onTopEdge()
        return if (config.consumeUpAtTopEdge) true else handled
    }

    /**
     * 处理DPAD_LEFT按键，当无法在网格内找到下一个焦点时调用回调
     */
    private fun handleDpadLeft(itemView: View): Boolean {
        val rootItem = recyclerView.findContainingItemView(itemView) ?: itemView
        val next = FocusFinder.getInstance().findNextFocus(recyclerView, rootItem, View.FOCUS_LEFT)
        if (next != null && FocusTreeUtils.isDescendantOf(next, recyclerView)) return false
        return callbacks.onLeftEdge()
    }

    /**
     * 处理DPAD_RIGHT按键，当无法在网格内找到下一个焦点时调用回调
     */
    private fun handleDpadRight(itemView: View): Boolean {
        val rootItem = recyclerView.findContainingItemView(itemView) ?: itemView
        val next = FocusFinder.getInstance().findNextFocus(recyclerView, rootItem, View.FOCUS_RIGHT)
        if (next != null && FocusTreeUtils.isDescendantOf(next, recyclerView)) return false
        if (!config.consumeRightEdge) return false
        callbacks.onRightEdge()
        // 右边缘不逃逸焦点
        return true
    }

    /**
     * 处理DPAD_DOWN按键，实现滚动、加载更多和焦点保持逻辑
     */
    private fun handleDpadDown(itemView: View, position: Int): Boolean {
        val rootItem = recyclerView.findContainingItemView(itemView) ?: itemView
        val next = FocusFinder.getInstance().findNextFocus(recyclerView, rootItem, View.FOCUS_DOWN)
        if (next != null && FocusTreeUtils.isDescendantOf(next, recyclerView)) {
            // 即使网格内存在下一个焦点也消费事件。让系统处理事件偶尔会选择RecyclerView外部的"更好"候选
            // （如侧边栏），特别是在用户按住DPAD_DOWN期间进行布局/adapter更新时
            next.requestFocus()
            return true
        }

        if (recyclerView.canScrollVertically(1)) {
            val dy = (rootItem.height * config.scrollOnDownEdgeFactor).toInt().coerceAtLeast(1)
            recyclerView.scrollBy(0, dy)
            val adapter = recyclerView.adapter
            val itemCount = adapter?.itemCount ?: 0
            val spanCount = spanCountForLayoutManager()
            val candidatePos =
                if (itemCount <= 0 || spanCount == null) {
                    null
                } else {
                    when {
                        position + spanCount in 0 until itemCount -> position + spanCount
                        position + 1 in 0 until itemCount -> position + 1
                        else -> null
                    }
                }
            recyclerView.postIfAlive(
                isAlive = { installed && recyclerView.isAttachedToWindow && config.isEnabled() },
            ) {
                if (tryFocusNextDownFromCurrent()) return@postIfAlive
                if (candidatePos != null) focusAdapterPosition(candidatePos)
            }
            return true
        }

        if (callbacks.canLoadMore()) {
            pendingFocusAfterLoadMoreAnchorPos = position
            pendingFocusAfterLoadMoreTargetPos = RecyclerView.NO_POSITION
            callbacks.loadMore()
            // 将焦点冻结在RecyclerView上，直到新页面被追加并可以将焦点移动到目标"下一行"项
            // 这防止了adapter更新导致的分离/附加期间框架回退焦点（如侧边栏）
            parkFocusInRecyclerViewForLoadMore(keepDescendantFocusabilityOverridden = true)
        }
        // 底部边缘总是消费事件，避免焦点逃逸到其他容器
        return true
    }

    /**
     * DPAD_DOWN的后备处理，当无法获取adapter位置时使用
     */
    private fun handleDpadDownFallback(itemView: View): Boolean {
        val rootItem = recyclerView.findContainingItemView(itemView) ?: itemView
        val next = FocusFinder.getInstance().findNextFocus(recyclerView, rootItem, View.FOCUS_DOWN)
        if (next != null && FocusTreeUtils.isDescendantOf(next, recyclerView)) {
            next.requestFocus()
            return true
        }

        if (recyclerView.canScrollVertically(1)) {
            val base =
                rootItem.height
                    .takeIf { it > 0 }
                    ?: (recyclerView.height.takeIf { it > 0 }?.div(2) ?: 1)
            val dy = (base * config.scrollOnDownEdgeFactor).toInt().coerceAtLeast(1)
            recyclerView.scrollBy(0, dy)
            recyclerView.postIfAlive(
                isAlive = { installed && recyclerView.isAttachedToWindow && config.isEnabled() },
            ) {
                tryFocusNextDownFromCurrent()
            }
            return true
        }

        if (callbacks.canLoadMore()) {
            val adapter = recyclerView.adapter
            val itemCount = adapter?.itemCount ?: 0
            val fallbackAnchor =
                when {
                    lastKnownFocusedAdapterPos in 0 until itemCount -> lastKnownFocusedAdapterPos
                    (lastVisibleAdapterPosition() ?: RecyclerView.NO_POSITION) in 0 until itemCount -> lastVisibleAdapterPosition()!!
                    (firstVisibleAdapterPosition() ?: RecyclerView.NO_POSITION) in 0 until itemCount -> firstVisibleAdapterPosition()!!
                    itemCount > 0 -> itemCount - 1
                    else -> RecyclerView.NO_POSITION
                }
            if (fallbackAnchor != RecyclerView.NO_POSITION) {
                pendingFocusAfterLoadMoreAnchorPos = fallbackAnchor
                pendingFocusAfterLoadMoreTargetPos = RecyclerView.NO_POSITION
            }
            callbacks.loadMore()
            // 在数据追加期间，聚焦的子项可能会暂时分离/重新绑定
            // 将焦点驻留在RecyclerView上避免框架级别的回退焦点目标
            parkFocusInRecyclerViewForLoadMore(keepDescendantFocusabilityOverridden = true)
        }
        // 总是消费：此后备处理的目的就是防止焦点逃逸到其他容器
        return true
    }

    /**
     * 将焦点驻留在RecyclerView上，用于加载更多期间的焦点保护
     */
    private fun parkFocusInRecyclerViewForLoadMore(keepDescendantFocusabilityOverridden: Boolean = false) {
        if (!installed) return
        if (!recyclerView.isAttachedToWindow) return
        if (!config.isEnabled()) return

        // 只在焦点已经在RecyclerView内部（或为null）时驻留焦点
        // 此控制器不应从其他容器窃取焦点（如侧边栏）
        val focused = recyclerView.rootView?.findFocus()
        if (focused != null && focused !== recyclerView && !FocusTreeUtils.isDescendantOf(focused, recyclerView)) {
            return
        }

        suppressRecyclerDefaultFocusHighlight()

        if (focusParkedDescendantFocusability == null) {
            // 临时优先聚焦RecyclerView本身而不是其子项，以便requestFocus()不会立即返回到可能被回收的子项
            focusParkedDescendantFocusability = recyclerView.descendantFocusability
            recyclerView.descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
        }

        if (recyclerView.isFocused || recyclerView.requestFocus()) {
            if (!keepDescendantFocusabilityOverridden) {
                // 焦点捕获后立即恢复原始descendantFocusability
                // 保持覆盖状态会影响后续焦点进入RecyclerView的方式（如从Tab到网格）
                unparkFocusInRecyclerViewIfNeeded()
            }
            return
        }

        // 如果requestFocus()失败（时序/布局问题），在下一帧重试，同时保持覆盖状态
        recyclerView.postIfAlive(
            isAlive = { installed && recyclerView.isAttachedToWindow && config.isEnabled() },
        ) {
            // 如果用户已经导航到其他地方，不要窃取焦点回来
            val currentFocused = recyclerView.rootView?.findFocus()
            if (
                currentFocused != null &&
                currentFocused !== recyclerView &&
                !FocusTreeUtils.isDescendantOf(currentFocused, recyclerView)
            ) {
                restoreRecyclerDefaultFocusHighlightIfSuppressed()
                return@postIfAlive
            }

            suppressRecyclerDefaultFocusHighlight()
            if (!recyclerView.isFocused) {
                recyclerView.requestFocus()
            }
            if (!keepDescendantFocusabilityOverridden) {
                unparkFocusInRecyclerViewIfNeeded()
            }
        }
    }

    /**
     * 如果焦点被驻留，恢复RecyclerView的descendantFocusability
     */
    private fun unparkFocusInRecyclerViewIfNeeded() {
        val original = focusParkedDescendantFocusability ?: return
        focusParkedDescendantFocusability = null
        recyclerView.descendantFocusability = original
    }

    /**
     * 抑制RecyclerView的默认焦点高亮（API 26+）
     */
    private fun suppressRecyclerDefaultFocusHighlight() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        if (originalDefaultFocusHighlightEnabled == null) {
            originalDefaultFocusHighlightEnabled = recyclerView.defaultFocusHighlightEnabled
        }

        if (recyclerView.defaultFocusHighlightEnabled) {
            recyclerView.defaultFocusHighlightEnabled = false
            didSuppressDefaultFocusHighlight = true
        }
    }

    /**
     * 如果之前抑制了默认焦点高亮，恢复它
     */
    private fun restoreRecyclerDefaultFocusHighlightIfSuppressed() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (!didSuppressDefaultFocusHighlight) return

        val original = originalDefaultFocusHighlightEnabled ?: return
        if (!recyclerView.defaultFocusHighlightEnabled) {
            recyclerView.defaultFocusHighlightEnabled = original
        }
        didSuppressDefaultFocusHighlight = false
    }

    /**
     * 确保默认焦点高亮抑制钩子已安装
     */
    private fun ensureDefaultFocusHighlightSuppressionHook() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (focusHighlightListener != null) return

        if (originalDefaultFocusHighlightEnabled == null) {
            originalDefaultFocusHighlightEnabled = recyclerView.defaultFocusHighlightEnabled
        }

        val listener =
            ViewTreeObserver.OnGlobalFocusChangeListener { oldFocus, newFocus ->
                if (!installed) return@OnGlobalFocusChangeListener
                if (newFocus === recyclerView) {
                    suppressRecyclerDefaultFocusHighlight()
                } else if (oldFocus === recyclerView) {
                    restoreRecyclerDefaultFocusHighlightIfSuppressed()
                }
            }
        focusHighlightListener = listener
        runCatching { recyclerView.viewTreeObserver.addOnGlobalFocusChangeListener(listener) }

        // 如果安装时焦点已经驻留在RecyclerView上（边缘情况），立即抑制
        if (recyclerView.isFocused) {
            suppressRecyclerDefaultFocusHighlight()
        }
    }

    /**
     * 移除默认焦点高亮抑制钩子
     */
    private fun removeDefaultFocusHighlightSuppressionHook() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val listener = focusHighlightListener
        focusHighlightListener = null
        if (listener != null) {
            runCatching { recyclerView.viewTreeObserver.removeOnGlobalFocusChangeListener(listener) }
        }
    }

    /**
     * 尝试从当前聚焦项向下移动焦点
     */
    private fun tryFocusNextDownFromCurrent(): Boolean {
        if (!config.isEnabled()) return false
        val focused = recyclerView.findFocus() ?: return false
        if (!FocusTreeUtils.isDescendantOf(focused, recyclerView)) return false
        val itemView = recyclerView.findContainingItemView(focused) ?: return false
        val next = FocusFinder.getInstance().findNextFocus(recyclerView, itemView, View.FOCUS_DOWN)
        if (next != null && FocusTreeUtils.isDescendantOf(next, recyclerView)) {
            next.requestFocus()
            return true
        }
        return false
    }

    /**
     * 聚焦指定adapter位置的项（平滑滚动）
     */
    private fun focusAdapterPosition(position: Int): Boolean {
        val adapter = recyclerView.adapter ?: return false
        val itemCount = adapter.itemCount
        if (position !in 0 until itemCount) return false

        return scrollAndFocusAdapterPosition(position, smooth = true)
    }

    /**
     * 滚动并聚焦指定adapter位置的项
     */
    private fun scrollAndFocusAdapterPosition(position: Int, smooth: Boolean, onFocused: (() -> Unit)? = null): Boolean {
        val adapter = recyclerView.adapter
        if (adapter == null) {
            maybeAbortPendingLoadMoreFocusRetry(position)
            return false
        }
        val itemCount = adapter.itemCount
        if (position !in 0 until itemCount) {
            maybeAbortPendingLoadMoreFocusRetry(position)
            return false
        }

        recyclerView.findViewHolderForAdapterPosition(position)?.itemView?.let { itemView ->
            // 避免在目标视图完全不在屏幕上时请求焦点
            // RecyclerView会"跳跃"使聚焦视图可见，看起来像闪烁
            if (isPartiallyVisibleInRecycler(itemView) && itemView.requestFocus()) {
                onFocused?.invoke()
                return true
            }
        }

        if (smooth) recyclerView.smoothScrollToPosition(position) else recyclerView.scrollToPosition(position)
        retryFocusAdapterPosition(position, attemptsLeft = focusRetryMaxAttempts, onFocused = onFocused)
        return true
    }

    /**
     * 重试聚焦指定adapter位置的项（带延迟重试机制）
     */
    private fun retryFocusAdapterPosition(position: Int, attemptsLeft: Int, onFocused: (() -> Unit)? = null) {
        if (!isAliveForFocusJump()) {
            maybeAbortPendingLoadMoreFocusRetry(position)
            return
        }

        val adapter = recyclerView.adapter
        if (adapter == null) {
            maybeAbortPendingLoadMoreFocusRetry(position)
            return
        }
        val itemCount = adapter.itemCount
        if (position !in 0 until itemCount) {
            maybeAbortPendingLoadMoreFocusRetry(position)
            return
        }

        recyclerView.findViewHolderForAdapterPosition(position)?.itemView?.let { itemView ->
            // 仅当目标至少部分可见时才请求焦点，否则RecyclerView可能执行立即滚动到可见（无平滑动画），看起来像闪烁
            if (isPartiallyVisibleInRecycler(itemView) && itemView.requestFocus()) {
                onFocused?.invoke()
                return
            }
        }

        if (attemptsLeft <= 0) {
            maybeAbortPendingLoadMoreFocusRetry(position)
            return
        }
        recyclerView.postDelayedIfAlive(
            delayMillis = focusRetryDelayMillis,
            isAlive = { isAliveForFocusJump() },
        ) {
            retryFocusAdapterPosition(position, attemptsLeft = attemptsLeft - 1, onFocused = onFocused)
        }
    }

    /**
     * 如果指定位置的加载更多焦点重试失败，取消待处理的焦点跳转
     */
    private fun maybeAbortPendingLoadMoreFocusRetry(position: Int) {
        if (pendingFocusAfterLoadMoreTargetPos != position) return
        if (pendingFocusAfterLoadMoreAnchorPos == RecyclerView.NO_POSITION) return
        // 放弃排队的加载更多焦点跳转：恢复正常焦点能力，使用户即使目标视图永远不可聚焦/不可见也能继续导航
        clearPendingFocusAfterLoadMore()
    }

    /**
     * 检查焦点跳转是否仍然有效（控制器已安装、RecyclerView已附加、配置已启用、焦点仍在RecyclerView内）
     */
    private fun isAliveForFocusJump(): Boolean {
        if (!installed) return false
        if (!recyclerView.isAttachedToWindow) return false
        if (!config.isEnabled()) return false
        val focused = recyclerView.rootView?.findFocus()
        if (focused != null && !FocusTreeUtils.isDescendantOf(focused, recyclerView)) return false
        return true
    }

    /**
     * 检查视图在RecyclerView中是否至少部分可见
     */
    private fun isPartiallyVisibleInRecycler(itemView: View): Boolean {
        val parentH = recyclerView.height
        if (parentH <= 0) return false
        val parentTop = recyclerView.paddingTop
        val parentBottom = parentH - recyclerView.paddingBottom
        val childTop = itemView.top
        val childBottom = itemView.bottom
        return childBottom > parentTop && childTop < parentBottom
    }

    /**
     * 获取LayoutManager的列数
     */
    private fun spanCountForLayoutManager(): Int? {
        val lm = recyclerView.layoutManager
        return when (lm) {
            is GridLayoutManager -> lm.spanCount.coerceAtLeast(1)
            is LinearLayoutManager -> 1
            is StaggeredGridLayoutManager -> lm.spanCount.coerceAtLeast(1)
            else -> null
        }
    }

    /**
     * 判断指定位置是否在顶部行且无法继续向上滚动
     *
     * 重要：不能仅依赖`RecyclerView.canScrollVertically(-1)`。
     * 在TV上，聚焦项通常会略微放大；RecyclerView可能会滚动几个像素以保持放大的项完全可见，
     * 使`canScrollVertically(-1)`返回true，即使用户实际上在第一行。
     * 这会导致DPAD_UP逃逸到其他容器（如侧边栏）而不是聚焦标题/Tab。
     */
    private fun isInTopRowAtTop(position: Int): Boolean {
        val lm = recyclerView.layoutManager ?: return false
        return when (lm) {
            is GridLayoutManager -> {
                val spanCount = lm.spanCount.coerceAtLeast(1)
                val groupIndex = lm.spanSizeLookup.getSpanGroupIndex(position, spanCount)
                if (groupIndex != 0) return false

                val firstVisible = lm.findFirstVisibleItemPosition()
                firstVisible == 0 || !recyclerView.canScrollVertically(-1)
            }

            is LinearLayoutManager -> {
                if (position != 0) return false
                val firstVisible = lm.findFirstVisibleItemPosition()
                firstVisible == 0 || !recyclerView.canScrollVertically(-1)
            }

            is StaggeredGridLayoutManager -> {
                val spanCount = lm.spanCount.coerceAtLeast(1)
                val first = IntArray(spanCount)
                lm.findFirstVisibleItemPositions(first)
                val nearTop = first.any { it == 0 } || !recyclerView.canScrollVertically(-1)
                nearTop && first.any { it == position }
            }

            else -> position == 0 && !recyclerView.canScrollVertically(-1)
        }
    }
}
