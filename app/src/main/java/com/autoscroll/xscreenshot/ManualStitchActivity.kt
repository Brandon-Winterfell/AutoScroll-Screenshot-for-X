package com.autoscroll.xscreenshot

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*

/**
 * 手动批量导入连续截屏拼长图页面：
 * 复用与自动滚截完全相同的 XSmartStitcher 核心算法与 ImageSaver 保存类。
 */
class ManualStitchActivity : AppCompatActivity() {

    private data class SelectedFrame(
        val uri: Uri,
        val displayName: String,
        val thumbnail: Bitmap?
    )

    private val selectedFrames = mutableListOf<SelectedFrame>()
    private var stitchedPreviewBitmap: Bitmap? = null
    private var previewDisplayBitmap: Bitmap? = null
    private val uiScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var topExclusionPercent = 10
    private var bottomExclusionPercent = 12

    private lateinit var tvCountSummary: TextView
    private lateinit var listContainer: LinearLayout
    private lateinit var tvTopCropLabel: TextView
    private lateinit var tvBottomCropLabel: TextView
    private lateinit var seekTopCrop: SeekBar
    private lateinit var seekBottomCrop: SeekBar
    private lateinit var btnStitch: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var tvResultInfo: TextView
    private lateinit var ivResultPreview: ImageView
    private lateinit var btnSaveAgain: Button

    private val pickImagesLauncher = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        if (uris.isEmpty()) return@registerForActivityResult
        loadSelectedUris(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.apply {
            title = "批量导入截屏拼长图"
            setDisplayHomeAsUpEnabled(true)
        }

        buildUi()
        renderFrameList()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun buildUi() {
        val rootScroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#09090B"))
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 32, 36, 48)
        }
        rootScroll.addView(root)

        // 顶部说明卡片
        val headerCard = createCard().apply {
            addView(TextView(this@ManualStitchActivity).apply {
                text = "手动多图智能拼接"
                textSize = 18f
                setTextColor(Color.WHITE)
                setTypeface(null, android.graphics.Typeface.BOLD)
            })
            addView(TextView(this@ManualStitchActivity).apply {
                text = "选择多张连续手动截屏，系统将自动剔除顶部状态栏与 X 底部固定回复栏，并使用与自动滚截完全相同的像素级无缝算法拼接长图。"
                textSize = 13f
                setTextColor(Color.parseColor("#A1A1AA"))
                setPadding(0, 10, 0, 20)
            })

            val btnRow = LinearLayout(this@ManualStitchActivity).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            val btnPick = createButton("批量选择截屏", "#1D9BF0").apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = 12
                }
                setOnClickListener { pickImagesLauncher.launch("image/*") }
            }
            val btnSortByName = createButton("按名称正序", "#27272A").apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = 12 }
                setOnClickListener {
                    selectedFrames.sortBy { it.displayName }
                    renderFrameList()
                    Toast.makeText(this@ManualStitchActivity, "已按文件名正序排列", Toast.LENGTH_SHORT).show()
                }
            }
            val btnClear = createButton("清空", "#3F3F46").apply {
                setOnClickListener {
                    clearSelectedThumbnails()
                    renderFrameList()
                }
            }
            btnRow.addView(btnPick)
            btnRow.addView(btnSortByName)
            btnRow.addView(btnClear)
            addView(btnRow)
        }
        root.addView(headerCard, cardMargin())

        // 参数设置卡片
        val configCard = createCard().apply {
            addView(TextView(this@ManualStitchActivity).apply {
                text = "裁切避让配置"
                textSize = 15f
                setTextColor(Color.WHITE)
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(0, 0, 0, 12)
            })

            val presetRow = LinearLayout(this@ManualStitchActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 0, 0, 16)
            }
            val btnPresetX = createButton("X 帖子模式 (顶10%/底12%)", "#1E3A5F").apply {
                textSize = 12f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = 10
                }
                setOnClickListener {
                    seekTopCrop.progress = 10
                    seekBottomCrop.progress = 12
                }
            }
            val btnPresetGeneral = createButton("通用无底栏 (顶6%/底3%)", "#27272A").apply {
                textSize = 12f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener {
                    seekTopCrop.progress = 6
                    seekBottomCrop.progress = 3
                }
            }
            presetRow.addView(btnPresetX)
            presetRow.addView(btnPresetGeneral)
            addView(presetRow)

            tvTopCropLabel = TextView(this@ManualStitchActivity).apply {
                text = "顶部状态栏/标题栏避让: ${topExclusionPercent}%"
                textSize = 13f
                setTextColor(Color.parseColor("#D4D4D8"))
            }
            seekTopCrop = SeekBar(this@ManualStitchActivity).apply {
                max = 25
                progress = topExclusionPercent
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                        topExclusionPercent = p
                        tvTopCropLabel.text = "顶部状态栏/标题栏避让: ${p}%"
                    }
                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) {}
                })
            }

            tvBottomCropLabel = TextView(this@ManualStitchActivity).apply {
                text = "底部固定回复栏避让: ${bottomExclusionPercent}%"
                textSize = 13f
                setTextColor(Color.parseColor("#D4D4D8"))
                setPadding(0, 12, 0, 0)
            }
            seekBottomCrop = SeekBar(this@ManualStitchActivity).apply {
                max = 30
                progress = bottomExclusionPercent
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                        bottomExclusionPercent = p
                        tvBottomCropLabel.text = "底部固定回复栏避让: ${p}%"
                    }
                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) {}
                })
            }

            addView(tvTopCropLabel)
            addView(seekTopCrop)
            addView(tvBottomCropLabel)
            addView(seekBottomCrop)
        }
        root.addView(configCard, cardMargin())

        // 已选图片排序列表卡片
        val listCard = createCard().apply {
            tvCountSummary = TextView(this@ManualStitchActivity).apply {
                text = "待拼接截屏队列 (0 张)"
                textSize = 15f
                setTextColor(Color.WHITE)
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(0, 0, 0, 12)
            }
            addView(tvCountSummary)

            listContainer = LinearLayout(this@ManualStitchActivity).apply {
                orientation = LinearLayout.VERTICAL
            }
            addView(listContainer)
        }
        root.addView(listCard, cardMargin())

        // 拼接操作与进度区
        btnStitch = createButton("开始智能拼接并保存长图", "#00BA7C").apply {
            textSize = 15f
            setPadding(0, 24, 0, 24)
            setOnClickListener { startManualStitch() }
        }
        root.addView(btnStitch, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = 20 })

        progressBar = ProgressBar(this).apply {
            visibility = View.GONE
        }
        root.addView(progressBar)

        // 拼接结果预览卡片
        val resultCard = createCard().apply {
            tvResultInfo = TextView(this@ManualStitchActivity).apply {
                text = "长图预览区（拼接完成后将自动保存至相册）"
                textSize = 13f
                setTextColor(Color.parseColor("#A1A1AA"))
                setPadding(0, 0, 0, 14)
            }
            addView(tvResultInfo)

            btnSaveAgain = createButton("重新保存至相册", "#1D9BF0").apply {
                visibility = View.GONE
                setOnClickListener {
                    val bmp = stitchedPreviewBitmap ?: return@setOnClickListener
                    val uri = ImageSaver.saveToGallery(this@ManualStitchActivity, bmp, "X_ManualStitch")
                    Toast.makeText(
                        this@ManualStitchActivity,
                        if (uri != null) "已保存至相册 Pictures/XScreenshots" else "保存失败",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            addView(btnSaveAgain, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 16 })

            ivResultPreview = ImageView(this@ManualStitchActivity).apply {
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            addView(ivResultPreview, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }
        root.addView(resultCard, cardMargin())

        setContentView(rootScroll)
    }

    private fun loadSelectedUris(uris: List<Uri>) {
        uiScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                uris.map { uri ->
                    val name = queryDisplayName(uri)
                    val thumb = decodeThumbnail(uri, 160)
                    SelectedFrame(uri, name, thumb)
                }
            }
            selectedFrames.addAll(loaded)
            renderFrameList()
        }
    }

    private fun renderFrameList() {
        tvCountSummary.text = "待拼接截屏队列 (${selectedFrames.size} 张) — 请确保从上到下顺序"
        btnStitch.isEnabled = selectedFrames.size >= 2
        listContainer.removeAllViews()

        if (selectedFrames.isEmpty()) {
            listContainer.addView(TextView(this).apply {
                text = "尚未导入截屏，请点击上方“批量选择截屏”按顺序多选 2 张以上连续截屏。"
                textSize = 13f
                setTextColor(Color.parseColor("#71717A"))
                setPadding(0, 12, 0, 12)
            })
            return
        }

        selectedFrames.forEachIndexed { index, frame ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(16, 12, 16, 12)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#18181B"))
                    cornerRadius = 18f
                    setStroke(1, Color.parseColor("#27272A"))
                }
            }

            val ivThumb = ImageView(this).apply {
                setImageBitmap(frame.thumbnail)
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(Color.BLACK)
            }
            row.addView(ivThumb, LinearLayout.LayoutParams(90, 140).apply { marginEnd = 16 })

            val infoCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            infoCol.addView(TextView(this).apply {
                text = "第 #${index + 1} 屏"
                textSize = 14f
                setTextColor(Color.parseColor("#38BDF8"))
                setTypeface(null, android.graphics.Typeface.BOLD)
            })
            infoCol.addView(TextView(this).apply {
                text = frame.displayName
                textSize = 12f
                setTextColor(Color.parseColor("#D4D4D8"))
                maxLines = 2
            })
            row.addView(infoCol)

            val btnUp = createSmallAction("↑") {
                if (index > 0) {
                    val tmp = selectedFrames[index - 1]
                    selectedFrames[index - 1] = selectedFrames[index]
                    selectedFrames[index] = tmp
                    renderFrameList()
                }
            }
            val btnDown = createSmallAction("↓") {
                if (index < selectedFrames.size - 1) {
                    val tmp = selectedFrames[index + 1]
                    selectedFrames[index + 1] = selectedFrames[index]
                    selectedFrames[index] = tmp
                    renderFrameList()
                }
            }
            val btnRemove = createSmallAction("✕") {
                frame.thumbnail?.recycle()
                selectedFrames.removeAt(index)
                renderFrameList()
            }

            row.addView(btnUp)
            row.addView(btnDown)
            row.addView(btnRemove)

            listContainer.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 12 })
        }
    }

    private fun startManualStitch() {
        if (selectedFrames.size < 2) {
            Toast.makeText(this, "请至少选择 2 张连续截屏", Toast.LENGTH_SHORT).show()
            return
        }

        btnStitch.isEnabled = false
        progressBar.visibility = View.VISIBLE
        tvResultInfo.text = "正在使用 XSmartStitcher 动态裁切拼接 ${selectedFrames.size} 张截图..."

        val framesCopy = selectedFrames.toList()
        val config = XSmartStitcher.StitchConfig(
            topExclusionPercent = topExclusionPercent / 100f,
            bottomExclusionPercent = bottomExclusionPercent / 100f
        )

        uiScope.launch {
            val resultPair = withContext(Dispatchers.Default) {
                val decodedBitmaps = mutableListOf<Bitmap>()
                try {
                    for (item in framesCopy) {
                        val bmp = decodeFullBitmap(item.uri)
                        if (bmp != null) decodedBitmaps.add(bmp)
                    }
                    if (decodedBitmaps.size < 2) {
                        return@withContext Pair<Bitmap?, Uri?>(null, null)
                    }

                    // 复用完全相同的独立拼接算法类 XSmartStitcher
                    val stitcher = XSmartStitcher(config)
                    val merged = stitcher.stitch(decodedBitmaps)
                    val savedUri = ImageSaver.saveToGallery(
                        this@ManualStitchActivity,
                        merged,
                        "X_ManualStitch"
                    )
                    Pair(merged, savedUri)
                } finally {
                    for (b in decodedBitmaps) {
                        if (!b.isRecycled) b.recycle()
                    }
                }
            }

            progressBar.visibility = View.GONE
            btnStitch.isEnabled = true

            val resultBmp = resultPair.first
            val savedUri = resultPair.second
            if (resultBmp != null) {
                ivResultPreview.setImageDrawable(null)
                if (previewDisplayBitmap != null && previewDisplayBitmap !== stitchedPreviewBitmap) {
                    previewDisplayBitmap?.recycle()
                }
                stitchedPreviewBitmap?.recycle()
                stitchedPreviewBitmap = resultBmp

                // 防止超长图 (>4096px) 触发 Android ImageView GPU 纹理尺寸上限导致不显示
                val displayBmp = if (resultBmp.height > 4096) {
                    val scale = 4096f / resultBmp.height
                    val previewW = (resultBmp.width * scale).toInt().coerceAtLeast(1)
                    Bitmap.createScaledBitmap(resultBmp, previewW, 4096, true)
                } else {
                    resultBmp
                }
                previewDisplayBitmap = displayBmp
                ivResultPreview.setImageBitmap(displayBmp)
                btnSaveAgain.visibility = View.VISIBLE
                tvResultInfo.text = "拼接完成！原图尺寸: ${resultBmp.width} × ${resultBmp.height} px" +
                    if (savedUri != null) " · 已自动保存至相册" else " · 请点击下方保存"
                Toast.makeText(
                    this@ManualStitchActivity,
                    "长图拼接成功！已保存至相册 Pictures/XScreenshots",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                tvResultInfo.text = "解码图片失败，请检查选中的图片是否有效"
                Toast.makeText(this@ManualStitchActivity, "拼接失败，请确认图片可读", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun queryDisplayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) return cursor.getString(idx) ?: "screenshot.png"
            }
        }
        return uri.lastPathSegment ?: "screenshot.png"
    }

    private fun decodeThumbnail(uri: Uri, targetWidth: Int): Bitmap? {
        return try {
            val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, boundsOpts) }
            var sample = 1
            while (boundsOpts.outWidth / sample > targetWidth * 2) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        } catch (e: Exception) {
            null
        }
    }

    private fun decodeFullBitmap(uri: Uri): Bitmap? {
        return try {
            val opts = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        } catch (e: Exception) {
            null
        }
    }

    private fun createCard(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 24, 28, 24)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#111114"))
                cornerRadius = 24f
                setStroke(2, Color.parseColor("#27272A"))
            }
        }
    }

    private fun cardMargin(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = 24 }
    }

    private fun createButton(label: String, hexColor: String): Button {
        return Button(this).apply {
            text = label
            textSize = 13f
            setTextColor(Color.WHITE)
            isAllCaps = false
            background = GradientDrawable().apply {
                setColor(Color.parseColor(hexColor))
                cornerRadius = 18f
            }
        }
    }

    private fun createSmallAction(symbol: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = symbol
            textSize = 15f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(18, 12, 18, 12)
            setOnClickListener { onClick() }
        }
    }

    private fun clearSelectedThumbnails() {
        for (item in selectedFrames) {
            item.thumbnail?.recycle()
        }
        selectedFrames.clear()
    }

    override fun onDestroy() {
        super.onDestroy()
        clearSelectedThumbnails()
        if (previewDisplayBitmap != null && previewDisplayBitmap !== stitchedPreviewBitmap) {
            previewDisplayBitmap?.recycle()
        }
        previewDisplayBitmap = null
        stitchedPreviewBitmap?.recycle()
        stitchedPreviewBitmap = null
        uiScope.cancel()
    }
}
