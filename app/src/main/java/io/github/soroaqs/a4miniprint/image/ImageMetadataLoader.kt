package io.github.soroaqs.a4miniprint.image

import android.content.ContentResolver
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import io.github.soroaqs.a4miniprint.data.PhotoMetadata

/**
 * 负责读取图片尺寸和 EXIF 方向，不加载像素数据。
 */
class ImageMetadataLoader(private val contentResolver: ContentResolver) {

    fun load(uri: Uri): PhotoMetadata {
        val bounds = loadBounds(uri)
        val orientation = loadOrientation(uri)

        require(bounds.first > 0 && bounds.second > 0) {
            "无法识别图片尺寸：$uri"
        }

        return PhotoMetadata(
            uri = uri,
            rawWidth = bounds.first,
            rawHeight = bounds.second,
            exifOrientation = orientation
        )
    }

    private fun loadBounds(uri: Uri): Pair<Int, Int> {
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }

        contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, options)
        }

        return options.outWidth to options.outHeight
    }

    private fun loadOrientation(uri: Uri): Int {
        return try {
            contentResolver.openInputStream(uri)?.use { input ->
                ExifInterface(input).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_UNDEFINED
                )
            } ?: ExifInterface.ORIENTATION_UNDEFINED
        } catch (_: Exception) {
            ExifInterface.ORIENTATION_UNDEFINED
        }
    }
}
