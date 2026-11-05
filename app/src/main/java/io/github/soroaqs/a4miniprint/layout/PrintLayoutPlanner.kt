package io.github.soroaqs.a4miniprint.layout

import android.graphics.RectF
import io.github.soroaqs.a4miniprint.config.PrintConfig
import io.github.soroaqs.a4miniprint.data.PhotoFitMode
import io.github.soroaqs.a4miniprint.data.PhotoMetadata
import io.github.soroaqs.a4miniprint.data.PhotoResolutionMode
import io.github.soroaqs.a4miniprint.data.PhotoSlot
import io.github.soroaqs.a4miniprint.data.PrintLayoutMode
import io.github.soroaqs.a4miniprint.data.PrintPage

/**
 * 统一使用 A4 竖版页面计算照片落位。
 *
 * 这样混合选择横竖照片时，四宫格不会因为方向不同被拆成多页。
 * 横版照片由 PhotoDecoder 自动旋转成竖版后再填入格子。
 */
class PrintLayoutPlanner(private val config: PrintConfig) {

    fun plan(
        photos: List<PhotoMetadata>,
        mode: PrintLayoutMode,
        fitMode: PhotoFitMode,
        resolutionMode: PhotoResolutionMode
    ): List<PrintPage> {
        if (photos.isEmpty()) {
            return emptyList()
        }

        return when (mode) {
            PrintLayoutMode.A4_GRID -> planGridPages(photos, fitMode, resolutionMode)
            PrintLayoutMode.SINGLE_PER_PAGE -> planSinglePages(photos, fitMode, resolutionMode)
        }
    }

    private fun planGridPages(
        photos: List<PhotoMetadata>,
        fitMode: PhotoFitMode,
        resolutionMode: PhotoResolutionMode
    ): List<PrintPage> {
        return photos
            .chunked(config.gridColumns * config.gridRows)
            .map { chunk -> buildGridPage(chunk, fitMode, resolutionMode) }
    }

    private fun planSinglePages(
        photos: List<PhotoMetadata>,
        fitMode: PhotoFitMode,
        resolutionMode: PhotoResolutionMode
    ): List<PrintPage> {
        return photos.map { photo ->
            PrintPage(
                isPortrait = true,
                slots = listOf(
                    buildSlot(
                        photo = photo,
                        rowIndex = 0,
                        columnIndex = 0,
                        fitMode = fitMode,
                        resolutionMode = resolutionMode
                    )
                )
            )
        }
    }

    private fun buildGridPage(
        photos: List<PhotoMetadata>,
        fitMode: PhotoFitMode,
        resolutionMode: PhotoResolutionMode
    ): PrintPage {
        val slots = photos.mapIndexed { index, photo ->
            buildSlot(
                photo = photo,
                rowIndex = index / config.gridColumns,
                columnIndex = index % config.gridColumns,
                fitMode = fitMode,
                resolutionMode = resolutionMode
            )
        }

        return PrintPage(
            isPortrait = true,
            slots = slots
        )
    }

    private fun buildSlot(
        photo: PhotoMetadata,
        rowIndex: Int,
        columnIndex: Int,
        fitMode: PhotoFitMode,
        resolutionMode: PhotoResolutionMode
    ): PhotoSlot {
        val slotSize = config.slotSize(isPortrait = true)
        // 两侧和中间的空隙都要参与分配；否则右列会贴到页面边缘，整体视觉偏右。
        val horizontalGapMm =
            (config.pageWidthMm - slotSize.widthMm * config.gridColumns) /
                (config.gridColumns + 1)
        val verticalGapMm =
            (config.pageHeightMm - slotSize.heightMm * config.gridRows) /
                (config.gridRows + 1)

        val leftMm =
            horizontalGapMm + columnIndex * (slotSize.widthMm + horizontalGapMm) +
                config.horizontalPrintCalibrationMm
        val topMm =
            verticalGapMm + rowIndex * (slotSize.heightMm + verticalGapMm)

        return PhotoSlot(
            photo = photo,
            rectMm = RectF(
                leftMm,
                topMm,
                leftMm + slotSize.widthMm,
                topMm + slotSize.heightMm
            ),
            isPortrait = true,
            fitMode = fitMode,
            resolutionMode = resolutionMode
        )
    }
}
