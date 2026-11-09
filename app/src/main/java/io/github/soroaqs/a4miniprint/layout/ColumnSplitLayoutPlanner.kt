package io.github.soroaqs.a4miniprint.layout

import android.graphics.RectF
import io.github.soroaqs.a4miniprint.config.PrintConfig
import io.github.soroaqs.a4miniprint.data.PhotoFitMode
import io.github.soroaqs.a4miniprint.data.PhotoMetadata
import io.github.soroaqs.a4miniprint.data.PhotoResolutionMode
import io.github.soroaqs.a4miniprint.data.PhotoSlot
import io.github.soroaqs.a4miniprint.data.PrintPage

/**
 * 把一张横向多栏图拆成多张 A4 竖版页面。
 *
 * 第一版不自动识别栏边界，只使用用户提供的等分或微调后的切分线。
 */
class ColumnSplitLayoutPlanner(private val config: PrintConfig) {

    fun plan(
        photo: PhotoMetadata,
        columnCount: Int,
        splitPositions: List<Int>,
        fitMode: PhotoFitMode,
        resolutionMode: PhotoResolutionMode
    ): List<PrintPage> {
        require(columnCount in 2..4) {
            "分栏拆印目前仅支持 2、3、4 栏"
        }
        require(splitPositions.size == columnCount - 1) {
            "切分线数量必须等于栏数减一"
        }

        val positions = splitPositions
            .map { it.coerceIn(1, 999) }
            .distinct()
            .sorted()

        require(positions.size == columnCount - 1) {
            "切分线不能重合"
        }

        val bounds = listOf(0) + positions + 1000

        return bounds
            .zipWithNext()
            .map { (startPerMille, endPerMille) ->
                PrintPage(
                    isPortrait = true,
                    slots = listOf(
                        PhotoSlot(
                            photo = photo,
                            rectMm = RectF(
                                0f,
                                0f,
                                config.pageWidthMm,
                                config.pageHeightMm
                            ),
                            isPortrait = true,
                            fitMode = fitMode,
                            resolutionMode = resolutionMode,
                            sourceCropRect = RectF(
                                startPerMille / 1000f,
                                0f,
                                endPerMille / 1000f,
                                1f
                            )
                        )
                    )
                )
            }
    }
}
