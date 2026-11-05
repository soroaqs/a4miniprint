package io.github.soroaqs.a4miniprint.config

import io.github.soroaqs.a4miniprint.data.PrintLayoutMode
import io.github.soroaqs.a4miniprint.data.PhotoFitMode
import io.github.soroaqs.a4miniprint.data.PhotoResolutionMode
import io.github.soroaqs.a4miniprint.data.SizeMm

/**
 * 输出规格的可配置入口。
 *
 * 预留了打印尺寸枚举、页面尺寸、目标 DPI、网格行列数和默认排版模式，
 * 后续如果要支持 A6、5×7 或自定义尺寸，只需要扩展 [PrintPreset] 和 UI。
 */
data class PrintConfig(
    val preset: PrintPreset = PrintPreset.NEAR_4X6_ON_A4,
    val pageWidthMm: Float = 210f,
    val pageHeightMm: Float = 297f,
    val targetDpi: Int = 300,
    val gridColumns: Int = 2,
    val gridRows: Int = 2,
    val defaultLayoutMode: PrintLayoutMode = PrintLayoutMode.A4_GRID,
    val defaultFitMode: PhotoFitMode = PhotoFitMode.CONTAIN,
    val defaultResolutionMode: PhotoResolutionMode = PhotoResolutionMode.PRINT_300_DPI,
    /**
     * 实测 HP 打印机输出整体略偏右约 1mm；左移 0.5mm 可让左右留白恢复对称。
     */
    val horizontalPrintCalibrationMm: Float = -0.5f,
    val printJobName: String = "a4miniprint"
) {
    fun pageSize(isPortrait: Boolean): SizeMm {
        return if (isPortrait) {
            SizeMm(widthMm = pageWidthMm, heightMm = pageHeightMm)
        } else {
            SizeMm(widthMm = pageHeightMm, heightMm = pageWidthMm)
        }
    }

    fun slotSize(isPortrait: Boolean): SizeMm {
        return if (isPortrait) {
            preset.portraitSlot
        } else {
            preset.landscapeSlot
        }
    }
}

/**
 * 第一版只提供「A4 上的近似 4×6」。
 *
 * 尺寸不是严格 4×6 英寸，而是保持 2:3 比例后能放入 A4 四宫格的物理尺寸。
 */
enum class PrintPreset(
    val displayName: String,
    val portraitSlot: SizeMm,
    val landscapeSlot: SizeMm
) {
    NEAR_4X6_ON_A4(
        displayName = "4×6 近似（A4 排版）",
        portraitSlot = SizeMm(widthMm = 99f, heightMm = 148.5f),
        landscapeSlot = SizeMm(widthMm = 148.5f, heightMm = 99f)
    )
}
