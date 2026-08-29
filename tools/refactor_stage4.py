from pathlib import Path

PATH = Path("app/src/main/java/ru/evrasia/research/NetworkDebuggerActivity.kt")
text = PATH.read_text()


def replace_private_fun(name: str, replacement: str) -> None:
    global text
    marker = f"    private fun {name}"
    start = text.find(marker)
    if start < 0:
        raise SystemExit(f"Method not found: {name}")
    candidates = []
    for next_marker in ("    private fun ", "    inner class ", "    override fun "):
        pos = text.find(next_marker, start + len(marker))
        if pos >= 0:
            candidates.append(pos)
    if not candidates:
        raise SystemExit(f"End marker not found for: {name}")
    end = min(candidates)
    block = replacement.rstrip()
    if block:
        block += "\n\n"
    text = text[:start] + block + text[end:]


constants = '''    private val displayMergeSources = setOf("webview","resource-copy","resource-timing","fetch","fetch-meta","xhr","xhr-meta")
    private val displaySourceOrder = listOf("webview","fetch","xhr","resource-timing","resource-copy","replay","fetch-meta","xhr-meta")
    private val displayMergeWindowMs = 1200L
    private val strongDuplicateWindowMs = 90L
'''
if constants not in text:
    raise SystemExit("Display merge constants block not found")
text = text.replace(constants, "", 1)
text = text.replace("import kotlin.math.abs\n", "", 1)

replace_private_fun(
    "mergeForDisplay",
    "    private fun mergeForDisplay(source:List<JSONObject>):List<JSONObject> = NetworkDisplayMerger.merge(source)"
)
for name in (
    "collapseStrongDuplicates",
    "strongDuplicateCandidate",
    "bestResponseSize",
    "isDisplayMergeable",
    "displayMergeKey",
    "timesCompatible",
    "orderedSourceLabel",
    "displaySource",
    "mergeDisplayInto",
    "mergeJsonObjects",
    "meaningful",
    "betterNumeric",
):
    replace_private_fun(name, "")

replace_private_fun(
    "eventSources",
    "    private fun eventSources(event:JSONObject) = NetworkEventClassifier.eventSources(event)"
)
replace_private_fun(
    "sourceSummary",
    "    private fun sourceSummary(event:JSONObject):String = NetworkDisplayMerger.sourceSummary(event,mergeMode)"
)

replace_private_fun(
    "groupEndpoints",
    "    private fun groupEndpoints(source:List<JSONObject>):List<JSONObject> = NetworkEndpointAnalyzer.group(source)"
)
replace_private_fun("isEndpointEligible", "")
replace_private_fun(
    "isEndpointGroup",
    "    private fun isEndpointGroup(event:JSONObject) = NetworkEventClassifier.isEndpointGroup(event)"
)
replace_private_fun(
    "normalizeEndpoint",
    "    private fun normalizeEndpoint(raw:String):String = NetworkEndpointAnalyzer.normalize(raw)"
)
replace_private_fun(
    "responseKind",
    "    private fun responseKind(event:JSONObject):String = NetworkEventClassifier.responseKind(event)"
)
replace_private_fun(
    "eventLocation",
    "    private fun eventLocation(event:JSONObject):String = NetworkEventClassifier.eventLocation(event)"
)
replace_private_fun(
    "methodOf",
    "    private fun methodOf(event:JSONObject):String = NetworkEventClassifier.methodOf(event)"
)
replace_private_fun(
    "responseBodyText",
    "    private fun responseBodyText(event:JSONObject):String = NetworkEventClassifier.responseBodyText(event)"
)
replace_private_fun(
    "hasRequestBody",
    "    private fun hasRequestBody(event:JSONObject):Boolean = NetworkEventClassifier.hasRequestBody(event)"
)
replace_private_fun(
    "isRealtimeSession",
    "    private fun isRealtimeSession(event:JSONObject) = NetworkEventClassifier.isRealtimeSession(event)"
)
replace_private_fun(
    "isApiRelevant",
    "    private fun isApiRelevant(event:JSONObject):Boolean = NetworkEventClassifier.isApiRelevant(event)"
)
replace_private_fun(
    "isPlainRequestEvent",
    "    private fun isPlainRequestEvent(event:JSONObject):Boolean = NetworkEventClassifier.isPlainRequestEvent(event)"
)
replace_private_fun(
    "isRequestEvent",
    "    private fun isRequestEvent(event:JSONObject):Boolean = NetworkEventClassifier.isRequestEvent(event)"
)
replace_private_fun(
    "isActionEvent",
    "    private fun isActionEvent(event:JSONObject):Boolean = NetworkEventClassifier.isActionEvent(event)"
)
replace_private_fun(
    "isJsEvent",
    "    private fun isJsEvent(event:JSONObject):Boolean = NetworkEventClassifier.isJsEvent(event)"
)
replace_private_fun(
    "hostOf",
    "    private fun hostOf(url:String):String? = NetworkEventClassifier.hostOf(url)"
)

PATH.write_text(text)
