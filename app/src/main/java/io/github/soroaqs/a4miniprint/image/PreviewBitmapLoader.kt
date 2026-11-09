package io.github.soroaqs.a4miniprint.image

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import io.github.soroaqs.a4miniprint.config.PrintConfig
import io.github.soroaqs.a4miniprint.data.PhotoMetadata
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 为分栏切分线预览加载一张小图。
 *
 * 预览只用于辅助定位切分线，不参与最终打印输出；这里按最长边降采样，
 * 并在生成新 Bitmap 时及时回收中间对象，避免 UI 层保留大图内存。
 */
class PreviewBitmapLoader(
    private val contentResolver: ContentResolver,
    private val config: PrintConfig
) {

    fun load(photo: PhotoMetadata): Bitmap {
        val maxEdge = config.previewMaxEdgePx
        val sampleSize = calculateSampleSize(
            sourceWidth = photo.orientedWidth,
            sourceHeight = photo.orientedHeight,
            maxEdge = maxEdge
        )

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }

        val decoded = contentResolver.openInputStream(photo.uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, options)
        } ?: error("无法读取预览图片：${photo.uri}")

        val oriented = applyOrientation(decoded, photo.exifOrientation)
        if (oriented !== decoded) {
            decoded.recycle()
        }

        val currentMaxEdge = max(oriented.width, oriented.height)
        if (currentMaxEdge <= maxEdge) {
            return oriented
        }

        val scale = maxEdge.toFloat() / currentMaxEdge
        val preview = Bitmap.createScaledBitmap(
            oriented,
            (oriented.width * scale).roundToInt(),
            (oriented.height * scale).roundToInt(),
            true
        )
        if (preview !== oriented) {
            oriented.recycle()
        }
        return preview
    }

    private fun calculateSampleSize(
        sourceWidth: Int,
        sourceHeight: Int,
        maxEdge: Int
    ): Int {
        var sampleSize = 1
        val longestEdge = max(sourceWidth, sourceHeight)

        while (longestEdge / (sampleSize * 2) >= maxEdge) {
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
}
