from pathlib import Path

root = Path('app/src/main/java/ru/evrasia/research')
activity_path = root / 'WebResearchV10Activity.kt'
activity = activity_path.read_text()

old_decl = '    private val text = Color.rgb(238, 245, 241)\n'
new_decl = '    private val textColor = Color.rgb(238, 245, 241)\n'
if old_decl not in activity:
    raise SystemExit('text color field not found')
activity = activity.replace(old_decl, new_decl, 1)
activity = activity.replace('setTextColor(text)', 'setTextColor(textColor)')
activity_path.write_text(activity)

debugger_path = root / 'NetworkDebuggerActivity.kt'
debugger = debugger_path.read_text()
old_popup = '        spinner.popupBackgroundDrawable=rounded(panel,12f,line)\n'
new_popup = '        spinner.setPopupBackgroundDrawable(rounded(panel,12f,line))\n'
if old_popup not in debugger:
    raise SystemExit('spinner popup compatibility call not found')
debugger = debugger.replace(old_popup, new_popup, 1)
debugger_path.write_text(debugger)

for compat in ('TextColorCompat.kt', 'SpinnerCompat.kt'):
    path = root / compat
    if not path.exists():
        raise SystemExit(f'{compat} not found')
    path.unlink()

print('stage13 cleanup applied')
