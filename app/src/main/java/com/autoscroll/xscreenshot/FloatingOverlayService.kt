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
        val captureService = ScreenCaptureService.instance
        if (captureService == null) {
            Toast.makeText(this, "截屏服务未就绪，请重新在主界面启动！", Toast.LENGTH_LONG).show()
            return
        }

        val btnBg = button.background as? GradientDrawable

        if (!isCapturing) {
            isCapturing = true
            button.text = "停止保存"
            btnBg?.setColor(Color.parseColor("#00BA7C"))
            statusText.text = "截取第 1 屏..."

            val capturedList = ArrayList<android.graphics.Bitmap>()

            scope.launch {
                val metrics = resources.displayMetrics
                val screenW = metrics.widthPixels
                val screenH = metrics.heightPixels

                // 底部避让：X 的固定回复栏 + 手机系统导航条（约 300px）
                // 动态读取主界面滑块设置的避让高度（默认 280px）
                val prefs = getSharedPreferences("app_config", Context.MODE_PRIVATE)
                val bottomExclude = prefs.getInt("bottom_crop_px", 280)
                // 顶部避让：状态栏与 X 标题栏（约 200px）
                val topExclude = 200

                // 1. 首屏截取
                delay(500)
                captureWithHiddenOverlay(captureService)?.let { capturedList.add(it) }

                var step = 1
                while (isCapturing && isActive) {
                    // 2. 每次温和滚动屏幕高度的 50%~60%，保留充足的重叠对齐参考区，杜绝断层丢失
                    val scrollDistance = (screenH * 0.55f).toInt()
                    val startY = screenH - bottomExclude - 50
                    val endY = startY - scrollDistance

                    val path = android.graphics.Path().apply {
                        moveTo(screenW / 2f, startY.toFloat())
                        lineTo(screenW / 2f, endY.toFloat())
                    }
                    val stroke = android.accessibilityservice.GestureDescription.StrokeDescription(path, 0L, 400L)
                    val gesture = android.accessibilityservice.GestureDescription.Builder().addStroke(stroke).build()

                    accessibility.dispatchGesture(gesture, null, null)

                    // 等待页面滚动完成并让文本和图片静止清晰渲染
                    delay(1200)

                    if (!isCapturing || !isActive) break

                    // 3. 截取下一屏
                    captureWithHiddenOverlay(captureService)?.let {
                        capturedList.add(it)
                        step++
                        statusText.text = "已录制 $step 屏..."
                    }
                }

                // 4. 开始图像自适应像素对齐拼接
                if (capturedList.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        statusText.text = "正在自适应拼接..."
                    }

                    val finalLongBitmap = withContext(Dispatchers.Default) {
                        stitchWithAutoOverlapMatching(capturedList, topExclude, bottomExclude)
                    }

                    val savedUri = withContext(Dispatchers.IO) {
                        captureService.saveBitmapToGallery(applicationContext, finalLongBitmap)
                    }

                    withContext(Dispatchers.Main) {
                        if (savedUri != null) {
                            statusText.text = "已存入相册 ✓"
                            Toast.makeText(applicationContext, "🎉 长截图完美拼接完成，已保存至相册！", Toast.LENGTH_LONG).show()
                        } else {
                            statusText.text = "保存失败"
                            Toast.makeText(applicationContext, "保存相册失败", Toast.LENGTH_SHORT).show()
                        }
                        button.text = "开始截取"
                        btnBg?.setColor(Color.parseColor("#1D9BF0"))
                    }
                }
            }
        } else {
            isCapturing = false
            statusText.text = "正在生成长图..."
        }
    }

    // 智能寻找两张截屏之间的重叠接缝，实现 0 像素误差拼接
    private fun stitchWithAutoOverlapMatching(
        list: List<android.graphics.Bitmap>,
        topExclude: Int,
        bottomExclude: Int
    ): android.graphics.Bitmap {
        if (list.size == 1) {
            val w = list[0].width
            val h = (list[0].height - bottomExclude).coerceAtLeast(200)
            return android.graphics.Bitmap.createBitmap(list[0], 0, 0, w, h)
        }

        val width = list[0].width
        val screenHeight = list[0].height

        // 第一屏有效区域：从 0 到屏幕高度 - bottomExclude
        val firstCleanHeight = screenHeight - bottomExclude
        var accumulatedBitmap = android.graphics.Bitmap.createBitmap(list[0], 0, 0, width, firstCleanHeight)

        for (i in 1 until list.size) {
            val currentBmp = list[i]

            // 在上一张图的底部抽取一条采样特征带（高度 30px）
            val sampleH = 30
            val sampleYInPrev = accumulatedBitmap.height - 40
            if (sampleYInPrev < 100) continue

            val samplePixels = IntArray(width * sampleH)
            accumulatedBitmap.getPixels(samplePixels, 0, width, 0, sampleYInPrev, width, sampleH)

            // 在当前屏的动态内容区（从 topExclude 到 screenHeight - bottomExclude）搜索匹配点
            val searchStartY = topExclude
            val searchEndY = (screenHeight - bottomExclude - sampleH).coerceAtLeast(searchStartY)

            var bestMatchY = -1
            var minDiff = Long.MAX_VALUE

            // 步长为 2 像素快速扫描最佳吻合点
            val testPixels = IntArray(width * sampleH)
            for (candidateY in searchStartY..searchEndY step 2) {
                currentBmp.getPixels(testPixels, 0, width, 0, candidateY, width, sampleH)

                // 采样计算色值差异（每隔 8 个像素采一个点以提高速度）
                var diff = 0L
                for (k in 0 until (width * sampleH) step 8) {
                    val p1 = samplePixels[k]
                    val p2 = testPixels[k]
                    val r = ((p1 shr 16) and 0xff) - ((p2 shr 16) and 0xff)
                    val g = ((p1 shr 8) and 0xff) - ((p2 shr 8) and 0xff)
                    val b = (p1 and 0xff) - (p2 and 0xff)
                    diff += (r * r + g * g + b * b)
                }

                if (diff < minDiff) {
                    minDiff = diff
                    bestMatchY = candidateY
                }
            }

            // 如果匹配成功（找到重合切入点），只把匹配点之后的新增内容接在后面；
            // 如果没找到满意的点，做安全保底对齐
            val newContentStartY = if (bestMatchY > 0) bestMatchY + sampleH else topExclude + 200
            val newContentEndY = screenHeight - bottomExclude
            val newContentHeight = (newContentEndY - newContentStartY).coerceAtLeast(0)

            if (newContentHeight > 0) {
                val newTotalHeight = accumulatedBitmap.height + newContentHeight
                val merged = android.graphics.Bitmap.createBitmap(width, newTotalHeight, android.graphics.Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(merged)

                // 绘制已拼好的部分
                canvas.drawBitmap(accumulatedBitmap, 0f, 0f, null)
                // 绘制新拼上的无缝部分
                val srcRect = android.graphics.Rect(0, newContentStartY, width, newContentEndY)
                val dstRect = android.graphics.Rect(0, accumulatedBitmap.height, width, newTotalHeight)
                canvas.drawBitmap(currentBmp, srcRect, dstRect, null)

                accumulatedBitmap.recycle()
                accumulatedBitmap = merged
            }
        }

        return accumulatedBitmap
    }

    // 截图瞬间让悬浮窗完全透明，确保截出的图片干干净净
    private suspend fun captureWithHiddenOverlay(service: ScreenCaptureService): android.graphics.Bitmap? {
        return withContext(Dispatchers.Main) {
            floatView?.visibility = View.INVISIBLE
            delay(80) // 给系统渲染一帧的时间隐藏悬浮球
            val bmp = service.captureCurrentFrame()
            floatView?.visibility = View.VISIBLE
            bmp
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
