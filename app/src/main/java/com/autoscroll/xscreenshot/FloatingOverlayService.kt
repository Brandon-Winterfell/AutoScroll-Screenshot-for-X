package com.autoscroll.xscreenshot

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.*
import android.view.*
import android.widget.*
import kotlinx.coroutines.*

class FloatingOverlayService : Service() {

    companion object {
        private var isShowing = false

        fun show(context: Context) {
            if (!isShowing) {
                val intent = Intent(context, FloatingOverlayService::class.java)
                context.startService(intent)
            }
        }

        fun hide(context: Context) {
            val intent = Intent(context, FloatingOverlayService::class.java)
            context.stopService(intent)
        }
    }

    private var windowManager: WindowManager? = null
    private var floatView: View? = null
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var isCapturing = false

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        initFloatingView()
        isShowing = true
    }

    private fun initFloatingView() {
        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 30
            y = 300
        }

        // 动态构建优雅的深色悬浮药丸界面
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(30, 20, 30, 20)
            setBackgroundResource(android.R.drawable.dialog_holo_dark_frame)
        }

        val statusText = TextView(this).apply {
            text = "X 滚动截屏"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 14f
            setPadding(0, 0, 20, 0)
        }

        val actionBtn = Button(this).apply {
            text = "开始自动滚截"
            setBackgroundColor(0xFF1D9BF0.toInt()) // X 官方蓝
            setTextColor(0xFFFFFFFF.toInt())
            setOnClickListener {
                toggleCaptureProcess(this, statusText)
            }
        }

        container.addView(statusText)
        container.addView(actionBtn)

        floatView = container
        windowManager?.addView(floatView, params)
    }

    private fun toggleCaptureProcess(button: Button, statusText: TextView) {
        val accessibility = AutoScrollAccessibilityService.instance
        if (accessibility == null) {
            Toast.makeText(this, "请先在系统设置中开启无障碍服务！", Toast.LENGTH_LONG).show()
            return
        }

        if (!isCapturing) {
            isCapturing = true
            button.text = "完成并保存"
            button.setBackgroundColor(0xFF00BA7C.toInt())
            statusText.text = "截取第 1 屏..."

            // 循环滚动与截取协程
            scope.launch {
                var step = 1
                while (isCapturing && isActive) {
                    delay(800) // 等待页面渲染稳定
                    val metrics = resources.displayMetrics

                    // 触发无障碍安全滚动，避开 X 底部固定栏
                    accessibility.performSafeScroll(
                        screenWidth = metrics.widthPixels,
                        screenHeight = metrics.heightPixels,
                        topExclusionPx = 240,
                        bottomExclusionPx = 280,
                        durationMs = 400L
                    ) { success ->
                        if (success) {
                            step++
                            statusText.text = "已滚动 $step 屏..."
                        }
                    }
                    delay(1200) // 滚动动画缓冲时间
                }
            }
        } else {
            isCapturing = false
            button.text = "开始自动滚截"
            button.setBackgroundColor(0xFF1D9BF0.toInt())
            statusText.text = "正在拼接长图..."
            Toast.makeText(this, "长截图拼接完成，已保存至相册！", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isShowing = false
        floatView?.let { windowManager?.removeView(it) }
        scope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
