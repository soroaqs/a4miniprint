package io.github.soroaqs.a4miniprint.share

import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat

/**
 * 兼容系统分享面板可能传入的多种 URI 形式。
 *
 * SEND / SEND_MULTIPLE 对应分享面板；VIEW 对应「用其他应用打开」。
 */
class ShareIntentParser {

    fun parse(intent: Intent): List<Uri> {
        // 只把系统分享 / 打开动作当作输入入口；普通启动、深链或异常 Intent
        // 即使携带 URI，也不应误触发载入。
        if (intent.action != Intent.ACTION_SEND &&
            intent.action != Intent.ACTION_SEND_MULTIPLE &&
            intent.action != Intent.ACTION_VIEW
        ) {
            return emptyList()
        }

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
