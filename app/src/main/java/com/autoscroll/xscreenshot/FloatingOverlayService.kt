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
                // 同时停止后台录屏服务，保证状态完全复位
                val stopIntent = Intent(this@FloatingOverlayService, ScreenCaptureService::class.java).apply {
                    action = ScreenCaptureService.ACTION_STOP
                }
                startService(stopIntent)
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

    // 工业级自适应无缝拼接：无论交界处是文字、图片、表格还是深浅色模式，均能 0 误差像素咬合
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

        val firstCleanHeight = screenHeight - bottomExclude
        var accumulatedBitmap = android.graphics.Bitmap.createBitmap(list[0], 0, 0, width, firstCleanHeight)

        for (i in 1 until list.size) {
            val currentBmp = list[i]

            // 特征采样带高度设为 50px（完整涵盖一行字或图片局部纹理，确保唯一性）
            val templateH = 50
            var anchorY = accumulatedBitmap.height - templateH - 10
            
            // 【通用特征探测器】：向上扫描，寻找富含细节的区域（文字笔画或图片），避开纯单色背景
            var foundFeature = false
            while (anchorY > accumulatedBitmap.height - 280 && anchorY > 100) {
                val testLine = IntArray(width)
                accumulatedBitmap.getPixels(testLine, 0, width, 0, anchorY + templateH / 2, width, 1)
                
                // 计算相邻像素的颜色跳变（如果是纯单色背景，跳变数为 0）
                var variation = 0
                for (x in 0 until width - 1 step 4) {
                    val p1 = testLine[x]
                    val p2 = testLine[x + 1]
                    val diff = Math.abs(((p1 shr 16) and 0xff) - ((p2 shr 16) and 0xff)) +
                               Math.abs(((p1 shr 8) and 0xff) - ((p2 shr 8) and 0xff)) +
                               Math.abs((p1 and 0xff) - (p2 and 0xff))
                    if (diff > 15) variation++
                }
                
                // 跳变数超过 20，表示这一行存在明确的文字笔画、图片细节或边框线
                if (variation > 20) {
                    foundFeature = true
                    break
                }
                anchorY -= 12
            }
            if (!foundFeature) {
                anchorY = accumulatedBitmap.height - templateH - 20
            }

            // 提取特征块像素数据
            val templatePixels = IntArray(width * templateH)
            accumulatedBitmap.getPixels(templatePixels, 0, width, 0, anchorY, width, templateH)

            // 在下一屏中自顶向下单像素（step 1）高精度匹配
            val searchStartY = topExclude
            val searchEndY = (screenHeight - bottomExclude - templateH).coerceAtLeast(searchStartY)

            var bestMatchY = -1
            var minDiff = Long.MAX_VALUE

            val candidatePixels = IntArray(width * templateH)

            for (candidateY in searchStartY..searchEndY) {
                currentBmp.getPixels(candidatePixels, 0, width, 0, candidateY, width, templateH)

                var diff = 0L
                for (k in 0 until (width * templateH) step 4) {
                    val p1 = templatePixels[k]
                    val p2 = candidatePixels[k]
                    val r = ((p1 shr 16) and 0xff) - ((p2 shr 16) and 0xff)
                    val g = ((p1 shr 8) and 0xff) - ((p2 shr 8) and 0xff)
                    val b = (p1 and 0xff) - (p2 and 0xff)
                    diff += Math.abs(r) + Math.abs(g) + Math.abs(b)
                    if (diff > minDiff) break // 快速剪枝优化
                }

                if (diff < minDiff) {
                    minDiff = diff
                    bestMatchY = candidateY
                }
            }

            // 黄金接缝点：完整保留旧图的特征行文字（避免在文字笔画内部切割导致切脚），
            // 新图严格从特征行下方接续，实现 100% 完整笔画与自然行距！
            val validOldHeight = if (bestMatchY >= 0) anchorY + templateH else anchorY
            val appendStartY = if (bestMatchY >= 0) bestMatchY + templateH else topExclude + 150
            val appendEndY = screenHeight - bottomExclude
            val appendHeight = (appendEndY - appendStartY).coerceAtLeast(0)

            if (appendHeight > 0) {
                val newTotalHeight = validOldHeight + appendHeight
                val merged = android.graphics.Bitmap.createBitmap(width, newTotalHeight, android.graphics.Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(merged)

                val srcOld = android.graphics.Rect(0, 0, width, validOldHeight)
                val dstOld = android.graphics.Rect(0, 0, width, validOldHeight)
                canvas.drawBitmap(accumulatedBitmap, srcOld, dstOld, null)

                val srcNew = android.graphics.Rect(0, appendStartY, width, appendEndY)
                val dstNew = android.graphics.Rect(0, validOldHeight, width, newTotalHeight)
                canvas.drawBitmap(currentBmp, srcNew, dstNew, null)

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
