package io.github.soroaqs.a4miniprint.data

import android.graphics.RectF
import android.net.Uri

data class SizeMm(
    val widthMm: Float,
    val heightMm: Float
)

enum class PrintLayoutMode {
    A4_GRID,
    SINGLE_PER_PAGE
}

enum class PhotoFitMode {
    CONTAIN,
    COVER
}

enum class PhotoResolutionMode {
    PRINT_300_DPI,
    ORIGINAL
}

/**
 * 照片元数据只保留排版和裁切需要的信息，避免保存完整 Bitmap。
 */
data class PhotoMetadata(
    val uri: Uri,
    val rawWidth: Int,
    val rawHeight: Int,
    val exifOrientation: Int
) {
    val orientedWidth: Int
        get() = if (exifOrientation in 5..8) rawHeight else rawWidth

    val orientedHeight: Int
        get() = if (exifOrientation in 5..8) rawWidth else rawHeight
}

/**
 * 单个照片在页面上的落位。
 *
 * [rectMm] 使用毫米坐标，打印适配器再统一换算成 PDF point。
 */
data class PhotoSlot(
    val photo: PhotoMetadata,
    val rectMm: RectF,
    val isPortrait: Boolean,
    val fitMode: PhotoFitMode,
    val resolutionMode: PhotoResolutionMode
)

data class PrintPage(
    val isPortrait: Boolean,
    val slots: List<PhotoSlot>
)
