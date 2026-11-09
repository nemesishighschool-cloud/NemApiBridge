#!/usr/bin/env bash
# Prerequisites: server running with DeepSeek foreground, Qwen Code 0.27.0,
# provider max_history_chars >= 20000; uses only an isolated temporary fixture.
set -euo pipefail
BIN=${QWEN_BIN:-/tmp/nem-qwen/node_modules/.bin/qwen}
ADB=${ADB:-/opt/android-sdk/platform-tools/adb}
ROOT=$(mktemp -d /tmp/nem-qwen-live.XXXXXX)
mkdir -p "$ROOT/home" "$ROOT/work" "$ROOT/logs"
printf 'NEM_QWEN_FILE_86c2\n' > "$ROOT/work/fixture.txt"
admin(){ curl -fsS -X POST http://127.0.0.1:8090/web/nem/agent-integrations/qwen-code -H 'Content-Type: application/json' -d "$1" >/dev/null; }
trap 'admin "{\"enabled\":false}" || true' EXIT
admin '{"enabled":true,"features":{"incremental_context":true,"tool_calls":true}}'
cd "$ROOT/work"
HOME="$ROOT/home" OPENAI_BASE_URL=http://127.0.0.1:8090/v1 OPENAI_API_KEY=unused OPENAI_MODEL=deepseek-chat NO_COLOR=1 \
 "$BIN" --bare --auth-type openai --model deepseek-chat --openai-base-url http://127.0.0.1:8090/v1 --openai-api-key unused \
 --approval-mode plan --max-tool-calls 2 --max-session-turns 4 --max-wall-time 5m --openai-logging --openai-logging-dir "$ROOT/logs" \
 --output-format json 'Read fixture.txt with the read_file tool and answer with its exact content only.' > "$ROOT/result.json"
python3 - "$ROOT/result.json" <<'PY'
import json,sys
j=json.load(open(sys.argv[1])); r=next(x for x in j if x['type']=='result')
assert not r['is_error'] and r['result'].strip()=='NEM_QWEN_FILE_86c2',r
assert any(x['type']=='user' and any(c.get('type')=='tool_result' and 'NEM_QWEN_FILE_86c2' in str(c.get('content')) for c in x['message']['content']) for x in j)
print('QWEN_REAL_READ_FILE_OK', 'turns',r['num_turns'])
PY
"$ADB" shell uiautomator dump /sdcard/nem-qwen-test.xml >/dev/null
"$ADB" shell cat /sdcard/nem-qwen-test.xml > "$ROOT/view.xml"
python3 - "$ROOT/logs" "$ROOT/view.xml" <<'PY'
import json,sys,glob,xml.etree.ElementTree as E
logs=[json.load(open(f)) for f in sorted(glob.glob(sys.argv[1]+'/*.json'))]
req=next(x['request'] for x in logs if x.get('request',{}).get('messages',[{}])[0].get('role')=='system')
s=req['messages'][0]['content']; nodes=[n.get('text') or '' for n in E.parse(sys.argv[2]).iter('node')]
assert any(s in t for t in nodes),'Full Qwen system prompt was not visible intact'
print('QWEN_FULL_SYSTEM_UTF8_EXACT_OK',len(s))
PY
