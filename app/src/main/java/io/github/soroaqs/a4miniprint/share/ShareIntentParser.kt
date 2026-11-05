package io.github.soroaqs.a4miniprint.share

import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat

/**
 * 兼容系统分享面板可能传入的多种图片 URI 形式。
 */
class ShareIntentParser {

    fun parse(intent: Intent): List<Uri> {
        val uris = LinkedHashSet<Uri>()

        intent.data?.let { uri ->
            uris += uri
        }

        when (intent.action) {
            Intent.ACTION_SEND -> {
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let { uri ->
                    uris += uri
                }
            }

            Intent.ACTION_SEND_MULTIPLE -> {
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                    ?.forEach { uri ->
                        uris += uri
                    }
            }
        }

        intent.clipData?.let { clipData ->
            for (index in 0 until clipData.itemCount) {
                clipData.getItemAt(index).uri?.let { uri ->
                    uris += uri
                }
            }
        }

        return uris
            .filter { it.scheme == "content" || it.scheme == "file" }
    }
}
