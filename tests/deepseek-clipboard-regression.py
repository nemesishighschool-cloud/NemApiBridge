#!/usr/bin/env python3
"""Real DeepSeek clipboard regression. Sends a prompt in the currently open chat.

The provider must have copy_response enabled, DeepSeek must be in the foreground,
and the API client timeout must allow both generation and copying. This test
uses synthetic data, changes the chat and overwrites the Android clipboard.
A mismatch can also mean the model did not follow the requested format.
"""
import argparse
import json
import time
import urllib.error
import urllib.request

parser = argparse.ArgumentParser()
parser.add_argument('--url', default='http://127.0.0.1:8090')
parser.add_argument('--provider', default='DeepSeek')
parser.add_argument('--blocks', type=int, default=25, choices=[25, 100])
parser.add_argument('--timeout', type=int, default=900)
args = parser.parse_args()
width = 2 if args.blocks == 25 else 3
count = args.blocks
start_marker = f'BLOCK_{1:0{width}d}'
end_marker = f'BLOCK_{count:0{width}d}'
code = 'def repeated(value):\n    if value is None:\n        return None\n    return value'
prompt = (f'Generate EXACTLY {count} sections numbered {start_marker} through {end_marker} in order. '
          f'Each section must have the marker as a heading followed by a Python fenced code block with EXACTLY this code:\n{code}\n'
          f'All {count} blocks must be identical. Do not summarize, compress, skip or omit any section. No intro or conclusion.')
payload = json.dumps({'model': args.provider, 'messages': [{'role': 'user', 'content': prompt}]}).encode()
req = urllib.request.Request(args.url.rstrip('/') + '/web/chat', data=payload, headers={'Content-Type': 'application/json'})
start = time.monotonic()
try:
    with urllib.request.urlopen(req, timeout=args.timeout) as resp:
        actual = json.load(resp)['choices'][0]['message']['content']
except urllib.error.HTTPError as e:
    info = json.load(e).get('error', {})
    raise SystemExit(f"HTTP {e.code} {info.get('code')}: {info.get('message')}") from e
block = '```python\n' + code + '\n```'
expected = '\n\n'.join(f'BLOCK_{i:0{width}d}\n\n{block}' for i in range(1, count + 1))
if actual != expected:
    raise SystemExit(f'Unexpected response after {time.monotonic()-start:.1f}s; {actual.count(block)} exact blocks, '
                     f'{actual.count("```")} fences. Model formatting or copying may differ.')
print(f'OK: {count} exact code blocks, {actual.count("```")} fences, {len(actual)} characters, '
      f'{len(actual.splitlines())} lines, in {time.monotonic()-start:.1f}s.')
