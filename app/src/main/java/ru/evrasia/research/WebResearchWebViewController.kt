package ru.evrasia.research

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
