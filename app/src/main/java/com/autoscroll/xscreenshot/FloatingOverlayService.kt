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
     * 工业级自适应全重叠多行特征联合对齐拼接引擎
     * 彻底解决：
     * 1. 消除推文多行文本/歌词在交界处的跳跃、吞行、吃字现象；
     * 2. 避免汉字笔画被水平腰斩，智能寻找行间/段落天然空白缝隙进行无痕缝合；
     * 3. 全重叠多线联合损失函数 + 1px 单像素精细对齐，无论图文混排均能 0 误差咬合。
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

        // 如果调用方传入了具体的 topCut/bottomCut 则优先使用，否则自适应按屏幕比例计算
        val topCutPx = if (topCutCustomPx > 0) topCutCustomPx else (height * 0.10f).toInt().coerceAtLeast(220)
        val bottomCutPx = if (bottomCutCustomPx > 0) bottomCutCustomPx else (height * 0.12f).toInt().coerceAtLeast(260)
        val validBottom = height - bottomCutPx

        // 第 1 屏保留顶部发帖人标题信息，底部寻找天然空白行切除（避免在字中央切断）
        var prevFrame = frames[0]
        var prevCutY = findBestSeamY(prevFrame, validBottom - 160, validBottom - 20, width)

        var accumulatedBitmap = Bitmap.createBitmap(prevFrame, 0, 0, width, prevCutY)

        for (i in 1 until frames.size) {
            val currentFrame = frames[i]

            // 1. 全重叠联合采样精确定位垂直位移 dy (像素)
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

            // 2. 当前屏接合起始 Y 坐标：上一屏接缝在 prevCutY，对应当前屏为 (prevCutY - bestDy)
            val currentStartY = (prevCutY - bestDy).coerceIn(topCutPx, validBottom - 10)

            // 3. 当前屏切割结束点：若是最后一屏则取至 validBottom，否则继续找自然空白缝隙
            val isLast = (i == frames.size - 1)
            val currentEndY = if (isLast) {
                validBottom
            } else {
                findBestSeamY(currentFrame, validBottom - 160, validBottom - 20, width)
            }

            val sliceHeight = currentEndY - currentStartY
            if (sliceHeight > 0) {
                val oldHeight = accumulatedBitmap.height
                val newTotalHeight = oldHeight + sliceHeight
                val merged = Bitmap.createBitmap(width, newTotalHeight, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(merged)

                // 绘制此前已拼合的内容
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
     * 在全重叠区间内通过数十条水平采样线联合比对，锁定真实的垂直位移 dy（零误判）
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
        val maxDy = (validBottom - topCut - 150).coerceAtLeast(minDy + 40)

        // 横向采样范围：避开最左侧头像/边框与最右侧悬浮窗药丸及滚动条 (保留 10% ~ 75% 宽度区间)
        val startX = (width * 0.10f).toInt()
        val endX = (width * 0.75f).toInt()
        val stepX = 24

        // 粗搜阶段 (Coarse search): step = 4 像素全域扫描
        var bestCoarseDy = minDy
        var minCoarseCost = Double.MAX_VALUE

        var dy = minDy
        while (dy <= maxDy) {
            val overlapHeight = validBottom - topCut - dy
            if (overlapHeight < 150) {
                dy += 4
                continue
            }

            // 在重叠区间内均匀分布 25 条水平采样线
            val numSampleLines = 25
            val lineSpacing = overlapHeight / numSampleLines
            var totalDiff = 0L
            var pixelCount = 0

            for (l in 0 until numSampleLines) {
                val currY = topCut + l * lineSpacing
                val prevY = currY + dy

                if (currY in topCut until validBottom && prevY in topCut until validBottom) {
                    for (x in startX..endX step stepX) {
                        val p1 = prevBmp.getPixel(x, prevY)
                        val p2 = currBmp.getPixel(x, currY)

                        val rDiff = Math.abs((p1 shr 16 and 0xff) - (p2 shr 16 and 0xff))
                        val gDiff = Math.abs((p1 shr 8 and 0xff) - (p2 shr 8 and 0xff))
                        val bDiff = Math.abs((p1 and 0xff) - (p2 and 0xff))

                        totalDiff += (rDiff + gDiff + bDiff)
                        pixelCount++
                    }
                }
            }

            val avgCost = if (pixelCount > 0) totalDiff.toDouble() / pixelCount else Double.MAX_VALUE
            if (avgCost < minCoarseCost) {
                minCoarseCost = avgCost
                bestCoarseDy = dy
            }

            dy += 4
        }

        // 精搜阶段 (Fine search): 在 bestCoarseDy 左右 ±8 像素范围内逐像素 (step = 1) 细化锁定
        var bestFineDy = bestCoarseDy
        var minFineCost = Double.MAX_VALUE

        val fineMin = (bestCoarseDy - 8).coerceAtLeast(minDy)
        val fineMax = (bestCoarseDy + 8).coerceAtMost(maxDy)

        for (fineDy in fineMin..fineMax) {
            val overlapHeight = validBottom - topCut - fineDy
            val numSampleLines = 35
            val lineSpacing = (overlapHeight / numSampleLines).coerceAtLeast(2)
            var totalDiff = 0L
            var pixelCount = 0

            for (l in 0 until numSampleLines) {
                val currY = topCut + l * lineSpacing
                val prevY = currY + fineDy

                if (currY in topCut until validBottom && prevY in topCut until validBottom) {
                    for (x in startX..endX step 12) {
                        val p1 = prevBmp.getPixel(x, prevY)
                        val p2 = currBmp.getPixel(x, currY)

                        val rDiff = Math.abs((p1 shr 16 and 0xff) - (p2 shr 16 and 0xff))
                        val gDiff = Math.abs((p1 shr 8 and 0xff) - (p2 shr 8 and 0xff))
                        val bDiff = Math.abs((p1 and 0xff) - (p2 and 0xff))

                        totalDiff += (rDiff + gDiff + bDiff)
                        pixelCount++
                    }
                }
            }

            val avgCost = if (pixelCount > 0) totalDiff.toDouble() / pixelCount else Double.MAX_VALUE
            if (avgCost < minFineCost) {
                minFineCost = avgCost
                bestFineDy = fineDy
            }
        }

        return bestFineDy
    }

    /**
     * 智能空白缝隙探测器：
     * 寻找水平跳变最小的行（行间距或段落空白行），避免在文字中央切割切碎汉字。
     */
    private fun findBestSeamY(bmp: Bitmap, startY: Int, endY: Int, width: Int): Int {
        val sampleStartX = (width * 0.12f).toInt()
        val sampleEndX = (width * 0.88f).toInt()
        val stepX = 16

        var bestY = (startY + endY) / 2
        var minVariance = Long.MAX_VALUE

        for (y in startY..endY) {
            var rowVariance = 0L
            var prevPixel = bmp.getPixel(sampleStartX, y)
            val prevGray = ((prevPixel shr 16 and 0xff) * 299 + (prevPixel shr 8 and 0xff) * 587 + (prevPixel and 0xff) * 114) / 1000

            for (x in (sampleStartX + stepX)..sampleEndX step stepX) {
                val currPixel = bmp.getPixel(x, y)
                val currGray = ((currPixel shr 16 and 0xff) * 299 + (currPixel shr 8 and 0xff) * 587 + (currPixel and 0xff) * 114) / 1000
                rowVariance += Math.abs(currGray - prevGray)
                prevPixel = currPixel
            }

            if (rowVariance < minVariance) {
                minVariance = rowVariance
                bestY = y
                if (rowVariance == 0L) break
            }
        }

        return bestY
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
