from pathlib import Path

path = Path('tools/refactor_stage10.py')
text = path.read_text()
start = text.find("old_lifecycle = '''")
end = text.find("new_lifecycle = '''", start)
if start < 0 or end < 0:
    raise SystemExit('lifecycle literals not found')
section = text[start:end].replace('statsHandler.removeCallbacks(statsTicker)', 'uiHandler.removeCallbacks(statsTicker)')
text = text[:start] + section + text[end:]
path.write_text(text)
print('stage10 patch corrected')
