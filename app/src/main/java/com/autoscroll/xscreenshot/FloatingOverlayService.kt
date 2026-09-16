package com.autoscroll.xscreenshot

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.*
import android.provider.Settings
import android.view.*
import android.widget.*
import kotlinx.coroutines.*

class FloatingOverlayService : Service() {

    companion object {
        fun show(context: Context) {
            val intent = Intent(context, FloatingOverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startService(intent)
            } else {
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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "未授予悬浮窗权限，无法显示控制球！", Toast.LENGTH_LONG).show()
            return START_NOT_STICKY
        }

        if (floatView == null) {
            initFloatingView()
        }
        return START_NOT_STICKY
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
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 50
            y = 400
        }

        // 创建高质感胶囊悬浮卡片
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(36, 20, 36, 20)
            
            // 黑色半透明圆角背景
            val bg = GradientDrawable().apply {
                setColor(Color.parseColor("#E615202B"))
                cornerRadius = 60f
                setStroke(3, Color.parseColor("#1D9BF0"))
            }
            background = bg
            elevation = 20f
        }

        val statusText = TextView(this).apply {
            text = "X 截屏"
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(0, 0, 24, 0)
        }

        val actionBtn = Button(this).apply {
            text = "开始截取"
            textSize = 12f
            setTextColor(Color.WHITE)
            val btnBg = GradientDrawable().apply {
                setColor(Color.parseColor("#1D9BF0"))
                cornerRadius = 36f
            }
            background = btnBg
            setPadding(28, 12, 28, 12)
            setOnClickListener {
                toggleCaptureProcess(this, statusText)
            }
        }

        val closeBtn = TextView(this).apply {
            text = " ✕ "
            setTextColor(Color.parseColor("#8899A6"))
            textSize = 14f
            setPadding(16, 0, 0, 0)
            setOnClickListener {
                stopSelf()
            }
        }

        container.addView(statusText)
        container.addView(actionBtn)
        container.addView(closeBtn)

        // 增加手指自由拖动悬浮窗逻辑
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        container.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager?.updateViewLayout(container, params)
                    true
                }
                else -> false
            }
        }

        floatView = container
        try {
            windowManager?.addView(floatView, params)
            Toast.makeText(this, "悬浮控制球已弹出，可任意拖拽！", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "添加悬浮窗失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun toggleCaptureProcess(button: Button, statusText: TextView) {
        val accessibility = AutoScrollAccessibilityService.instance
        if (accessibility == null) {
            Toast.makeText(this, "请先在系统设置中开启无障碍服务！", Toast.LENGTH_LONG).show()
            return
        }

        val btnBg = button.background as? GradientDrawable

        if (!isCapturing) {
            isCapturing = true
            button.text = "停止保存"
            btnBg?.setColor(Color.parseColor("#00BA7C"))
            statusText.text = "截取第 1 屏..."

            scope.launch {
                var step = 1
                while (isCapturing && isActive) {
                    delay(800)
                    val metrics = resources.displayMetrics
                    accessibility.performSafeScroll(
                        screenWidth = metrics.widthPixels,
                        screenHeight = metrics.heightPixels,
                        topExclusionPx = 240,
                        bottomExclusionPx = 280,
                        durationMs = 420L
                    ) { success ->
                        if (success) {
                            step++
                            statusText.text = "已录制 $step 屏..."
                        }
                    }
                    delay(1200)
                }
            }
        } else {
            isCapturing = false
            button.text = "开始截取"
            btnBg?.setColor(Color.parseColor("#1D9BF0"))
            statusText.text = "长图已保存"
            Toast.makeText(this, "长截图已生成！", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        floatView?.let {
            try {
                windowManager?.removeView(it)
            } catch (e: Exception) {
                // ignore
            }
        }
        floatView = null
        scope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
