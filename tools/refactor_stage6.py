from pathlib import Path
import re

activity_path = Path('app/src/main/java/ru/evrasia/research/WebResearchV10Activity.kt')
activity = activity_path.read_text()


def extract_js(function_name: str, next_marker: str):
    start = activity.find(function_name)
    end = activity.find(next_marker, start)
    if start < 0 or end < 0 or end <= start:
        raise SystemExit(f'cannot locate {function_name}')
    segment = activity[start:end]
    match = re.search(r'val js = """(.*?)"""\.trimIndent\(\)', segment, re.S)
    if not match:
        raise SystemExit(f'cannot extract js from {function_name}')
    return start, end, match.group(1)

inst_start, inst_end, inst_js = extract_js('    fun ensureInstrumentation()', '    private fun captureLightPageSnapshot()')
light_start, light_end, light_js = extract_js('    private fun captureLightPageSnapshot()', '    private fun capturePageSnapshot()')
full_start, full_end, full_js = extract_js('    private fun capturePageSnapshot()', '    private fun addRecord(')

# Replace from bottom to top so offsets remain valid.
full_replacement = '''    private fun capturePageSnapshot() {\n        if (!::web.isInitialized) return\n        val nativeCookies = CookieManager.getInstance().getCookie(web.url ?: "") ?: ""\n        web.evaluateJavascript(WebResearchScripts.fullSnapshot(nativeCookies), null)\n    }\n\n'''
activity = activity[:full_start] + full_replacement + activity[full_end:]

light_replacement = '''    private fun captureLightPageSnapshot() {\n        if (!::web.isInitialized) return\n        val nativeCookies = CookieManager.getInstance().getCookie(web.url ?: "") ?: ""\n        web.evaluateJavascript(WebResearchScripts.lightSnapshot(nativeCookies), null)\n    }\n\n'''
activity = activity[:light_start] + light_replacement + activity[light_end:]

inst_replacement = '''    fun ensureInstrumentation() {\n        if (!::web.isInitialized) return\n        web.evaluateJavascript(WebResearchScripts.instrumentation(), null)\n    }\n\n'''
activity = activity[:inst_start] + inst_replacement + activity[inst_end:]
activity_path.write_text(activity)

scripts_path = Path('app/src/main/java/ru/evrasia/research/WebResearchScripts.kt')
if scripts_path.exists():
    raise SystemExit('WebResearchScripts.kt already exists')

scripts_path.write_text(
    'package ru.evrasia.research\n\n'
    'import org.json.JSONObject\n\n'
    'internal object WebResearchScripts {\n'
    '    fun instrumentation(): String = """' + inst_js + '""".trimIndent()\n\n'
    '    fun lightSnapshot(nativeCookies: String): String = """' + light_js + '""".trimIndent()\n\n'
    '    fun fullSnapshot(nativeCookies: String): String = """' + full_js + '""".trimIndent()\n'
    '}\n'
)

print('stage6 refactor applied')
