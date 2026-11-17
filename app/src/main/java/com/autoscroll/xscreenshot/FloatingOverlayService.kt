package com.autoscroll.xscreenshot

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.*
import android.provider.Settings
import android.view.*
import android.widget.*
import kotlinx.coroutines.*
import kotlin.math.abs

class FloatingOverlayService : Service() {

    companion object {
        private var isShowing = false

        fun show(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
                Toast.makeText(context, "请先授予悬浮窗权限！", Toast.LENGTH_LONG).show()
                return
            }
            if (!isShowing) {
                val intent = Intent(context, FloatingOverlayService::class.java)
                context.startService(intent)
            }
        }

        fun hide(context: Context) {
            if (isShowing) {
                val intent = Intent(context, FloatingOverlayService::class.java)
                context.stopService(intent)
            }
        }
    }

    private var windowManager: WindowManager? = null
    private var floatView: View? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var captureJob: Job? = null
    private var isCapturing = false
    private val capturedFrames = mutableListOf<Bitmap>()

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "未获得悬浮窗显示权限，请先开启权限", Toast.LENGTH_LONG).show()
            stopSelf()
            return
        }
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        initFloatingView()
    }

    private fun initFloatingView() {
        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val metrics = resources.displayMetrics
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (metrics.widthPixels * 0.42f).toInt().coerceAtLeast(24)
            y = 300
        }

        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        val container = object : LinearLayout(this) {
            private var downRawX = 0f
            private var downRawY = 0f
            private var startX = 0
            private var startY = 0
            private var isDragging = false

            override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downRawX = ev.rawX
                        downRawY = ev.rawY
                        startX = params.x
                        startY = params.y
                        isDragging = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (abs(ev.rawX - downRawX) > touchSlop || abs(ev.rawY - downRawY) > touchSlop) {
                            isDragging = true
                            return true
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        isDragging = false
                    }
                }
                return super.onInterceptTouchEvent(ev)
            }

            override fun onTouchEvent(ev: MotionEvent): Boolean {
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downRawX = ev.rawX
                        downRawY = ev.rawY
                        startX = params.x
                        startY = params.y
                        isDragging = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (ev.rawX - downRawX).toInt()
                        val dy = (ev.rawY - downRawY).toInt()
                        if (!isDragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                            isDragging = true
                        }
                        if (isDragging) {
                            val screenMetrics = resources.displayMetrics
                            val maxX = (screenMetrics.widthPixels - width).coerceAtLeast(0)
                            val maxY = (screenMetrics.heightPixels - height).coerceAtLeast(0)
                            params.x = (startX + dx).coerceIn(0, maxX)
                            params.y = (startY + dy).coerceIn(0, maxY)
                            try {
                                windowManager?.updateViewLayout(this, params)
                            } catch (_: Exception) {}
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (!isDragging) {
                            performClick()
                        }
                        isDragging = false
                        return true
                    }
                }
                return super.onTouchEvent(ev)
            }

            override fun performClick(): Boolean {
                return super.performClick()
            }
        }.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(28, 18, 20, 18)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E618181B"))
                cornerRadius = 48f
                setStroke(2, Color.parseColor("#3F3F46"))
            }
        }

        val statusText = TextView(this).apply {
            text = "X 滚动截屏"
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(0, 0, 18, 0)
        }

        val actionBtn = Button(this).apply {
            text = "开始滚截"
            textSize = 12f
            setTextColor(Color.WHITE)
            setPadding(24, 10, 24, 10)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1D9BF0"))
                cornerRadius = 36f
            }
            setOnClickListener {
                toggleCaptureProcess(this, statusText)
            }
        }

        val closeBtn = TextView(this).apply {
            text = "✕"
            textSize = 14f
            setTextColor(Color.parseColor("#A1A1AA"))
            setPadding(18, 6, 8, 6)
            setOnClickListener {
                isShowing = false
                isCapturing = false
                captureJob?.cancel()
                // 同步停止后台录屏前台服务并移除通知栏常驻提醒
                ScreenCaptureService.stop(this@FloatingOverlayService)
                stopSelf()
            }
        }

        container.addView(statusText)
        container.addView(actionBtn)
        container.addView(closeBtn)

        try {
            windowManager?.addView(container, params)
            floatView = container
            isShowing = true
        } catch (e: Exception) {
            Toast.makeText(this, "悬浮窗启动失败: ${e.message}", Toast.LENGTH_LONG).show()
            isShowing = false
            stopSelf()
        }
    }

    /**
     * 隐藏悬浮球 -> 清空旧缓冲 -> 等待 SurfaceFlinger 重绘并输出纯净帧 -> 恢复悬浮球
     */
    private suspend fun captureCleanFrame(): Bitmap? {
        val view = floatView
        val captureService = ScreenCaptureService.instance ?: return null

        // 先清掉隐藏悬浮球之前的残留帧，再隐藏悬浮球强制 VirtualDisplay 合成纯净画面
        captureService.drainPendingFrames()
        view?.visibility = View.INVISIBLE

        return try {
            // 等待按压水波纹消失及至少多个 VSync 周期完成无悬浮球合成
            delay(260)

            var frame = captureService.captureCurrentFrame()
            if (frame == null) {
                delay(120)
                frame = captureService.captureCurrentFrame()
            }
            frame
        } finally {
            if (isShowing) {
                view?.visibility = View.VISIBLE
            }
        }
    }

    private fun toggleCaptureProcess(button: Button, statusText: TextView) {
        val accessibility = AutoScrollAccessibilityService.instance
        if (accessibility == null) {
            Toast.makeText(this, "请先在系统设置中开启无障碍服务！", Toast.LENGTH_LONG).show()
            return
        }

        if (!isCapturing) {
            isCapturing = true
            clearFrames()
            button.text = "完成并保存"
            (button.background as? GradientDrawable)?.setColor(Color.parseColor("#00BA7C"))
            statusText.text = "准备截屏..."

            val prefs = getSharedPreferences("x_screenshot_prefs", Context.MODE_PRIVATE)
            val bottomExclusionPx = prefs.getInt("bottom_crop_px", 260)
            val topExclusionPx = 240

            captureJob?.cancel()
            captureJob = scope.launch {
                // 采集首屏（先隐藏悬浮球并等待干净帧）
                captureCleanFrame()?.let { frame ->
                    if (isCapturing) {
                        capturedFrames.add(frame)
                        statusText.text = "已采集 ${capturedFrames.size} 屏"
                    } else {
                        frame.recycle()
                    }
                }

                while (isCapturing && isActive) {
                    val metrics = resources.displayMetrics
                    val scrollDeferred = CompletableDeferred<Boolean>()

                    accessibility.performSafeScroll(
                        screenWidth = metrics.widthPixels,
                        screenHeight = metrics.heightPixels,
                        topExclusionPx = topExclusionPx,
                        bottomExclusionPx = bottomExclusionPx,
                        durationMs = 420L
                    ) { success ->
                        scrollDeferred.complete(success)
                    }

                    val scrolled = scrollDeferred.await()
                    if (!scrolled || !isCapturing) break

                    delay(800) // 等待滚动惯性与画面完全静止
                    if (!isCapturing) break

                    captureCleanFrame()?.let { frame ->
                        if (isCapturing) {
                            capturedFrames.add(frame)
                            statusText.text = "已采集 ${capturedFrames.size} 屏"
                        } else {
                            frame.recycle()
                        }
                    }
                }
            }
        } else {
            isCapturing = false
            captureJob?.cancel()
            floatView?.visibility = View.VISIBLE
            button.isEnabled = false
            button.text = "拼接中..."
            statusText.text = "正在合成长图..."

            val prefs = getSharedPreferences("x_screenshot_prefs", Context.MODE_PRIVATE)
            val bottomCustomPx = prefs.getInt("bottom_crop_px", 260)

            scope.launch {
                val framesToStitch = capturedFrames.toList()
                val stitcher = XSmartStitcher(
                    XSmartStitcher.StitchConfig(
                        topCutCustomPx = 240,
                        bottomCutCustomPx = bottomCustomPx
                    )
                )

                val savedUri = withContext(Dispatchers.Default) {
                    if (framesToStitch.isEmpty()) return@withContext null
                    val resultBmp = stitcher.stitch(framesToStitch)
                    val uri = ImageSaver.saveToGallery(
                        this@FloatingOverlayService,
                        resultBmp,
                        "X_LongScreenshot"
                    )
                    resultBmp.recycle()
                    uri
                }

                clearFrames()
                button.isEnabled = true
                button.text = "开始滚截"
                (button.background as? GradientDrawable)?.setColor(Color.parseColor("#1D9BF0"))
                statusText.text = "X 滚动截屏"

                val tip = if (savedUri != null) {
                    "长截图拼接完成（${framesToStitch.size} 屏），已保存至相册！"
                } else {
                    "未获取到有效截屏帧，请先开启截屏授权！"
                }
                Toast.makeText(this@FloatingOverlayService, tip, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun clearFrames() {
        for (bmp in capturedFrames) {
            if (!bmp.isRecycled) bmp.recycle()
        }
        capturedFrames.clear()
    }

    override fun onDestroy() {
        super.onDestroy()
        isShowing = false
        isCapturing = false
        clearFrames()
        floatView?.let {
            try {
                if (it.isAttachedToWindow) {
                    windowManager?.removeView(it)
                }
            } catch (_: Exception) {}
        }
        floatView = null
        scope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
