package ru.evrasia.research

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject

internal class WebDownloadController(
    private val activity: AppCompatActivity,
    private val web: WebView,
    private val userAgent: String,
    private val record: (JSONObject) -> Unit
) {
    fun install() {
        web.setDownloadListener { url, suppliedUserAgent, contentDisposition, mimeType, contentLength ->
            if (url.isNullOrBlank()) return@setDownloadListener
            if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
                activity.runOnUiThread {
                    Toast.makeText(activity, "Этот тип загрузки пока не поддерживается: ${Uri.parse(url).scheme ?: "unknown"}", Toast.LENGTH_LONG).show()
                }
                recordDownload(url, null, mimeType, contentLength, "unsupported-scheme")
                return@setDownloadListener
            }

            try {
                val fileName = sanitizeFileName(URLUtil.guessFileName(url, contentDisposition, mimeType))
                val request = DownloadManager.Request(Uri.parse(url)).apply {
                    setTitle(fileName)
                    setDescription("web research")
                    if (!mimeType.isNullOrBlank()) setMimeType(mimeType)
                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    setAllowedOverMetered(true)
                    setAllowedOverRoaming(true)
                    setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)

                    val effectiveUserAgent = suppliedUserAgent?.takeIf { it.isNotBlank() } ?: userAgent
                    if (effectiveUserAgent.isNotBlank()) addRequestHeader("User-Agent", effectiveUserAgent)

                    val cookies = CookieManager.getInstance().getCookie(url)
                    if (!cookies.isNullOrBlank()) addRequestHeader("Cookie", cookies)

                    val referer = web.url
                    if (!referer.isNullOrBlank()) addRequestHeader("Referer", referer)
                }

                val manager = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                val downloadId = manager.enqueue(request)
                recordDownload(url, fileName, mimeType, contentLength, "queued", downloadId)
                activity.runOnUiThread {
                    Toast.makeText(activity, "Скачивание началось: $fileName", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                recordDownload(url, null, mimeType, contentLength, "error", error = e.toString())
                activity.runOnUiThread {
                    Toast.makeText(activity, "Не удалось начать скачивание", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun sanitizeFileName(name: String): String {
        val cleaned = name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().trim('.')
        return cleaned.takeIf { it.isNotBlank() }?.take(180) ?: "download_${System.currentTimeMillis()}"
    }

    private fun recordDownload(
        url: String,
        fileName: String?,
        mimeType: String?,
        contentLength: Long,
        status: String,
        downloadId: Long? = null,
        error: String? = null
    ) {
        val event = JSONObject()
            .put("source", "download")
            .put("time", System.currentTimeMillis())
            .put("url", url)
            .put("status", status)
            .put("contentLength", contentLength)
        if (!fileName.isNullOrBlank()) event.put("fileName", fileName)
        if (!mimeType.isNullOrBlank()) event.put("mimeType", mimeType)
        if (downloadId != null) event.put("downloadId", downloadId)
        if (!error.isNullOrBlank()) event.put("error", error)
        record(event)
    }
}
