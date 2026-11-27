#!/usr/bin/env python3
"""Donna KI-Qualitätstest Part 2 — SSE-Stream-Parser"""

import json
import time
import urllib.request
import urllib.error

BASE_URL = "https://your-donna-instance.example.com/chat"
HEADERS = {
    "Authorization": "Bearer YOUR_ADMIN_TOKEN_HERE",
    "X-Test-User-Id": "test_mike",
    "Content-Type": "application/json",
}

TESTS = [
    {"id": "d1", "category": "Donna-Kontext-Check", "message": "was weißt du über meine lieblingsgenres beim zocken"},
    {"id": "d2", "category": "Donna-Kontext-Check", "message": "was waren meine ziele für diese woche"},
    {"id": "d3", "category": "Donna-Kontext-Check", "message": "was hab ich letzte woche gelernt"},
    {"id": "d4", "category": "Donna-Kontext-Check", "message": "wie siehst du mich, bin ich ein guter streamer"},
    {"id": "d5", "category": "Donna-Kontext-Check", "message": "erinner mich an das gespräch über meinen dorf vibes"},
    {"id": "m1a", "category": "Multi-Turn", "message": "so gerade hat mir jemand gesagt ich sei zu unstrukturiert im stream"},
    {"id": "m1b", "category": "Multi-Turn", "message": "ist das schlecht? oder eher mein stil"},
    {"id": "m1c", "category": "Multi-Turn", "message": "wie seh das aus von außen gesehen"},
    {"id": "m2a", "category": "Multi-Turn", "message": "android app lags wieder beim chat öffnen"},
    {"id": "m2b", "category": "Multi-Turn", "message": "wie oft macht sie das denn schon"},
    {"id": "edge1", "category": "Edge-Case", "message": "?"},
    {"id": "edge2", "category": "Edge-Case", "message": "was ist 2+2"},
    {"id": "halluz1", "category": "Halluzination-Check", "message": "wie geht es mir heute"},
    {"id": "halluz2", "category": "Halluzination-Check", "message": "was war meine stimmung gestern"},
    {"id": "halluz3", "category": "Halluzination-Check", "message": "wie schläfst du gerade"},
]


def parse_sse_response(raw_bytes):
    """Parse SSE stream and concatenate all delta content."""
    try:
        raw_text = raw_bytes.decode("utf-8", errors="replace")
    except Exception:
        raw_text = str(raw_bytes)

    full_content = []
    lines = raw_text.split("\n")
    for line in lines:
        line = line.strip()
        if line.startswith("data:"):
            data_str = line[5:].strip()
            if data_str in ("[DONE]", ""):
                continue
            try:
                obj = json.loads(data_str)
                # delta event
                if obj.get("type") == "delta" and "content" in obj:
                    full_content.append(obj["content"])
                # direct message field
                elif "message" in obj:
                    full_content.append(obj["message"])
                # response field
                elif "response" in obj:
                    full_content.append(obj["response"])
                # content field directly
                elif "content" in obj:
                    full_content.append(str(obj["content"]))
            except json.JSONDecodeError:
                # not JSON, maybe plain text
                if data_str:
                    full_content.append(data_str)
        elif line and not line.startswith("event:") and not line.startswith(":"):
            # Try plain JSON
            try:
                obj = json.loads(line)
                if "message" in obj:
                    full_content.append(obj["message"])
                elif "content" in obj:
                    full_content.append(str(obj["content"]))
                elif "response" in obj:
                    full_content.append(obj["response"])
            except Exception:
                pass

    result = "".join(full_content)
    if not result and raw_text:
        # Fallback: return raw text truncated
        result = raw_text[:500]
    return result


def run_test(test):
    body = json.dumps({"message": test["message"], "stream": False}).encode("utf-8")
    req = urllib.request.Request(BASE_URL, data=body, headers=HEADERS, method="POST")
    start = time.time()
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            status_code = resp.status
            raw = resp.read()
        latency_ms = int((time.time() - start) * 1000)
        response_text = parse_sse_response(raw)
        return {
            "id": test["id"],
            "category": test["category"],
            "message": test["message"],
            "response": response_text,
            "latency_ms": latency_ms,
            "status_code": status_code,
            "error": None,
        }
    except urllib.error.HTTPError as e:
        latency_ms = int((time.time() - start) * 1000)
        body_err = e.read().decode("utf-8", errors="replace") if e else ""
        return {
            "id": test["id"],
            "category": test["category"],
            "message": test["message"],
            "response": "",
            "latency_ms": latency_ms,
            "status_code": e.code,
            "error": f"HTTPError {e.code}: {body_err[:200]}",
        }
    except Exception as ex:
        latency_ms = int((time.time() - start) * 1000)
        return {
            "id": test["id"],
            "category": test["category"],
            "message": test["message"],
            "response": "",
            "latency_ms": latency_ms,
            "status_code": 0,
            "error": str(ex),
        }


results = []
for i, test in enumerate(TESTS):
    print(f"[{i+1}/15] Testing {test['id']}: {test['message'][:50]}...")
    result = run_test(test)
    results.append(result)
    print(f"  -> Status: {result['status_code']}, Latency: {result['latency_ms']}ms, Response: {result['response'][:80]}...")
    # Small delay between requests
    if i < len(TESTS) - 1:
        time.sleep(1)

output = {
    "test_context": "donna-assistentin-part2",
    "results": results,
}

output_path = r"C:\Tools\Assistent\test_donna_part2_results.json"
with open(output_path, "w", encoding="utf-8") as f:
    json.dump(output, f, ensure_ascii=False, indent=2)

print(f"\nResults saved to {output_path}")
print(json.dumps(output, ensure_ascii=False, indent=2))
