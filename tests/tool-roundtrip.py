"""Live DeepSeek OpenAI tool cycle. Requires server + configured provider.
This test consumes two real generations and overwrites the Android clipboard.
"""
import json
import os
import urllib.request

url = os.environ.get("NEM_API_URL", "http://127.0.0.1:8090/v1/chat/completions")
model = os.environ.get("NEM_MODEL", "DeepSeek")
tools = [{"type": "function", "function": {"name": "read_file", "description": "Read a file by its path", "parameters": {"type": "object", "properties": {"file_path": {"type": "string"}}, "required": ["file_path"]}}}]

def send(messages, choice):
    data = json.dumps({"model": model, "messages": messages, "tools": tools, "tool_choice": choice, "stream": False}).encode()
    request = urllib.request.Request(url, data, {"Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=180) as response:
        return json.load(response)["choices"][0]

first = {"role": "user", "content": "Call the read_file tool for nem-check.txt and report its contents after receiving the result."}
result = send([first], "required")
assert result["finish_reason"] == "tool_calls", result
call = result["message"]["tool_calls"][0]
assert call["function"]["name"] == "read_file", call
assert json.loads(call["function"]["arguments"])["file_path"] == "nem-check.txt", call
reply = {"role": "tool", "tool_call_id": call["id"], "content": "NEM110_TOOL_RESULT_OK"}
final = send([first, result["message"], reply], "auto")
assert final["finish_reason"] == "stop", final
assert "NEM110_TOOL_RESULT_OK" in final["message"]["content"], final
print("TOOL_ROUNDTRIP_OK")
