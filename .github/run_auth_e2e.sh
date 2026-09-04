#!/usr/bin/env bash
set -euo pipefail

COLLECTION="$(find app/build -name 'auth-e2e.postman_collection.json' -print -quit)"
if [ -z "$COLLECTION" ]; then
  echo "AUTH E2E collection was not generated"
  exit 1
fi

python3 .github/auth_replay_mock.py >/tmp/web-research-auth-mock.log 2>&1 &
MOCK_PID=$!
trap 'kill "$MOCK_PID" 2>/dev/null || true' EXIT

python3 - <<'PY'
import time
import urllib.request

for _ in range(40):
    try:
        with urllib.request.urlopen("http://127.0.0.1:18080/health", timeout=1) as response:
            if response.status == 200:
                break
    except Exception:
        time.sleep(0.25)
else:
    raise SystemExit("AUTH mock did not start")
PY

if ! npx --yes newman@6.2.1 run "$COLLECTION" --env-var login=user@example.test --env-var password=secret --bail; then
  cat /tmp/web-research-auth-mock.log
  exit 1
fi
