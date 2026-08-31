package ru.evrasia.research

import android.app.Activity
import android.content.Intent
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal class WebResearchExportController(
    private val activity: AppCompatActivity,
    private val archive: ResearchArchive,
    private val web: WebView,
    private val captureSnapshot: () -> Unit
) {
    fun start() {
        captureSnapshot()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/zip"
            putExtra(Intent.EXTRA_TITLE, "web-research-$stamp.zip")
        }
        activity.startActivityForResult(intent, REQUEST_EXPORT_ZIP)
    }

    fun handleResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != REQUEST_EXPORT_ZIP) return false
        if (resultCode == Activity.RESULT_OK) {
            data?.data?.let { uri ->
                activity.contentResolver.openOutputStream(uri)?.use { archive.writeZip(it, web.url ?: "") }
            }
        }
        return true
    }

    companion object {
        private const val REQUEST_EXPORT_ZIP = 501
    }
}
