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

/**
 * 顶层任务模式。
 *
 * [PHOTO_4X6] 保留现有的 4×6 照片排版；
 * [COLUMN_SPLIT] 用于把一张横向多栏图拆成多张 A4 竖版页面。
 */
enum class PrintTaskMode {
    PHOTO_4X6,
    COLUMN_SPLIT
}

enum class PhotoFitMode {
    CONTAIN,
    COVER,
    FILL
}

enum class PhotoResolutionMode {
    PRINT_300_DPI,
    ORIGINAL
}

/**
 * 输入来源类型。
 *
 * [IMAGE] 走 BitmapFactory 解码；[PDF_PAGE] 走 PdfRenderer 栅格化。
 */
enum class PhotoSourceType {
    IMAGE,
    PDF_PAGE
}

/**
 * 照片元数据只保留排版和裁切需要的信息，避免保存完整 Bitmap。
 *
 * PDF 页的 [rawWidth]、[rawHeight] 是 point（1/72 英寸），
 * 与图片的像素尺寸一样只用于计算宽高比和目标尺寸。
 */
data class PhotoMetadata(
    val uri: Uri,
    val rawWidth: Int,
    val rawHeight: Int,
    val exifOrientation: Int,
    val sourceType: PhotoSourceType = PhotoSourceType.IMAGE,
    val pdfPageIndex: Int = 0
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
    val resolutionMode: PhotoResolutionMode,
    /**
     * 可选的源图裁剪区域，坐标使用 0f..1f 的归一化比例。
     *
     * 分栏拆印会把一张原图切成多栏，每栏生成一张 A4 页面。
     */
    val sourceCropRect: RectF? = null
)

data class PrintPage(
    val isPortrait: Boolean,
    val slots: List<PhotoSlot>
)
