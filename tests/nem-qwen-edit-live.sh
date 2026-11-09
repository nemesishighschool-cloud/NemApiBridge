#!/usr/bin/env bash
# Real Qwen edit, isolated temporary file. No shell-command tool is enabled.
set -euo pipefail
BIN=${QWEN_BIN:-/tmp/nem-qwen/node_modules/.bin/qwen}
ROOT=$(mktemp -d /tmp/nem-qwen-edit.XXXXXX)
mkdir -p "$ROOT/home" "$ROOT/work"
printf 'before' > "$ROOT/work/fixture.txt"
admin(){ curl -fsS -X POST http://127.0.0.1:8090/web/nem/agent-integrations/qwen-code -H 'Content-Type: application/json' -d "$1" >/dev/null; }
trap 'admin "{\"enabled\":false}" || true' EXIT
admin '{"enabled":true,"features":{"incremental_context":true,"tool_calls":true}}'
cd "$ROOT/work"
HOME="$ROOT/home" OPENAI_BASE_URL=http://127.0.0.1:8090/v1 OPENAI_API_KEY=unused OPENAI_MODEL=deepseek-chat NO_COLOR=1 \
 "$BIN" --bare --auth-type openai --model deepseek-chat --openai-base-url http://127.0.0.1:8090/v1 --openai-api-key unused \
 --approval-mode auto-edit --core-tools read_file edit --max-tool-calls 4 --max-session-turns 6 --max-wall-time 5m --output-format json \
 'Edit fixture.txt, replacing its contents with exactly NEM_QWEN_WRITE_SUCCESS followed by one newline. Do not use a shell. Confirm when done.' > "$ROOT/result.json"
python3 - "$ROOT/result.json" "$ROOT/work/fixture.txt" <<'PY'
import json,sys
j=json.load(open(sys.argv[1]));r=next(x for x in j if x['type']=='result')
assert not r['is_error'],r
assert open(sys.argv[2],'rb').read()==b'NEM_QWEN_WRITE_SUCCESS\n'
tools=[c['name'] for x in j if x['type']=='assistant' for c in x['message']['content'] if c.get('type')=='tool_use']
assert 'edit' in tools,tools
assert 'run_shell_command' not in tools,tools
print('QWEN_REAL_FILE_EDIT_OK',tools,'turns',r['num_turns'])
PY
