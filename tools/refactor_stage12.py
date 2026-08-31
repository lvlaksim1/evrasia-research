from pathlib import Path

activity_path = Path('app/src/main/java/ru/evrasia/research/WebResearchV10Activity.kt')
activity = activity_path.read_text()

field_anchor = '    private lateinit var statsController: WebCookieStatsController\n'
if field_anchor not in activity:
    raise SystemExit('controller field anchor not found')
activity = activity.replace(field_anchor, field_anchor + '    private lateinit var webViewController: WebResearchWebViewController\n    private lateinit var exportController: WebResearchExportController\n', 1)

capture_end = '''        WebView.setWebContentsDebuggingEnabled(true)
        web.addJavascriptInterface(captureController.bridge, "EvrasiaResearch")
'''
if capture_end not in activity:
    raise SystemExit('capture controller anchor not found')
activity = activity.replace(capture_end, capture_end + '''        exportController = WebResearchExportController(
            activity = this,
            archive = archive,
            web = web,
            captureSnapshot = { capturePageSnapshot() }
        )
''', 1)

clients_start = activity.find('        web.webChromeClient = object : WebChromeClient() {')
clients_end = activity.find('        navigationController.navigate("https://evrasia.rest/")', clients_start)
if clients_start < 0 or clients_end < 0:
    raise SystemExit('webview clients block not found')
new_clients = '''        webViewController = WebResearchWebViewController(
            activity = this,
            web = web,
            swipeRefresh = swipeRefresh,
            address = address,
            captureController = captureController,
            navigationController = navigationController,
            handler = uiHandler,
            record = { addRecord(it) },
            updateStats = { if (::statsController.isInitialized) statsController.update() }
        )
        webViewController.install()
'''
activity = activity[:clients_start] + new_clients + activity[clients_end:]

export_start = activity.find('    private fun exportZip() {')
resume_start = activity.find('    override fun onResume()', export_start)
if export_start < 0 or resume_start < 0:
    raise SystemExit('export block not found')
new_export = '''    private fun exportZip() {
        if (::exportController.isInitialized) exportController.start()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (::exportController.isInitialized) exportController.handleResult(requestCode, resultCode, data)
    }

'''
activity = activity[:export_start] + new_export + activity[resume_start:]
activity_path.write_text(activity)

Path('app/src/main/java/ru/evrasia/research/WebResearchWebViewController.kt').write_text('''package ru.evrasia.research

import android.graphics.Bitmap
import android.os.Handler
import android.os.Message
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import org.json.JSONObject

internal class WebResearchWebViewController(
    private val activity: AppCompatActivity,
    private val web: WebView,
    private val swipeRefresh: SwipeRefreshLayout,
    private val address: EditText,
    private val captureController: WebCaptureController,
    private val navigationController: WebNavigationController,
    private val handler: Handler,
    private val record: (JSONObject) -> Unit,
    private val updateStats: () -> Unit
) {
    fun install() {
        web.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                record(JSONObject().put("source", "console").put("time", System.currentTimeMillis()).put("level", message.messageLevel().name).put("message", message.message()).put("sourceId", message.sourceId()).put("line", message.lineNumber()))
                return true
            }

            override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?): Boolean {
                if (resultMsg == null) return false
                val temp = WebView(activity)
                temp.settings.javaScriptEnabled = true
                temp.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                        navigationController.openInActiveWindow(request.url.toString())
                        temp.destroy()
                        return true
                    }

                    override fun onPageStarted(v: WebView, url: String, favicon: Bitmap?) {
                        if (url != "about:blank") {
                            navigationController.openInActiveWindow(url)
                            temp.stopLoading()
                            temp.destroy()
                        }
                    }
                }
                val transport = resultMsg.obj as WebView.WebViewTransport
                transport.webView = temp
                resultMsg.sendToTarget()
                return true
            }
        }

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = false

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                handler.postDelayed({ captureController.ensureInstrumentation() }, 100)
                handler.postDelayed({ captureController.ensureInstrumentation() }, 350)
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                swipeRefresh.isRefreshing = false
                address.setText(url)
                record(JSONObject().put("source", "navigation").put("time", System.currentTimeMillis()).put("url", url).put("page", url).put("method", "GET"))
                captureController.ensureInstrumentation()
                captureController.captureLightPageSnapshot()
                updateStats()
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                request?.let {
                    val url = it.url.toString()
                    val headers = HashMap(it.requestHeaders)
                    record(JSONObject().put("source", "webview").put("time", System.currentTimeMillis()).put("method", it.method).put("url", url).put("headers", JSONObject(headers)))
                    if (it.method.equals("GET", true) && (url.startsWith("http://") || url.startsWith("https://")) && captureController.shouldAutoCopyResource(url, headers)) {
                        captureController.captureResource(url, headers, "auto-static")
                    }
                }
                return super.shouldInterceptRequest(view, request)
            }
        }
    }
}
''')

Path('app/src/main/java/ru/evrasia/research/WebResearchExportController.kt').write_text('''package ru.evrasia.research

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
''')

print('stage12 refactor applied')
