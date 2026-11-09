package io.github.soroaqs.a4miniprint.image

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.RectF
import io.github.soroaqs.a4miniprint.config.PrintConfig
import io.github.soroaqs.a4miniprint.data.PhotoFitMode
import io.github.soroaqs.a4miniprint.data.PhotoResolutionMode
import io.github.soroaqs.a4miniprint.data.PhotoSlot
import androidx.exifinterface.media.ExifInterface
import kotlin.math.roundToInt

/**
 * 按目标格子大小采样解码，并在内存中修正 EXIF 方向。
 *
 * 采样后的解码尺寸仍不低于目标输出尺寸，避免把远超打印需求的原图整张嵌入 PDF。
 */
class PhotoDecoder(
    private val contentResolver: ContentResolver,
    private val config: PrintConfig
) {

    fun decode(slot: PhotoSlot): Bitmap {
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
