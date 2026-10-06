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

    // 工业级物理约束自适应拼接：锁定真实位移窗口，杜绝漏段、吞字与跳过内容
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

        // 每次手势实际滚动的物理位移约 50%~55% 屏幕高
        val expectedScrollY = (screenHeight * 0.52f).toInt()

        val firstCleanHeight = screenHeight - bottomExclude
        var accumulatedBitmap = android.graphics.Bitmap.createBitmap(list[0], 0, 0, width, firstCleanHeight)

        for (i in 1 until list.size) {
            val currentBmp = list[i]

            // 1. 在旧图底部有效区域内采样特征带（高度 60px）
            val templateH = 60
            var anchorY = accumulatedBitmap.height - templateH - 10
            
            // 向上寻找具有足够颜色对比度（文字笔画或图片轮廓）的特征行
            var foundFeature = false
            while (anchorY > accumulatedBitmap.height - 240 && anchorY > 100) {
                val testLine = IntArray(width)
                accumulatedBitmap.getPixels(testLine, 0, width, 0, anchorY + templateH / 2, width, 1)
                
                var variation = 0
                for (x in 0 until width - 1 step 4) {
                    val p1 = testLine[x]
                    val p2 = testLine[x + 1]
                    val diff = Math.abs(((p1 shr 16) and 0xff) - ((p2 shr 16) and 0xff)) +
                               Math.abs(((p1 shr 8) and 0xff) - ((p2 shr 8) and 0xff)) +
                               Math.abs((p1 and 0xff) - (p2 and 0xff))
                    if (diff > 18) variation++
                }
                
                if (variation > 25) {
                    foundFeature = true
                    break
                }
                anchorY -= 10
            }
            if (!foundFeature) {
                anchorY = accumulatedBitmap.height - templateH - 10
            }

            val templatePixels = IntArray(width * templateH)
            accumulatedBitmap.getPixels(templatePixels, 0, width, 0, anchorY, width, templateH)

            // 2. 【核心修复】：基于物理滑动的受限搜索窗口
            // 上一屏底部 anchorY 在新一屏中的理论位置是：anchorY - (上一屏高度 - screenHeight + bottomExclude + expectedScrollY)
            // 即在新一屏大约 (screenHeight - bottomExclude - expectedScrollY) 附近！
            // 绝不允许漫无目的地搜全屏，必须限制在合理物理浮动范围 ±300px 内！
            val baseExpectedY = (screenHeight - bottomExclude - expectedScrollY).coerceAtLeast(topExclude + 50)
            val searchStartY = (baseExpectedY - 260).coerceAtLeast(topExclude)
            val searchEndY = (baseExpectedY + 260).coerceAtMost(screenHeight - bottomExclude - templateH)

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
                    if (diff > minDiff) break
                }

                if (diff < minDiff) {
                    minDiff = diff
                    bestMatchY = candidateY
                }
            }

            // 3. 安全缝合：
            // 如果在物理窗口内找到了极佳吻合点，直接在锚点无缝拼接；
            // 如果出现异常大白屏未找到，使用物理预期滑动距离保底拼接，绝不漏掉文字！
            val validOldHeight: Int
            val appendStartY: Int

            if (bestMatchY >= 0 && minDiff < (width * templateH * 25L)) {
                // 精准对齐
                validOldHeight = anchorY + templateH
                appendStartY = bestMatchY + templateH
            } else {
                // 保底安全位移：截取滚动带来的全部新增内容
                validOldHeight = accumulatedBitmap.height
                appendStartY = (screenHeight - bottomExclude - expectedScrollY).coerceAtLeast(topExclude)
            }

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
