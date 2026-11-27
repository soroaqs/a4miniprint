package io.github.soroaqs.a4miniprint.image

import android.content.ContentResolver
import android.net.Uri
import io.github.soroaqs.a4miniprint.data.PhotoMetadata
import io.github.soroaqs.a4miniprint.data.PhotoSourceType
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * 读取 PDF 页数与每页尺寸。
 *
 * 部分云盘或 pipe provider 提供的文件描述符不可 seek，PdfRenderer 无法直接打开。
 * 这类来源会复制一份临时副本到 cacheDir，再以 file URI 打开。
 */
class PdfMetadataLoader(
    private val contentResolver: ContentResolver,
    private val cacheDir: File
) {

    fun load(sourceUri: Uri): PdfSource {
        val direct = tryRead(sourceUri)
        if (direct != null) {
            return direct
        }

        val cachedFile = copyToCache(sourceUri)
        val cached = tryRead(Uri.fromFile(cachedFile))
        if (cached == null) {
            cachedFile.delete()
            throw IOException("无法打开 PDF，文件可能已加密或损坏")
        }
        return cached.copy(cachedFile = cachedFile)
    }

    /**
     * 清理旧的临时副本。
     *
     * 只在下一次选择 PDF 或冷启动时调用：系统打印框架可能多次回调 onWrite，
     * 不能在一轮写入结束或 Activity 销毁时删除当前正在使用的文件。
     */
    fun clearCachedPdfs() {
        cacheDir.listFiles { file ->
            file.isFile && file.name.startsWith(PDF_CACHE_PREFIX)
        }?.forEach { file ->
            file.delete()
        }
    }

    private fun tryRead(uri: Uri): PdfSource? {
        return try {
            PdfDocumentHandle.open(contentResolver, uri).use { handle ->
                val pageCount = handle.renderer.pageCount
                if (pageCount <= 0) {
                    return null
                }

                val pages = (0 until pageCount).map { index ->
                    val page = handle.renderer.openPage(index)
                    try {
                        PhotoMetadata(
                            uri = uri,
                            rawWidth = page.width,
                            rawHeight = page.height,
                            exifOrientation = 0,
                            sourceType = PhotoSourceType.PDF_PAGE,
                            pdfPageIndex = index
                        )
                    } finally {
                        page.close()
                    }
                }

                PdfSource(
                    resolvedUri = uri,
                    pageCount = pageCount,
                    pages = pages,
                    cachedFile = null
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun copyToCache(sourceUri: Uri): File {
        val file = File(cacheDir, "$PDF_CACHE_PREFIX${UUID.randomUUID()}.pdf")
        try {
            val input = contentResolver.openInputStream(sourceUri)
                ?: throw IOException("无法读取 PDF：$sourceUri")
            input.use { source ->
                file.outputStream().use { target ->
                    source.copyTo(target)
                }
            }
        } catch (error: Exception) {
            file.delete()
            throw error
        }
        return file
    }

    companion object {
        private const val PDF_CACHE_PREFIX = "pdf-source-"
    }
}

/**
 * 一次 PDF 载入的结果。
 *
 * [pages] 每个 PDF 页对应一个 [PhotoMetadata]，打印时逐页展开成多张 A4。
 * [cachedFile] 仅在原始来源不可 seek 时存在。
 */
data class PdfSource(
    val resolvedUri: Uri,
    val pageCount: Int,
    val pages: List<PhotoMetadata>,
    val cachedFile: File?
)
