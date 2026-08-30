from pathlib import Path

path = Path('app/src/main/java/ru/evrasia/research/WebResearchApp.kt')
s = path.read_text()
old_created = '    override fun onActivityCreated(a:Activity,s:Bundle?){when(a){is WebResearchV10Activity->{browserRef=WeakReference(a);mirroredCount=0;mirroredScripts.clear();NetworkDebugStore.clear();brandBrowser(a)};is NetworkDebuggerActivity->debuggerRef=WeakReference(a)}}\n'
new_created = '    override fun onActivityCreated(a:Activity,s:Bundle?){when(a){is WebResearchV10Activity->{browserRef=WeakReference(a);NetworkDebugStore.clear();brandBrowser(a)};is NetworkDebuggerActivity->debuggerRef=WeakReference(a)}}\n'
if old_created not in s:
    raise SystemExit('Expected onActivityCreated block not found')
s = s.replace(old_created, new_created, 1)
old_resumed = '    override fun onActivityResumed(a:Activity){when(a){is WebResearchV10Activity->{browserRef=WeakReference(a);syncNetworkStore();brandBrowser(a);a.ensureInstrumentation();installAdvancedCapture(a)};is NetworkDebuggerActivity->{debuggerRef=WeakReference(a);browserRef.get()?.let{it.ensureInstrumentation();installAdvancedCapture(it)};syncNetworkStore();brandDebugger(a)}}}\n'
new_resumed = '    override fun onActivityResumed(a:Activity){when(a){is WebResearchV10Activity->{browserRef=WeakReference(a);brandBrowser(a);a.ensureInstrumentation();installAdvancedCapture(a)};is NetworkDebuggerActivity->{debuggerRef=WeakReference(a);browserRef.get()?.let{it.ensureInstrumentation();installAdvancedCapture(it)};brandDebugger(a)}}}\n'
if old_resumed not in s:
    raise SystemExit('Expected onActivityResumed block not found')
path.write_text(s.replace(old_resumed, new_resumed, 1))
