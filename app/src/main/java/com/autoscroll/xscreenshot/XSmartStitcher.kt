package com.autoscroll.xscreenshot

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

object XSmartStitcher {

    data class StitchConfig(
        val topExclusionPercent: Float = 0.08f,    // 顶部状态栏及 X 工具栏 (~8%)
        val bottomExclusionPercent: Float = 0.12f, // X 帖子底部固定回复栏 (~12%)
        val searchBandRatio: Float = 0.40f         // 最大重叠搜索范围
    )

    /**
     * 将两张连续的屏幕截图无缝拼合
     * 关键逻辑：Frame1 底部有一块静止的回复栏，计算相似度时必须完全剔除该区域！
     */
    suspend fun stitchFrames(
        frames: List<Bitmap>,
        config: StitchConfig = StitchConfig()
    ): Bitmap = withContext(Dispatchers.Default) {
        if (frames.isEmpty()) throw IllegalArgumentException("帧列表不能为空")
        if (frames.size == 1) return@withContext frames[0]

        val width = frames[0].width
        val height = frames[0].height

        val topCutPx = (height * config.topExclusionPercent).toInt()
        val bottomCutPx = (height * config.bottomExclusionPercent).toInt()
        val validBottom1 = height - bottomCutPx

        // 记录拼接偏移位置
        val cutOffsets = mutableListOf<Int>()
        cutOffsets.add(0)

        for (i in 1 until frames.size) {
            val img1 = frames[i - 1]
            val img2 = frames[i]

            val offset = findBestOverlapOffset(
                img1,
                img2,
                topCutPx,
                validBottom1,
                bottomCutPx,
                config.searchBandRatio
            )
            cutOffsets.add(offset)
        }

        // 计算拼合后的总高度
        var totalHeight = validBottom1
        for (i in 1 until frames.size) {
            val offset = cutOffsets[i]
            val sliceHeight = validBottom1 - offset
            if (sliceHeight > 0) {
                totalHeight += sliceHeight
            }
        }

        // 最终可附带完整的最后一张底部或剔除回复栏
        val resultBitmap = Bitmap.createBitmap(width, totalHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(resultBitmap)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)

        // 绘制第一张
        val srcRect1 = Rect(0, 0, width, validBottom1)
        val destRect1 = Rect(0, 0, width, validBottom1)
        canvas.drawBitmap(frames[0], srcRect1, destRect1, paint)

        var currentDestY = validBottom1

        // 绘制后续切片
        for (i in 1 until frames.size) {
            val offset = cutOffsets[i]
            val sliceHeight = validBottom1 - offset
            if (sliceHeight > 0) {
                val srcRect = Rect(0, offset, width, validBottom1)
                val destRect = Rect(0, currentDestY, width, currentDestY + sliceHeight)
                canvas.drawBitmap(frames[i], srcRect, destRect, paint)
                currentDestY += sliceHeight
            }
        }

        return@withContext resultBitmap
    }

    private fun findBestOverlapOffset(
        img1: Bitmap,
        img2: Bitmap,
        topCut: Int,
        validBottom1: Int,
        bottomCut: Int,
        searchRatio: Float
    ): Int {
        val width = img1.width
        val height = img1.height

        // 从 img1 有效区域底部取出一行特征条带 (40px 高度)
        val templateHeight = 40.coerceAtMost((validBottom1 - topCut) / 4)
        val templateY = validBottom1 - templateHeight

        val maxSearchY = ((height * searchRatio).toInt())
            .coerceAtMost(height - bottomCut - templateHeight)

        var bestY = topCut
        var minDiff = Long.MAX_VALUE

        // 降采样加快比对速度 (横向每隔 4 像素采样一次)
        val stepX = 4

        for (testY in topCut..maxSearchY) {
            var diff = 0L
            for (row in 0 until templateHeight step 2) {
                for (col in 0 until width step stepX) {
                    val p1 = img1.getPixel(col, templateY + row)
                    val p2 = img2.getPixel(col, testY + row)

                    val rDiff = abs((p1 shr 16 and 0xff) - (p2 shr 16 and 0xff))
                    val gDiff = abs((p1 shr 8 and 0xff) - (p2 shr 8 and 0xff))
                    val bDiff = abs((p1 and 0xff) - (p2 and 0xff))
                    diff += (rDiff + gDiff + bDiff)
                }
            }

            if (diff < minDiff) {
                minDiff = diff
                bestY = testY
            }
        }

        return bestY + templateHeight
    }
}
