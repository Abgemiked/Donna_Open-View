"""Parent-side worker lifecycle. No production app is started here."""
import base64
import json
import os
from pathlib import Path
import queue
import site
import subprocess
import sys
import threading
import time
import tempfile


class InvalidRun(RuntimeError):
    pass


def validate_run_root(root, backend, libraries):
    """Reject code/dependency/production locations before mkdir or worker start."""
    root = Path(root).resolve()
    backend = Path(backend).resolve()
    protected = [backend, backend.parent, Path(__file__).resolve().parents[2],
                 Path('/data').resolve(), Path('/opt/donna').resolve(),
                 *(Path(path).resolve() for path in libraries)]
    for path in protected:
        if root == path or root in path.parents or path in root.parents:
            raise InvalidRun('unsafe_run_root')
    if root.exists() and (not root.is_dir() or any(root.iterdir())):
        raise InvalidRun('run_root_not_fresh')
    return root


def run_worker(root, messages, broker, *, backend, model, memory=(), lessons=(), timeout=90, calendar_timeout=False):
    libraries = [sys.base_prefix, *site.getsitepackages(), site.getusersitepackages()]
    libraries = list(dict.fromkeys(str(Path(p).resolve()) for p in libraries if Path(p).is_dir()))
    base = validate_run_root(root, backend, libraries)
    base.mkdir(parents=True, exist_ok=True)
    # Only a newly created temporary child is ever exposed to app code. Reusing
    # a caller's existing data directory cannot make its contents readable.
    with tempfile.TemporaryDirectory(prefix='donna-eval-worker-', dir=base) as fresh:
        return _run_fresh_worker(fresh, messages, broker, backend=backend, model=model,
                                 memory=memory, lessons=lessons, timeout=timeout,
                                 calendar_timeout=calendar_timeout, libraries=libraries)


def _run_fresh_worker(root, messages, broker, *, backend, model, memory, lessons, timeout, calendar_timeout, libraries):
    root = Path(root).resolve()
    worker = Path(__file__).with_name("worker.py")
    env = {k: os.environ[k] for k in ("SYSTEMROOT", "WINDIR") if k in os.environ}
    env.update(HOME=str(root), USERPROFILE=str(root), TMP=str(root), TEMP=str(root), DONNA_MEM0="false", DONNA_GRAPHITI_CHAT_HOOK="false")
    init = {"root": str(root), "backend": str(Path(backend).resolve()), "libraries": libraries,
            "model": model, "messages": list(messages), "memory": list(memory), "lessons": list(lessons), "calendar_timeout": calendar_timeout}
    child = subprocess.Popen([sys.executable, "-I", "-S", str(worker)], cwd=root, env=env,
                             stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                             text=True, encoding="utf-8", bufsize=1)
    inbox = queue.Queue()
    def reader():
        for line in child.stdout: inbox.put(line)
        inbox.put(None)
    threading.Thread(target=reader, daemon=True).start()
    deadline = time.monotonic() + timeout
    last_stage = "startup"
    def send(value):
        child.stdin.write(json.dumps(value, ensure_ascii=True) + "\n"); child.stdin.flush()
    try:
        send(init)
        while True:
            try: line = inbox.get(timeout=max(.001, deadline-time.monotonic()))
            except queue.Empty: raise InvalidRun("worker_timeout:"+last_stage) from None
            if line is None: raise InvalidRun("worker_exited")
            try: event = json.loads(line)
            except ValueError: raise InvalidRun("worker_protocol") from None
            if event.get("type") == "stage":
                last_stage = event["name"]
            elif event.get("type") == "claude":
                send(broker(event["body"]))
            elif event.get("type") == "result":
                return event["data"]
            else:
                raise InvalidRun("worker_invalid:" + str(event.get("code")) + ":" + ",".join(event.get("violations", [])))
    finally:
        child.stdin.close()
        if child.poll() is None: child.terminate()
        child.wait(timeout=10)
        child.stdout.close()


def synthetic_broker(text="Synthetische Testantwort."):
    """Offline SDK transport control. Does not measure model quality."""
    def respond(body):
        model = body["model"]
        events = [
            ("message_start", {"type":"message_start","message":{"id":"msg_synthetic","type":"message","role":"assistant","model":model,"content":[],"stop_reason":None,"stop_sequence":None,"usage":{"input_tokens":25,"output_tokens":0}}}),
            ("content_block_start", {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}),
            ("content_block_delta", {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":text}}),
            ("content_block_stop", {"type":"content_block_stop","index":0}),
            ("message_delta", {"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":None},"usage":{"output_tokens":12}}),
            ("message_stop", {"type":"message_stop"}),
        ]
        raw = "".join("event: "+name+"\ndata: "+json.dumps(data)+"\n\n" for name,data in events).encode()
        return {"type":"claude_response","status":200,"body":base64.b64encode(raw).decode(),"metadata":{"provider":"synthetic_control","model":model,"input_tokens":25,"output_tokens":12}}
    return respond
