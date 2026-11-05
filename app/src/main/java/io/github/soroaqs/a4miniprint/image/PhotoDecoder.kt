package io.github.soroaqs.a4miniprint.image

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import io.github.soroaqs.a4miniprint.config.PrintConfig
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
        val targetWidthPx = mmToPx(targetWidthMm, config.targetDpi)
        val targetHeightPx = mmToPx(targetHeightMm, config.targetDpi)

        val sampleSize = when (slot.resolutionMode) {
            PhotoResolutionMode.PRINT_300_DPI -> calculateSampleSize(
                sourceWidth = slot.photo.orientedWidth,
                sourceHeight = slot.photo.orientedHeight,
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

        val oriented = rotateToSlotOrientation(exifOriented, slot.isPortrait)
        if (oriented !== exifOriented) {
            exifOriented.recycle()
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

    private fun mmToPx(valueMm: Float, dpi: Int): Int {
        return (valueMm / 25.4f * dpi).roundToInt()
    }
}
