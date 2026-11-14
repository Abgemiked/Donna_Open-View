"""Run with python -I -S. Stdio carries local JSON IPC, never credentials."""
import asyncio
import base64
import importlib.util
import io
import json
import os
from pathlib import Path
import sys


def main():
    channel = sys.stdout
    channel.write('{"type":"stage","name":"read_init"}\n');channel.flush()
    init = json.loads(sys.stdin.readline())
    root = Path(init["root"]).resolve()
    os.chdir(root)
    sys.path.extend(init["libraries"])
    sys.path.insert(0, init["backend"])
    spec = importlib.util.spec_from_file_location("eval_guard", Path(__file__).with_name("isolation.py"))
    guard_module = importlib.util.module_from_spec(spec);spec.loader.exec_module(guard_module)
    guard = guard_module.WorkerGuard(root, init["backend"], init["libraries"])
    # Windows creates an internal loopback socket pair for its event loop.
    # Create that trusted stdlib mechanism before the no-network guard; all
    # subsequent network attempts remain forbidden, including loopback access.
    channel.write('{"type":"stage","name":"loop_create"}\n');channel.flush()
    loop = asyncio.new_event_loop()
    # Staged eval code is also an explicit trusted code-only read root.
    guard.code_roots = [guard.code, Path(__file__).resolve().parents[2]]
    guard.install()
    channel.write('{"type":"stage","name":"app_import"}\n');channel.flush()
    sys.stdout = io.StringIO()
    sys.stderr = io.StringIO()
    try:
        import app
        app.__path__.insert(0, str(Path(__file__).resolve().parents[1]))
        result = loop.run_until_complete(run(init, channel, guard))
        guard.assert_clean()
        channel.write(json.dumps({"type": "result", "data": result}) + "\n");channel.flush()
    except BaseException as exc:
        channel.write(json.dumps({"type": "invalid", "code": type(exc).__name__, "violations": guard.violations}) + "\n");channel.flush()
        return 2
    return 0


async def run(init, channel, guard):
    def stage(name):
        channel.write(json.dumps({"type":"stage","name":name})+"\n");channel.flush()
    stage("httpx_import")
    import httpx
    stage("sdk_import")
    from anthropic import AsyncAnthropic
    stage("claude_import")
    from app.services.claude_client import ClaudeClient
    stage("factory_import")
    from app.evaluation.factory import create_eval_app
    from app.evaluation.factory import synthetic_payload
    traces = []
    class IPCTransport(httpx.AsyncBaseTransport):
        async def handle_async_request(self, request):
            if str(request.url) != "https://api.anthropic.com/v1/messages":
                raise RuntimeError("endpoint_denied")
            body = synthetic_payload(json.loads(request.content))
            channel.write(json.dumps({"type": "claude", "body": body}) + "\n");channel.flush()
            row = json.loads(await asyncio.to_thread(sys.stdin.readline))
            if row.get("type") != "claude_response": raise RuntimeError("broker_denied")
            traces.append(row["metadata"])
            return httpx.Response(row["status"], content=base64.b64decode(row["body"]), headers={"content-type": "text/event-stream"}, request=request)
    claude = ClaudeClient("synthetic-worker-no-key", model=init["model"])
    http = httpx.AsyncClient(transport=IPCTransport(), trust_env=False)
    claude._client = AsyncAnthropic(api_key="synthetic-worker-no-key", http_client=http, max_retries=0)
    stage("factory_create")
    app = await create_eval_app(init["root"], claude, init.get("memory", []), init.get("lessons", []), init["model"], calendar_timeout=init.get("calendar_timeout", False))
    stage("factory_ready")
    rows = []
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://eval.invalid") as client:
        for index, message in enumerate(init["messages"]):
            stage("chat_request")
            from app.routes import chat
            app.state.eval_tools.calendar_requested = chat._is_calendar_query(message)
            call_start = len(traces)
            tool_start = len(app.state.eval_tools.trace)
            context_start = len(app.state.eval_tools.context_trace)
            response = await client.post("/chat", json={"message": message, "session_id": "synthetic-session", "client": "android", "input_mode": "text"})
            events = []
            for line in response.text.splitlines():
                if line.startswith("data:"):
                    try: events.append(json.loads(line[5:]))
                    except ValueError: raise RuntimeError("malformed_sse")
            if response.status_code != 200 or not any(e.get("type") == "done" for e in events) or any(e.get("type") == "error" for e in events):
                raise RuntimeError("incomplete_stream")
            guard.assert_clean()
            text = "".join(e.get("content", "") for e in events if e.get("type") == "delta")
            if not text.strip(): raise RuntimeError("empty_response")
            rows.append({"response": text, "events": events, "headers": dict(response.headers), "usage": dict(claude.last_usage), "provider_calls": list(traces[call_start:]), "history": await app.state.stm.get_context("synthetic-session"), "memory": app.state.ltm.get_all(), "tools": list(app.state.eval_tools.trace[tool_start:]), "context_reads": list(app.state.eval_tools.context_trace[context_start:]), "profile": app.state.eval_profile})
    await claude._client.close()
    return rows


if __name__ == "__main__":
    raise SystemExit(main())
