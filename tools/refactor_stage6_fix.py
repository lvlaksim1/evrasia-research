from pathlib import Path

path = Path('app/src/main/java/ru/evrasia/research/WebResearchApp.kt')
s = path.read_text()
old = '    override fun onActivityResumed(a:Activity){when(a){is WebResearchV10Activity->{browserRef=WeakReference(a);syncNetworkStore();brandBrowser(a);a.ensureInstrumentation();installAdvancedCapture(a)};is NetworkDebuggerActivity->{debuggerRef=WeakReference(a);browserRef.get()?.let{it.ensureInstrumentation();installAdvancedCapture(it)};syncNetworkStore();brandDebugger(a)}}}\n'
new = '    override fun onActivityResumed(a:Activity){when(a){is WebResearchV10Activity->{browserRef=WeakReference(a);brandBrowser(a);a.ensureInstrumentation();installAdvancedCapture(a)};is NetworkDebuggerActivity->{debuggerRef=WeakReference(a);browserRef.get()?.let{it.ensureInstrumentation();installAdvancedCapture(it)};brandDebugger(a)}}}\n'
if old not in s:
    raise SystemExit('Expected onActivityResumed block not found')
path.write_text(s.replace(old, new, 1))
