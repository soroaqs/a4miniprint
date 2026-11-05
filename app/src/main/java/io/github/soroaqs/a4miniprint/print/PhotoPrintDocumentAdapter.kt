package io.github.soroaqs.a4miniprint.print

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import io.github.soroaqs.a4miniprint.config.PrintConfig
import io.github.soroaqs.a4miniprint.data.PhotoFitMode
import io.github.soroaqs.a4miniprint.data.PrintPage
import io.github.soroaqs.a4miniprint.image.PhotoDecoder
import java.io.FileOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/**
 * 把已排好的页面渲染成 PDF，并写入系统打印服务提供的输出描述符。
 *
 * 应用不自行创建文件；这里的输出目标是 Android 打印框架传入的
 * [ParcelFileDescriptor]，系统打印服务后续可能生成自己的临时 spool 文件。
 */
class PhotoPrintDocumentAdapter(
    private val context: Context,
    private val config: PrintConfig,
    private val pages: List<PrintPage>
) : PrintDocumentAdapter() {

    private val photoDecoder = PhotoDecoder(context.contentResolver, config)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var writeFuture: FutureTask<*>? = null
    private var writeExecutor: ExecutorService? = null

    override fun onLayout(
        oldAttributes: PrintAttributes?,
        newAttributes: PrintAttributes,
        cancellationSignal: CancellationSignal?,
        callback: LayoutResultCallback,
        extras: Bundle?
    ) {
        if (cancellationSignal?.isCanceled == true) {
            callback.onLayoutCancelled()
            return
        }

        val info = PrintDocumentInfo.Builder("a4miniprint.pdf")
            .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
            .setPageCount(pages.size)
            .build()

        callback.onLayoutFinished(info, newAttributes != oldAttributes)
    }

    override fun onWrite(
        requestedRanges: Array<out PageRange>,
        destination: ParcelFileDescriptor,
        cancellationSignal: CancellationSignal?,
        callback: WriteResultCallback
    ) {
        // onWrite 会在主线程被调用；图片解码和 PDF 写入必须在后台执行，
        // 否则生成打印预览时整个应用和系统 UI 都会被阻塞。
        writeFuture?.cancel(true)
        writeExecutor?.shutdownNow()

        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "a4miniprint-print-write").apply {
                isDaemon = true
            }
        }
        writeExecutor = executor

        val selectedPages = pagesInRequestedRanges(requestedRanges)
        if (selectedPages.isEmpty()) {
            executor.shutdownNow()
            callback.onWriteFailed("没有需要打印的页面")
            return
        }

        val resultDelivered = AtomicBoolean(false)
        val notifyResult: ((() -> Unit) -> Unit) = { action ->
            if (resultDelivered.compareAndSet(false, true)) {
                mainHandler.post(action)
            }
        }

        val task = FutureTask<Any?> {
            val document = PdfDocument()

            try {
                selectedPages.forEachIndexed { index, page ->
                    if (cancellationSignal?.isCanceled == true ||
                        Thread.currentThread().isInterrupted
                    ) {
                        notifyResult { callback.onWriteCancelled() }
                        return@FutureTask
                    }

                    val pageInfo = PdfDocument.PageInfo.Builder(
                        mmToPoint(config.pageSize(page.isPortrait).widthMm),
                        mmToPoint(config.pageSize(page.isPortrait).heightMm),
                        index + 1
                    ).create()

                    val pdfPage = document.startPage(pageInfo)
                    renderPage(pdfPage.canvas, page)
                    document.finishPage(pdfPage)
                }

                FileOutputStream(destination.fileDescriptor).use { output ->
                    document.writeTo(output)
                }

                notifyResult {
                    callback.onWriteFinished(requestedRanges)
                }
            } catch (error: Exception) {
                if (cancellationSignal?.isCanceled == true ||
                    Thread.currentThread().isInterrupted
                ) {
                    notifyResult { callback.onWriteCancelled() }
                } else {
                    notifyResult { callback.onWriteFailed(error.message) }
                }
            } finally {
                document.close()
                executor.shutdownNow()
            }

            null
        }

        writeFuture = task

        cancellationSignal?.setOnCancelListener {
            task.cancel(true)
            executor.shutdownNow()
            notifyResult { callback.onWriteCancelled() }
        }

        if (cancellationSignal?.isCanceled == true) {
            task.cancel(true)
            executor.shutdownNow()
            notifyResult { callback.onWriteCancelled() }
            return
        }

        executor.execute(task)
    }

    private fun pagesInRequestedRanges(
        requestedRanges: Array<out PageRange>
    ): List<PrintPage> {
        if (requestedRanges.isEmpty() ||
            requestedRanges.any { it == PageRange.ALL_PAGES }
        ) {
            return pages
        }

        return pages.filterIndexed { index, _ ->
            // Android PageRange 使用 0 基页码；这里必须用原始 index 匹配，
            // 否则系统请求第 0 页时会误判为不存在，导致“没有需要打印的页面”。
            val pageNumber = index
            requestedRanges.any { range ->
                pageNumber >= range.start && pageNumber <= range.end
            }
        }
    }

    private fun renderPage(canvas: Canvas, page: PrintPage) {
        canvas.drawColor(Color.WHITE)

        val paint = Paint().apply {
            isAntiAlias = true
            isFilterBitmap = true
        }

        page.slots.forEach { slot ->
            val bitmap = photoDecoder.decode(slot)

            try {
                val destinationRect = when (slot.fitMode) {
                    PhotoFitMode.CONTAIN -> calculateContainRect(
                        bitmapWidth = bitmap.width,
                        bitmapHeight = bitmap.height,
                        slotRect = slot.rectMm
                    )
                    PhotoFitMode.COVER -> RectF(
                        mmToPointF(slot.rectMm.left),
                        mmToPointF(slot.rectMm.top),
                        mmToPointF(slot.rectMm.right),
                        mmToPointF(slot.rectMm.bottom)
                    )
                }

                val sourceRect = if (slot.fitMode == PhotoFitMode.COVER) {
                    calculateCropRect(
                        bitmapWidth = bitmap.width,
                        bitmapHeight = bitmap.height,
                        targetAspect = slot.rectMm.width() / slot.rectMm.height()
                    )
                } else {
                    Rect(0, 0, bitmap.width, bitmap.height)
                }

                canvas.drawBitmap(bitmap, sourceRect, destinationRect, paint)
            } finally {
                bitmap.recycle()
            }
        }
    }

    private fun calculateCropRect(
        bitmapWidth: Int,
        bitmapHeight: Int,
        targetAspect: Float
    ): Rect {
        val sourceAspect = bitmapWidth.toFloat() / bitmapHeight.toFloat()

        return if (sourceAspect > targetAspect) {
            val cropWidth = (bitmapHeight * targetAspect).roundToInt()
            val left = ((bitmapWidth - cropWidth) / 2f).roundToInt()
            Rect(left, 0, left + cropWidth, bitmapHeight)
        } else {
            val cropHeight = (bitmapWidth / targetAspect).roundToInt()
            val top = ((bitmapHeight - cropHeight) / 2f).roundToInt()
            Rect(0, top, bitmapWidth, top + cropHeight)
        }
    }

    private fun calculateContainRect(
        bitmapWidth: Int,
        bitmapHeight: Int,
        slotRect: RectF
    ): RectF {
        val sourceAspect = bitmapWidth.toFloat() / bitmapHeight.toFloat()
        val slotAspect = slotRect.width() / slotRect.height()

        val width = if (sourceAspect > slotAspect) {
            slotRect.width()
        } else {
            slotRect.height() * sourceAspect
        }
        val height = if (sourceAspect > slotAspect) {
            slotRect.width() / sourceAspect
        } else {
            slotRect.height()
        }

        val left = slotRect.left + (slotRect.width() - width) / 2f
        val top = slotRect.top + (slotRect.height() - height) / 2f

        return RectF(
            mmToPointF(left),
            mmToPointF(top),
            mmToPointF(left + width),
            mmToPointF(top + height)
        )
    }

    private fun mmToPoint(valueMm: Float): Int {
        return (valueMm / 25.4f * 72f).roundToInt()
    }

    private fun mmToPointF(valueMm: Float): Float {
        return valueMm / 25.4f * 72f
    }
}
