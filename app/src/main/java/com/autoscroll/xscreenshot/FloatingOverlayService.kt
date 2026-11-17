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

        val container = LinearLayout(this).apply {
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
            statusText.text = "采集中 (1)..."

            val prefs = getSharedPreferences("x_screenshot_prefs", Context.MODE_PRIVATE)
            val bottomExclusionPx = prefs.getInt("bottom_crop_px", 280)
            val topExclusionPx = 240

            scope.launch {
                // 采集首屏
                delay(400)
                ScreenCaptureService.instance?.captureCurrentFrame()?.let {
                    capturedFrames.add(it)
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

                    delay(900) // 等待滚动惯性与画面静止
                    if (!isCapturing) break

                    ScreenCaptureService.instance?.captureCurrentFrame()?.let { frame ->
                        capturedFrames.add(frame)
                        statusText.text = "已采集 ${capturedFrames.size} 屏"
                    }
                }
            }
        } else {
            isCapturing = false
            button.isEnabled = false
            button.text = "拼接中..."
            statusText.text = "正在合成长图..."

            val prefs = getSharedPreferences("x_screenshot_prefs", Context.MODE_PRIVATE)
            val bottomCustomPx = prefs.getInt("bottom_crop_px", 280)

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
