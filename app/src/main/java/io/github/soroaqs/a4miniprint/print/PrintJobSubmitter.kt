package io.github.soroaqs.a4miniprint.print

import android.content.Context
import android.print.PrintAttributes
import android.print.PrintManager
import io.github.soroaqs.a4miniprint.config.PrintConfig
import io.github.soroaqs.a4miniprint.data.PrintPage

/**
 * 统一封装系统打印任务提交。
 *
 * A4 作为默认纸张规格；份数和彩色/黑白交给系统打印预览页。
 */
class PrintJobSubmitter(
    private val context: Context,
    private val config: PrintConfig
) {

    fun submit(pages: List<PrintPage>) {
        if (pages.isEmpty()) {
            return
        }

        val printManager = context.getSystemService(PrintManager::class.java)
        val adapter = PhotoPrintDocumentAdapter(
            // 使用 ApplicationContext，避免系统打印框架持有 Activity 造成后台驻留。
            context = context.applicationContext,
            config = config,
            pages = pages
        )
        val attributes = PrintAttributes.Builder()
            .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
            .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
            .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
            .build()

        printManager.print(
            config.printJobName,
            adapter,
            attributes
        )
    }
}
