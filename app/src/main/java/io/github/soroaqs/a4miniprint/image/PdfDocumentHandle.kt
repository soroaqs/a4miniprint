package io.github.soroaqs.a4miniprint.image

import android.content.ContentResolver
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.File
import java.io.IOException

/**
 * 持有一次 PdfRenderer 会话。
 *
 * PdfRenderer 的构造函数会接管传入的文件描述符，并在 close() 时一起关闭；
 * 构造失败时由这里负责关闭描述符，避免泄漏。
 */
internal class PdfDocumentHandle private constructor(
    val renderer: PdfRenderer
) : Closeable {

    override fun close() {
        renderer.close()
    }

    companion object {
        fun open(contentResolver: ContentResolver, uri: Uri): PdfDocumentHandle {
            val descriptor = when (uri.scheme) {
                ContentResolver.SCHEME_FILE -> {
                    val path = uri.path ?: throw IOException("无法打开 PDF：$uri")
                    ParcelFileDescriptor.open(
                        File(path),
                        ParcelFileDescriptor.MODE_READ_ONLY
                    )
                }

                else -> contentResolver.openFileDescriptor(uri, "r")
            } ?: throw IOException("无法打开 PDF：$uri")

            return try {
                PdfDocumentHandle(PdfRenderer(descriptor))
            } catch (error: Exception) {
                descriptor.close()
                throw error
            }
        }
    }
}
