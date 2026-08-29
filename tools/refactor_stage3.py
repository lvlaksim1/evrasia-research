from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    file = Path(path)
    text = file.read_text()
    if old not in text:
        raise SystemExit(f"Expected block not found in {path}: {old[:100]!r}")
    file.write_text(text.replace(old, new, 1))


activity = "app/src/main/java/ru/evrasia/research/WebResearchV10Activity.kt"
replace_once(
    activity,
    "class WebResearchV10Activity : AppCompatActivity() {\n    private lateinit var web: WebView",
    "class WebResearchV10Activity : AppCompatActivity() {\n"
    "    internal fun researchWebView(): WebView? = if (::web.isInitialized) web else null\n"
    "    internal fun researchArchive(): ResearchArchive = archive\n"
    "    internal fun researchUserAgent(): String = if (::userAgent.isInitialized) userAgent else \"\"\n"
    "    internal fun captureResearchSnapshot() = capturePageSnapshot()\n\n"
    "    private lateinit var web: WebView",
)

app = "app/src/main/java/ru/evrasia/research/WebResearchApp.kt"
replace_once(
    app,
    "    override fun onCreate(){super.onCreate();registerActivityLifecycleCallbacks(this);handler.post(ticker)}\n",
    "    override fun onCreate(){super.onCreate();registerActivityLifecycleCallbacks(this);handler.post(ticker)}\n\n"
    "    internal fun activeBrowserActivity(): WebResearchV10Activity? = browserRef.get()\n",
)
replace_once(
    app,
    "            val f=WebResearchV10Activity::class.java.getDeclaredField(\"archive\");f.isAccessible=true\n"
    "            val a=f.get(browser) as? ResearchArchive?:return",
    "            val a=browser.researchArchive()",
)
replace_once(
    app,
    "            val f=WebResearchV10Activity::class.java.getDeclaredField(\"web\");f.isAccessible=true\n"
    "            val w=f.get(browser) as? WebView\n"
    "            val page=w?.url.orEmpty()",
    "            val w=browser.researchWebView()\n"
    "            val page=w?.url.orEmpty()",
)
replace_once(
    app,
    "            val f=WebResearchV10Activity::class.java.getDeclaredField(\"web\");f.isAccessible=true\n"
    "            val w=f.get(a) as? WebView?:return",
    "            val w=a.researchWebView()?:return",
)
replace_once(app, "import android.webkit.WebView\n", "")

for provider in [
    "app/src/main/java/ru/evrasia/research/CookieTraceV47Provider.kt",
    "app/src/main/java/ru/evrasia/research/CookieTraceV48UiProvider.kt",
]:
    file = Path(provider)
    text = file.read_text()
    start = text.index("    private fun webOf(activity: WebResearchV10Activity): WebView? = try {")
    end_marker = "    } catch (_: Exception) { null }\n"
    first_end = text.index(end_marker, start) + len(end_marker)
    archive_start = text.index(
        "    private fun archiveOf(activity: WebResearchV10Activity): ResearchArchive? = try {",
        first_end,
    )
    archive_end = text.index(end_marker, archive_start) + len(end_marker)
    replacement = (
        "    private fun webOf(activity: WebResearchV10Activity): WebView? = activity.researchWebView()\n\n"
        "    private fun archiveOf(activity: WebResearchV10Activity): ResearchArchive? = activity.researchArchive()\n"
    )
    file.write_text(text[:start] + replacement + text[archive_end:])

actions = "app/src/main/java/ru/evrasia/research/NetworkRequestActions.kt"
replace_once(
    actions,
    "    fun prepareFullExport(activity: Activity) {\n"
    "        val browser = activeBrowser(activity) ?: return\n"
    "        try {\n"
    "            val method = WebResearchV10Activity::class.java.getDeclaredMethod(\"capturePageSnapshot\")\n"
    "            method.isAccessible = true\n"
    "            method.invoke(browser)\n"
    "        } catch (_: Exception) {}\n"
    "    }",
    "    fun prepareFullExport(activity: Activity) {\n"
    "        val browser = activeBrowser(activity) ?: return\n"
    "        browser.captureResearchSnapshot()\n"
    "    }",
)
file = Path(actions)
text = file.read_text()
start = text.index("    private fun activeBrowser(activity: Activity): WebResearchV10Activity? {")
end = text.index("\n}", start)
replacement = (
    "    private fun activeBrowser(activity: Activity): WebResearchV10Activity? =\n"
    "        (activity.application as? WebResearchApp)?.activeBrowserActivity()\n\n"
    "    private fun archiveOf(browser: WebResearchV10Activity): ResearchArchive? = browser.researchArchive()\n\n"
    "    private fun currentUrl(browser: WebResearchV10Activity): String = browser.researchWebView()?.url.orEmpty()\n\n"
    "    private fun browserUserAgent(browser: WebResearchV10Activity?): String = browser?.researchUserAgent().orEmpty()\n"
)
file.write_text(text[:start] + replacement + text[end:])

debugger = "app/src/main/java/ru/evrasia/research/NetworkDebuggerActivity.kt"
replace_once(
    debugger,
    "            val line=\"${if(e.has(\"time\"))listTime(e.optLong(\"time\")) else \"--:--:--.---\"}  $direction${if(data.isNotBlank())\"\\n$data\" else \"\"}\"\n"
    "            copyText.append(line).append(\"\\n\\n\")\n"
    "            root.addView(TextView(this).apply{\n"
    "                text=line;setTextColor(if(direction==\"SEND\")amber else if(direction==\"RECEIVE\")accent else muted);textSize=10.5f;typeface=Typeface.MONOSPACE;setTextIsSelectable(true);setPadding(dp(9),dp(8),dp(9),dp(8));background=rounded(panel2,9f,line)",
    "            val displayLine=\"${if(e.has(\"time\"))listTime(e.optLong(\"time\")) else \"--:--:--.---\"}  $direction${if(data.isNotBlank())\"\\n$data\" else \"\"}\"\n"
    "            copyText.append(displayLine).append(\"\\n\\n\")\n"
    "            root.addView(TextView(this).apply{\n"
    "                text=displayLine;setTextColor(if(direction==\"SEND\")amber else if(direction==\"RECEIVE\")accent else muted);textSize=10.5f;typeface=Typeface.MONOSPACE;setTextIsSelectable(true);setPadding(dp(9),dp(8),dp(9),dp(8));background=rounded(panel2,9f,line)",
)
replace_once(
    debugger,
    "        dialog=AlertDialog.Builder(this).setView(root).create()\n"
    "        dialog.setOnShowListener{\n"
    "            val dm=resources.displayMetrics\n"
    "            dialog.window?.setLayout((dm.widthPixels*0.97).toInt(),(dm.heightPixels*0.92).toInt())\n"
    "            dialog.window?.setBackgroundDrawable(rounded(bg,18f,line))\n"
    "        }\n"
    "        dialog.show()",
    "        dialog=AlertDialog.Builder(this).setView(root).create()\n"
    "        val dm=resources.displayMetrics\n"
    "        dialog.window?.apply{\n"
    "            setBackgroundDrawable(rounded(bg,18f,line))\n"
    "            attributes=attributes.apply{\n"
    "                width=(dm.widthPixels*0.97).toInt()\n"
    "                height=(dm.heightPixels*0.92).toInt()\n"
    "            }\n"
    "        }\n"
    "        dialog.show()",
)

workflow = ".github/workflows/android-apk.yml"
file = Path(workflow)
text = file.read_text()
marker = "      - name: Prepare request detail geometry\n"
start = text.index(marker)
next_step = text.index("      - name: Build APK\n", start)
file.write_text(text[:start] + text[next_step:])

compat = Path("app/src/main/java/ru/evrasia/research/NetworkDebuggerCompat.kt")
if compat.exists():
    compat.unlink()
