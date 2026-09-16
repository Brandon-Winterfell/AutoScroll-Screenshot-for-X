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

                // 针对 X 与 Android 导航栏的黄金裁切高度
                // 顶部状态栏+标题栏约 220px，底部回复框+系统导航栏约 340px
                val topExclude = 220
                val bottomExclude = 340

                // 1. 首屏截图（截图时临时把悬浮球隐身，避免悬浮球被截进图里）
                delay(500)
                captureWithHiddenOverlay(captureService)?.let { capturedList.add(it) }

                var step = 1
                while (isCapturing && isActive) {
                    // 2. 模拟真实阻尼平滑滑动（滑动距离严格对应内容区域高度）
                    accessibility.performSafeScroll(
                        screenWidth = screenW,
                        screenHeight = screenH,
                        topExclusionPx = topExclude,
                        bottomExclusionPx = bottomExclude,
                        durationMs = 500L
                    ) { success ->
                        if (success) {
                            step++
                            statusText.text = "已录制 $step 屏..."
                        }
                    }

                    // 等待页面滚动完成并让 X 帖子的图片和文本静止加载
                    delay(1300)

                    if (!isCapturing || !isActive) break

                    // 3. 截取下一屏
                    captureWithHiddenOverlay(captureService)?.let {
                        capturedList.add(it)
                    }
                }

                // 4. 用户点击了停止保存，进行智能无缝去重拼接
                if (capturedList.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        statusText.text = "正在拼接长图..."
                    }

                    val finalLongBitmap = withContext(Dispatchers.Default) {
                        stitchSeamlessBitmaps(capturedList, topExclude, bottomExclude)
                    }

                    val savedUri = withContext(Dispatchers.IO) {
                        captureService.saveBitmapToGallery(applicationContext, finalLongBitmap)
                    }

                    withContext(Dispatchers.Main) {
                        if (savedUri != null) {
                            statusText.text = "已存入相册 ✓"
                            Toast.makeText(applicationContext, "🎉 长截图拼接完成，已保存至系统相册！", Toast.LENGTH_LONG).show()
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

    // 专业的流式消除接缝拼接算法
    private fun stitchSeamlessBitmaps(
        list: List<android.graphics.Bitmap>,
        topExclude: Int,
        bottomExclude: Int
    ): android.graphics.Bitmap {
        if (list.size == 1) {
            // 只有一屏：只切掉底部的手机导航栏与空白，保留完整内容
            val w = list[0].width
            val h = (list[0].height - bottomExclude).coerceAtLeast(200)
            return android.graphics.Bitmap.createBitmap(list[0], 0, 0, w, h)
        }

        val width = list[0].width
        val screenHeight = list[0].height

        // 真实滚动的有效内容区域高度
        val contentHeight = (screenHeight - topExclude - bottomExclude).coerceAtLeast(100)

        // 计算拼接后的总高度：首屏(切除底部) + 中间屏(切头切尾)
        val firstScreenCleanHeight = screenHeight - bottomExclude
        val totalHeight = firstScreenCleanHeight + (list.size - 1) * contentHeight

        val resultBitmap = android.graphics.Bitmap.createBitmap(width, totalHeight, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(resultBitmap)

        // 1. 绘制首屏：保留顶部状态栏和正文，底部固定回复栏被完全切掉！
        val firstSrc = android.graphics.Rect(0, 0, width, firstScreenCleanHeight)
        val firstDst = android.graphics.Rect(0, 0, width, firstScreenCleanHeight)
        canvas.drawBitmap(list[0], firstSrc, firstDst, null)

        var currentY = firstScreenCleanHeight

        // 2. 依次拼接后续屏：上方切除顶部固定栏，下方切除底部固定回复栏，只拼接纯动态内容
        for (i in 1 until list.size) {
            val src = android.graphics.Rect(0, topExclude, width, topExclude + contentHeight)
            val dst = android.graphics.Rect(0, currentY, width, currentY + contentHeight)
            canvas.drawBitmap(list[i], src, dst, null)
            currentY += contentHeight
        }

        return resultBitmap
    }

    // 智能垂直拼接 Bitmap 并避开 X 底部固定 Dock 栏
    private fun stitchBitmaps(
        list: List<android.graphics.Bitmap>,
        topExclude: Int,
        bottomExclude: Int
    ): android.graphics.Bitmap {
        if (list.size == 1) return list[0]

        val width = list[0].width
        val cropHeight = (list[0].height - topExclude - bottomExclude).coerceAtLeast(100)
        val totalHeight = list[0].height + (list.size - 1) * cropHeight

        val resultBitmap = android.graphics.Bitmap.createBitmap(width, totalHeight, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(resultBitmap)

        // 绘制第一张完整的全屏
        canvas.drawBitmap(list[0], 0f, 0f, null)
        var currentY = list[0].height.toFloat()

        // 后续每一张都裁掉头部和 X 底部固定栏后无缝拼在下方
        for (i in 1 until list.size) {
            val srcRect = android.graphics.Rect(0, topExclude, width, list[i].height - bottomExclude)
            val dstRect = android.graphics.Rect(0, currentY.toInt(), width, (currentY + cropHeight).toInt())
            canvas.drawBitmap(list[i], srcRect, dstRect, null)
            currentY += cropHeight
        }

        return resultBitmap
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
