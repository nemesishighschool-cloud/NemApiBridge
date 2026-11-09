#!/usr/bin/env bash
# Actual NEMESIS CLI, isolated from host files and external networking.
# Prereqs: checkout at /tmp/nemesis-client with dependencies in .venv,
# bwrap, socat; Android/DeepSeek and forwarded API on 127.0.0.1:8090.
set -euo pipefail
MODE=${1:-read}
[[ "$MODE" == read || "$MODE" == write ]] || { echo 'usage: ... [read|write]' >&2; exit 2; }
ROOT=$(mktemp -d /tmp/nemesis-agent-test.XXXXXX)
CHECKOUT=${NEMESIS_CHECKOUT:-/tmp/nemesis-client}
REPO=$(cd "$(dirname "$0")/.." && pwd)
PYBASE=$(dirname "$(dirname "$(readlink -f "$CHECKOUT/.venv/bin/python")")")
[[ "$PYBASE" == /usr/* ]] || { echo 'The venv Python must be under /usr for this sandbox layout' >&2; exit 2; }
mkdir -p "$ROOT/work/config"
printf 'NEMESIS_TEST_FILE_café\n' > "$ROOT/work/fixture.txt"
cat > "$ROOT/work/config/config.yaml" <<EOF
provider:
  type: nemapi
  model: deepseek-chat
  timeout: 180
nemapi:
  agent: nemesis
  url: http://127.0.0.1:8090/v1
security:
  workspace: /workspace
  approval_mode: all
EOF
python3 -u "$REPO/tests/nemesis-safe-relay.py" "$ROOT/bridge.sock" > "$ROOT/relay.log" 2>&1 &
RELAY=$!
PREVIOUS=$(curl -fsS http://127.0.0.1:8090/web/nem/agent-integrations | python3 -c 'import json,sys; print(str(next(a["enabled"] for a in json.load(sys.stdin)["agents"] if a["id"]=="nemesis")).lower())')
cleanup(){ kill "$RELAY" 2>/dev/null || true; wait "$RELAY" 2>/dev/null || true; curl -fsS -X POST http://127.0.0.1:8090/web/nem/agent-integrations/nemesis -H 'Content-Type: application/json' -d "{\"enabled\":$PREVIOUS}" >/dev/null || true; echo "Test logs: $ROOT"; }
trap cleanup EXIT
sleep 1
test -S "$ROOT/bridge.sock"
curl -fsS -X POST http://127.0.0.1:8090/web/nem/agent-integrations/nemesis -H 'Content-Type: application/json' -d '{"enabled":true}' >/dev/null
if [[ "$MODE" == read ]]; then
 prompt='Use read_file to read /workspace/fixture.txt and report its exact content. Do not run shell commands.'
else
 prompt='Create /workspace/output.txt using write_file. Its content must be exactly NEMESIS_WRITE_OK with no trailing newline. Verify the file using read_file, then say done. Do not run shell commands.'
fi
printf '%s\n/exit\n' "$prompt" | timeout 240 bwrap --unshare-user --unshare-pid --unshare-net --die-with-parent \
 --tmpfs / --ro-bind /usr /usr --symlink usr/bin /bin --symlink usr/lib /lib --symlink usr/lib64 /lib64 \
 --dev /dev --proc /proc --tmpfs /tmp --ro-bind "$CHECKOUT" /tmp/nemesis-client \
 --bind "$ROOT/work" /workspace --bind "$ROOT/bridge.sock" /tmp/bridge.sock \
 --dir /home/codespace/.python --symlink "$PYBASE" /home/codespace/.python/current \
 --clearenv --setenv PATH /usr/local/bin:/usr/bin:/bin --setenv LANG C.UTF-8 --setenv HOME /workspace --setenv NEMESIS_CONFIG_DIR /workspace/config --setenv PYTHONDONTWRITEBYTECODE 1 \
 --chdir /workspace /bin/sh -c 'socat TCP-LISTEN:8090,bind=127.0.0.1,fork,reuseaddr UNIX-CONNECT:/tmp/bridge.sock & exec /tmp/nemesis-client/.venv/bin/python /tmp/nemesis-client/cli.py' > "$ROOT/output.log" 2>&1
python3 - "$ROOT" "$MODE" <<'PY'
import pathlib,sys,re
root=pathlib.Path(sys.argv[1]);mode=sys.argv[2];output=(root/'output.log').read_text()
assert re.search(r'read_file[^\n]*✓',output),output[-5000:]
if mode=='read':
 assert 'NEMESIS_TEST_FILE_café' in output,output[-5000:]
 print('NEMESIS_REAL_FILE_READ_OK')
else:
 assert re.search(r'write_file[^\n]*✓',output),output[-5000:]
 actual=(root/'work/output.txt').read_bytes();assert actual==b'NEMESIS_WRITE_OK',repr(actual)
 print('NEMESIS_REAL_FILE_WRITE_EXACT_OK')
PY
