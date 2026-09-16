package com.autoscroll.xscreenshot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.*

class AutoScrollAccessibilityService : AccessibilityService() {

    companion object {
        var instance: AutoScrollAccessibilityService? = null
            private set
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var isScrolling = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d("AutoScrollService", "无障碍服务已连接")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        serviceScope.cancel()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 无需监控事件，直接由悬浮窗发送手势控制
    }

    override fun onInterrupt() {
        isScrolling = false
    }

    /**
     * 执行单次安全向上滚动 (页面内容向下移动/查看更下方的回复)
     * 关键解决逻辑：起始触点必须高于 X 底部新增的固定回复栏，防止点击到 EditText 唤起键盘！
     *
     * @param screenWidth 屏幕宽度 (px)
     * @param screenHeight 屏幕高度 (px)
     * @param topExclusionPx 顶部状态栏及标题栏避让高度
     * @param bottomExclusionPx X 底部固定回复栏避让高度 (一般为 180~220px)
     * @param onComplete 滑动完成回调
     */
    fun performSafeScroll(
        screenWidth: Int,
        screenHeight: Int,
        topExclusionPx: Int = 220,
        bottomExclusionPx: Int = 260,
        durationMs: Long = 450L,
        onComplete: (Boolean) -> Unit
    ) {
        val centerX = (screenWidth / 2f)

        // 触点从避让区上方 30px 开始向上滑动
        val safeStartY = (screenHeight - bottomExclusionPx - 30).toFloat()
        // 滑动终点落在顶部避让区下方 60px
        val safeEndY = (topExclusionPx + 60).toFloat()

        if (safeStartY <= safeEndY) {
            onComplete(false)
            return
        }

        val scrollPath = Path().apply {
            moveTo(centerX, safeStartY)
            lineTo(centerX, safeEndY)
        }

        val stroke = GestureDescription.StrokeDescription(scrollPath, 0L, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                super.onCompleted(gestureDescription)
                onComplete(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                super.onCancelled(gestureDescription)
                onComplete(false)
            }
        }, null)

        if (!dispatched) {
            onComplete(false)
        }
    }
}
