package io.github.soroaqs.a4miniprint.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import io.github.soroaqs.a4miniprint.R
import kotlin.math.roundToInt

/**
 * 分栏拆印的轻量预览控件。
 *
 * 只绘制一张共享的预览 Bitmap 和当前切分线，不生成文件，也不持有第二份图片内存。
 * Bitmap 的生命周期由 MainActivity 统一管理，这里仅负责展示。
 */
class ColumnSplitPreviewView(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var previewBitmap: Bitmap? = null
    private var splitPositions: List<Int> = emptyList()
    private val bitmapPaint = Paint().apply {
        isAntiAlias = true
        isFilterBitmap = true
    }
    private val lineUnderlayPaint = Paint().apply {
        color = Color.argb(180, 255, 255, 255)
        strokeWidth = 2f * resources.displayMetrics.density
        style = Paint.Style.STROKE
    }
    private val linePaint = Paint().apply {
        color = Color.argb(230, 220, 38, 38)
        strokeWidth = 1f * resources.displayMetrics.density
        style = Paint.Style.STROKE
    }
    private val placeholderPaint = Paint().apply {
        color = Color.argb(160, 107, 114, 128)
        textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            14f,
            resources.displayMetrics
        )
        textAlign = Paint.Align.CENTER
    }
    private val placeholderText = context.getString(R.string.split_preview_placeholder)
    private val imageRect = RectF()

    fun update(bitmap: Bitmap?, positions: List<Int>) {
        previewBitmap = bitmap
        splitPositions = positions
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bitmap = previewBitmap

        if (bitmap == null || bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) {
            drawPlaceholder(canvas)
            return
        }

        calculateImageRect(bitmap)
        canvas.drawBitmap(
            bitmap,
            null,
            imageRect,
            bitmapPaint
        )
        drawSplitLines(canvas)
    }

    private fun drawPlaceholder(canvas: Canvas) {
        val baseline = (height + placeholderPaint.textSize) / 2f
        canvas.drawText(
            placeholderText,
            width / 2f,
            baseline,
            placeholderPaint
        )
    }

    private fun calculateImageRect(bitmap: Bitmap) {
        val horizontalScale = width.toFloat() / bitmap.width
        val verticalScale = height.toFloat() / bitmap.height
        val scale = minOf(horizontalScale, verticalScale)
        val drawWidth = bitmap.width * scale
        val drawHeight = bitmap.height * scale

        imageRect.set(
            (width - drawWidth) / 2f,
            (height - drawHeight) / 2f,
            (width + drawWidth) / 2f,
            (height + drawHeight) / 2f
        )
    }

    private fun drawSplitLines(canvas: Canvas) {
        splitPositions.forEach { position ->
            val x = imageRect.left + imageRect.width() * position / 1000f
                .roundToInt()
                .toFloat()

            canvas.drawLine(
                x,
                imageRect.top,
                x,
                imageRect.bottom,
                lineUnderlayPaint
            )
            canvas.drawLine(
                x,
                imageRect.top,
                x,
                imageRect.bottom,
                linePaint
            )
        }
    }
}
