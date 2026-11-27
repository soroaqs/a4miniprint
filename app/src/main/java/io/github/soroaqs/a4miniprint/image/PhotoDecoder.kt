package io.github.soroaqs.a4miniprint.image

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import io.github.soroaqs.a4miniprint.config.PrintConfig
import io.github.soroaqs.a4miniprint.data.PhotoFitMode
import io.github.soroaqs.a4miniprint.data.PhotoResolutionMode
import io.github.soroaqs.a4miniprint.data.PhotoSlot
import io.github.soroaqs.a4miniprint.data.PhotoSourceType
import androidx.exifinterface.media.ExifInterface
import java.io.Closeable
import kotlin.math.roundToInt

/**
 * 按目标格子大小采样解码，并在内存中修正 EXIF 方向。
 *
 * 采样后的解码尺寸仍不低于目标输出尺寸，避免把远超打印需求的原图整张嵌入 PDF。
 * PDF 页按需要裁剪的区域直接栅格化到 A4 目标像素，不先渲染整页再裁剪。
 */
class PhotoDecoder(
    private val contentResolver: ContentResolver,
    private val config: PrintConfig
) : Closeable {

    private var pdfHandle: PdfDocumentHandle? = null
    private var pdfUri: Uri? = null

    fun decode(slot: PhotoSlot): Bitmap {
        return when (slot.photo.sourceType) {
            PhotoSourceType.IMAGE -> decodeImage(slot)
            PhotoSourceType.PDF_PAGE -> decodePdfPage(slot)
        }
    }

    override fun close() {
        pdfHandle?.close()
        pdfHandle = null
        pdfUri = null
    }

    private fun decodeImage(slot: PhotoSlot): Bitmap {
        val targetWidthMm = slot.rectMm.width()
        val targetHeightMm = slot.rectMm.height()
        val cropRect = slot.sourceCropRect
        val sourceWidth = cropRect?.let { rect ->
            (slot.photo.orientedWidth * rect.width()).roundToInt()
        } ?: slot.photo.orientedWidth
        val sourceHeight = cropRect?.let { rect ->
            (slot.photo.orientedHeight * rect.height()).roundToInt()
        } ?: slot.photo.orientedHeight

        val targetWidthPx: Int
        val targetHeightPx: Int
        when (slot.fitMode) {
            PhotoFitMode.CONTAIN -> {
                val sourceAspect = sourceWidth.toFloat() / sourceHeight.toFloat()
                val slotAspect = targetWidthMm / targetHeightMm
                if (sourceAspect > slotAspect) {
                    targetWidthPx = mmToPx(targetWidthMm, config.targetDpi)
                    targetHeightPx = (targetWidthPx / sourceAspect).roundToInt()
                } else {
                    targetHeightPx = mmToPx(targetHeightMm, config.targetDpi)
                    targetWidthPx = (targetHeightPx * sourceAspect).roundToInt()
                }
            }
            PhotoFitMode.COVER,
            PhotoFitMode.FILL -> {
                targetWidthPx = mmToPx(targetWidthMm, config.targetDpi)
                targetHeightPx = mmToPx(targetHeightMm, config.targetDpi)
            }
        }

        val sampleSize = when (slot.resolutionMode) {
            PhotoResolutionMode.PRINT_300_DPI -> calculateSampleSize(
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight,
                targetWidth = targetWidthPx,
                targetHeight = targetHeightPx
            )
            PhotoResolutionMode.ORIGINAL -> 1
        }

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }

        val decoded = contentResolver.openInputStream(slot.photo.uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, options)
        } ?: error("无法读取图片：${slot.photo.uri}")

        val exifOriented = applyOrientation(decoded, slot.photo.exifOrientation)
        if (exifOriented !== decoded) {
            decoded.recycle()
        }

        val cropped = cropToSourceRect(exifOriented, cropRect)
        if (cropped !== exifOriented) {
            exifOriented.recycle()
        }

        val oriented = rotateToSlotOrientation(cropped, slot.isPortrait)
        // rotated 只能回收 cropped；不能与 exifOriented 比较，
        // 否则 cropped 未发生旋转但已经是裁剪结果时，会把返回对象错误回收。
        if (oriented !== cropped) {
            cropped.recycle()
        }

        return oriented
    }

    /**
     * 把 PDF 页的裁切区域直接栅格化到目标像素。
     *
     * 只渲染需要的栏，不先渲染整张横向页：A3@300DPI 整页约 70MB，
     * 而单栏 A4@300DPI 约 35MB。
     */
    private fun decodePdfPage(slot: PhotoSlot): Bitmap {
        val photo = slot.photo
        val renderer = pdfRenderer(photo.uri)
        require(photo.pdfPageIndex in 0 until renderer.pageCount) {
            "PDF 页码超出范围：${photo.pdfPageIndex}"
        }

        val page = renderer.openPage(photo.pdfPageIndex)
        try {
            val pageWidth = page.width.toFloat()
            val pageHeight = page.height.toFloat()
            val cropRect = slot.sourceCropRect ?: RectF(0f, 0f, 1f, 1f)
            val cropWidth = pageWidth * cropRect.width()
            val cropHeight = pageHeight * cropRect.height()
            require(cropWidth > 0f && cropHeight > 0f) {
                "PDF 页面尺寸无效"
            }

            val targetWidthPx: Int
            val targetHeightPx: Int
            when (slot.fitMode) {
                PhotoFitMode.CONTAIN -> {
                    val sourceAspect = cropWidth / cropHeight
                    val slotAspect = slot.rectMm.width() / slot.rectMm.height()
                    if (sourceAspect > slotAspect) {
                        targetWidthPx = mmToPx(slot.rectMm.width(), config.targetDpi)
                        targetHeightPx = (targetWidthPx / sourceAspect).roundToInt()
                    } else {
                        targetHeightPx = mmToPx(slot.rectMm.height(), config.targetDpi)
                        targetWidthPx = (targetHeightPx * sourceAspect).roundToInt()
                    }
                }
                PhotoFitMode.COVER,
                PhotoFitMode.FILL -> {
                    targetWidthPx = mmToPx(slot.rectMm.width(), config.targetDpi)
                    targetHeightPx = mmToPx(slot.rectMm.height(), config.targetDpi)
                }
            }
            require(targetWidthPx > 0 && targetHeightPx > 0) {
                "PDF 页面的目标尺寸无效"
            }

            val bitmap = Bitmap.createBitmap(
                targetWidthPx,
                targetHeightPx,
                Bitmap.Config.ARGB_8888
            )
            // PDF 页没有内容的位置会保持初始值，先铺白避免透明/黑底。
            bitmap.eraseColor(Color.WHITE)

            // 把裁切区域的左上角移到原点，再缩放到目标像素。
            val scaleX = targetWidthPx / cropWidth
            val scaleY = targetHeightPx / cropHeight
            val matrix = Matrix().apply {
                setScale(scaleX, scaleY)
                postTranslate(
                    -cropRect.left * pageWidth * scaleX,
                    -cropRect.top * pageHeight * scaleY
                )
            }
            page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
            return bitmap
        } finally {
            page.close()
        }
    }

    /**
     * 一个解码器只服务一次打印写入；同一任务内复用 renderer，
     * 避免每个输出页都重新打开 PDF。
     */
    private fun pdfRenderer(uri: Uri): PdfRenderer {
        val current = pdfHandle
        if (current != null && pdfUri == uri) {
            return current.renderer
        }

        current?.close()
        val handle = PdfDocumentHandle.open(contentResolver, uri)
        pdfHandle = handle
        pdfUri = uri
        return handle.renderer
    }

    private fun calculateSampleSize(
        sourceWidth: Int,
        sourceHeight: Int,
        targetWidth: Int,
        targetHeight: Int
    ): Int {
        var sampleSize = 1

        while (
            sourceWidth / (sampleSize * 2) >= targetWidth &&
            sourceHeight / (sampleSize * 2) >= targetHeight
        ) {
            sampleSize *= 2
        }

        return sampleSize
    }

    private fun applyOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()

        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            else -> return bitmap
        }

        return Bitmap.createBitmap(
            bitmap,
            0,
            0,
            bitmap.width,
            bitmap.height,
            matrix,
            true
        )
    }

    private fun rotateToSlotOrientation(bitmap: Bitmap, isSlotPortrait: Boolean): Bitmap {
        if (!isSlotPortrait || bitmap.width <= bitmap.height) {
            return bitmap
        }

        val matrix = Matrix().apply {
            postRotate(90f)
        }

        return Bitmap.createBitmap(
            bitmap,
            0,
            0,
            bitmap.width,
            bitmap.height,
            matrix,
            true
        )
    }

    /**
     * 按归一化区域裁剪源图。
     *
     * 分栏拆印先在已修正 EXIF 方向的图上裁出左右栏，再进入统一的 A4 渲染流程。
     */
    private fun cropToSourceRect(bitmap: Bitmap, cropRect: RectF?): Bitmap {
        if (cropRect == null) {
            return bitmap
        }

        val left = (bitmap.width * cropRect.left).roundToInt().coerceIn(0, bitmap.width)
        val top = (bitmap.height * cropRect.top).roundToInt().coerceIn(0, bitmap.height)
        val right = (bitmap.width * cropRect.right).roundToInt().coerceIn(left, bitmap.width)
        val bottom = (bitmap.height * cropRect.bottom).roundToInt().coerceIn(top, bitmap.height)

        if (left == 0 && top == 0 && right == bitmap.width && bottom == bitmap.height) {
            return bitmap
        }

        return Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
    }

    private fun mmToPx(valueMm: Float, dpi: Int): Int {
        return (valueMm / 25.4f * dpi).roundToInt()
    }
}
