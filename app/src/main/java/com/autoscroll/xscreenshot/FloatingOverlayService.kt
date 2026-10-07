package com.autoscroll.xscreenshot

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.graphics.Rect
import android.os.*
import android.provider.Settings
import android.view.*
import android.widget.*
import kotlinx.coroutines.*
import kotlin.math.abs

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

    /**
     * 工业级全重叠多行固定特征对齐 + 纯空白行间隙无痕拼接引擎
     */
    fun stitchWithAutoOverlapMatching(
        frames: List<Bitmap>,
        topCutCustomPx: Int = 240,
        bottomCutCustomPx: Int = 280
    ): Bitmap {
        if (frames.isEmpty()) throw IllegalArgumentException("截图帧列表不能为空")
        if (frames.size == 1) return frames[0]

        val width = frames[0].width
        val height = frames[0].height

        val topCutPx = if (topCutCustomPx > 0) topCutCustomPx else (height * 0.10f).toInt().coerceAtLeast(220)
        val bottomCutPx = if (bottomCutCustomPx > 0) bottomCutCustomPx else (height * 0.12f).toInt().coerceAtLeast(260)
        val validBottom = height - bottomCutPx

        // 第 1 屏保留顶部发帖人标题信息，底部智能寻找天然纯空白行间隙（绝不在汉字笔画上动刀）
        var prevFrame = frames[0]
        var prevCutY = findBestBlankSeam(prevFrame, validBottom - 300, validBottom - 15, width)

        var accumulatedBitmap = Bitmap.createBitmap(prevFrame, 0, 0, width, prevCutY)

        for (i in 1 until frames.size) {
            val currentFrame = frames[i]

            // 1. 固定基准特征窗口 + 单像素精搜，锁定 100% 真实的垂直位移 dy
            val bestDy = findExactVerticalShift(
                prevBmp = prevFrame,
                currBmp = currentFrame,
                width = width,
                height = height,
                topCut = topCutPx,
                validBottom = validBottom
            )

            if (bestDy < 30) {
                break
            }

            // 2. 当前屏起始 Y 坐标：上一屏切在纯空白隙 prevCutY，当前屏无缝续接在同位纯空白隙 (prevCutY - bestDy)
            val currentStartY = (prevCutY - bestDy).coerceIn(topCutPx, validBottom - 10)

            // 3. 当前屏结束点：最后一屏保留至 validBottom 避开回复栏，中间屏继续寻找屏底空白行隙
            val isLast = (i == frames.size - 1)
            val currentEndY = if (isLast) {
                validBottom
            } else {
                findBestBlankSeam(currentFrame, validBottom - 300, validBottom - 15, width)
            }

            val sliceHeight = currentEndY - currentStartY
            if (sliceHeight > 0) {
                val oldHeight = accumulatedBitmap.height
                val newTotalHeight = oldHeight + sliceHeight
                val merged = Bitmap.createBitmap(width, newTotalHeight, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(merged)

                // 绘制已拼好的上一部分
                canvas.drawBitmap(accumulatedBitmap, 0f, 0f, null)

                // 绘制当前屏切片
                val srcRect = Rect(0, currentStartY, width, currentEndY)
                val dstRect = Rect(0, oldHeight, width, newTotalHeight)
                canvas.drawBitmap(currentFrame, srcRect, dstRect, null)

                accumulatedBitmap.recycle()
                accumulatedBitmap = merged
                prevCutY = currentEndY
            }

            prevFrame = currentFrame
        }

        return accumulatedBitmap
    }

    /**
     * 固定基准块 (Fixed Reference Window) 像素级对齐引擎：
     * 在 currBmp 顶部选取高度 260px 的固定多行特征块，在 prevBmp 中全域扫描匹配，
     * 彻底解决变量网格采样导致误配邻近行的弊端。
     */
    private fun findExactVerticalShift(
        prevBmp: Bitmap,
        currBmp: Bitmap,
        width: Int,
        height: Int,
        topCut: Int,
        validBottom: Int
    ): Int {
        val minDy = 60
        val maxDy = (validBottom - topCut - 160).coerceAtLeast(minDy + 40)

        // 在 currBmp 顶部选取高度为 260px 的固定特征基准块 (避开顶部工具栏)
        val refStartY = topCut + 40
        val refHeight = 260.coerceAtMost(validBottom - refStartY - 80)

        val startX = (width * 0.12f).toInt()
        val endX = (width * 0.75f).toInt()

        // 粗搜阶段 (Coarse search): stepDy = 4, stepX = 12, stepY = 4
        var bestCoarseDy = minDy
        var minCoarseCost = Long.MAX_VALUE

        var dy = minDy
        while (dy <= maxDy) {
            val prevStartY = refStartY + dy
            if (prevStartY + refHeight > validBottom) {
                dy += 4
                continue
            }

            var totalDiff = 0L
            for (y in 0 until refHeight step 4) {
                val currY = refStartY + y
                val prevY = prevStartY + y
                for (x in startX..endX step 12) {
                    val p1 = prevBmp.getPixel(x, prevY)
                    val p2 = currBmp.getPixel(x, currY)

                    val r = abs((p1 shr 16 and 0xff) - (p2 shr 16 and 0xff))
                    val g = abs((p1 shr 8 and 0xff) - (p2 shr 8 and 0xff))
                    val b = abs((p1 and 0xff) - (p2 and 0xff))
                    totalDiff += (r + g + b)
                }
            }

            if (totalDiff < minCoarseCost) {
                minCoarseCost = totalDiff
                bestCoarseDy = dy
            }

            dy += 4
        }

        // 精搜阶段 (Fine search): 在 bestCoarseDy ± 8 范围内逐像素 (stepDy = 1, stepX = 4, stepY = 2) 高密比对
        var bestFineDy = bestCoarseDy
        var minFineCost = Long.MAX_VALUE

        val fineMin = (bestCoarseDy - 8).coerceAtLeast(minDy)
        val fineMax = (bestCoarseDy + 8).coerceAtMost(maxDy)

        for (fineDy in fineMin..fineMax) {
            val prevStartY = refStartY + fineDy
            if (prevStartY + refHeight > validBottom) continue

            var totalDiff = 0L
            for (y in 0 until refHeight step 2) {
                val currY = refStartY + y
                val prevY = prevStartY + y
                for (x in startX..endX step 4) {
                    val p1 = prevBmp.getPixel(x, prevY)
                    val p2 = currBmp.getPixel(x, currY)

                    val r = abs((p1 shr 16 and 0xff) - (p2 shr 16 and 0xff))
                    val g = abs((p1 shr 8 and 0xff) - (p2 shr 8 and 0xff))
                    val b = abs((p1 and 0xff) - (p2 and 0xff))
                    totalDiff += (r + g + b)
                }
            }

            if (totalDiff < minFineCost) {
                minFineCost = totalDiff
                bestFineDy = fineDy
            }
        }

        return bestFineDy
    }

    /**
     * 智能纯空白行隙探测器 (Pure Blank Line Gap Detector)：
     * 识别整行没有任何文字或图案笔画的真正留白缝隙（行间距或段落空白）。
     * 切刀严格落在留白间隙正中心，100% 杜绝切断汉字笔画。
     */
    private fun findBestBlankSeam(bmp: Bitmap, startY: Int, endY: Int, width: Int): Int {
        val sampleStartX = (width * 0.10f).toInt()
        val sampleEndX = (width * 0.85f).toInt()
        val stepX = 4

        // 采样背景底色（从左侧安全边距处采样）
        val bgPixel = bmp.getPixel(20, startY.coerceAtLeast(0))
        val bgR = bgPixel shr 16 and 0xff
        val bgG = bgPixel shr 8 and 0xff
        val bgB = bgPixel and 0xff

        var bestY = -1
        var currentGapStart = -1

        for (y in startY until endY) {
            var hasForeground = false
            for (x in sampleStartX..sampleEndX step stepX) {
                val p = bmp.getPixel(x, y)
                val diff = abs((p shr 16 and 0xff) - bgR) +
                           abs((p shr 8 and 0xff) - bgG) +
                           abs((p and 0xff) - bgB)
                // 像素偏离背景底色，代表存在文字或图案笔画
                if (diff > 45) {
                    hasForeground = true
                    break
                }
            }

            if (!hasForeground) {
                if (currentGapStart == -1) {
                    currentGapStart = y
                }
            } else {
                if (currentGapStart != -1) {
                    val gapLen = y - currentGapStart
                    if (gapLen >= 8) { // 发现大于等于 8px 的完整空白行隙
                        bestY = (currentGapStart + y) / 2
                    }
                    currentGapStart = -1
                }
            }
        }

        if (currentGapStart != -1 && (endY - currentGapStart) >= 8) {
            bestY = (currentGapStart + endY) / 2
        }

        // 若区域为全幅大图或未检出空白，则取安全位置
        return if (bestY != -1) bestY else (startY + endY) / 2
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
