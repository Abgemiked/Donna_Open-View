"""Synthetic local adapters. Tests retrieval wiring, not embedding quality."""
import json
from pathlib import Path
import sqlite3
import threading
import uuid


class SyntheticMemory:
    def __init__(self, root):
        self.root = Path(root).resolve()
        self.root.mkdir(parents=True, exist_ok=True)
        self.path = self.root / "memory.sqlite"
        self.trace = []
        self.lock = threading.RLock()
        with self.connect() as db:
            db.execute("CREATE TABLE IF NOT EXISTS memory(id TEXT PRIMARY KEY,content TEXT,category TEXT,revision TEXT)")
            db.execute("CREATE TABLE IF NOT EXISTS derived(id TEXT PRIMARY KEY,content TEXT,revision TEXT)")

    def connect(self):
        return sqlite3.connect(self.path)

    def store_memory(self, session_id, content, category="user_fact", **kwargs):
        mid = uuid.uuid4().hex
        with self.lock, self.connect() as db:
            db.execute("INSERT INTO memory VALUES(?,?,?,?)", (mid, content, category, kwargs.get("revision", "seed")))
        self.trace.append("ltm_write")
        return mid

    def get_all(self, **kwargs):
        self.trace.append("ltm_read")
        with self.lock, self.connect() as db:
            return [{"id": r[0], "content": r[1], "category": r[2], "score": .99, "session_id": "synthetic"} for r in db.execute("SELECT * FROM memory ORDER BY rowid")]

    def recall_relevant(self, query, top_k=5, category=None, min_score=0, **kwargs):
        rows = self.get_all()
        rows = [r for r in rows if category is None or r["category"] == category]
        # Explicit deterministic fixture retrieval; not a replacement embedding model.
        return rows[:top_k] if min_score <= .99 else []

    def list_by_category(self, category, **kwargs):
        return self.recall_relevant("", category=category, top_k=kwargs.get("limit", 100))

    def _get_mem0(self):
        return None

    def replace_revision(self, lessons):
        """Rebuild all coaching-derived memory and index rows atomically."""
        with self.lock, self.connect() as db:
            db.execute("DELETE FROM memory WHERE revision != 'seed'")
            db.execute("DELETE FROM derived")
            for lesson in lessons:
                scope = lesson.get("scope", "synthetic-test")
                label = "kontextgebundene Tendenz, keine allgemeine Regel" if lesson["kind"] == "tendency" else "bestätigter synthetischer Fakt"
                content = f"[Geltungsbereich: {scope}; {label}] {lesson['content']}"
                db.execute("INSERT INTO memory VALUES(?,?,?,?)", (lesson["id"], content, "user_preference" if lesson["kind"] == "tendency" else "user_fact", lesson["id"]))
                db.execute("INSERT INTO derived VALUES(?,?,?)", (lesson["id"], content, lesson["id"]))


class SyntheticVector:
    def __init__(self, memory):
        self.memory = memory
        self.trace = []

    def ltm(self):
        self.trace.append("vector_ltm")
        return self

    def stm(self):
        self.trace.append("vector_stm")
        return EmptyCollection()

    def query(self, query_texts, n_results=5, **kwargs):
        rows = self.memory.recall_relevant(query_texts[0], top_k=n_results)
        return {"documents": [[r["content"] for r in rows]], "metadatas": [[{"category": r["category"]} for r in rows]]}


class EmptyCollection:
    def query(self, **kwargs):
        return {"documents": [[]], "metadatas": [[]]}


class ToolStubs:
    def __init__(self, calendar_timeout=False):
        self.trace = []
        self.context_trace = []
        self.enabled = True
        self.calendar_timeout = calendar_timeout
        self.calendar_requested = False

    def ready(self):
        return True

    async def all_states(self):
        self.context_trace.append("smart_home_context_read")
        return [{"entity_id": "light.eval_lamp", "state": "off", "attributes": {"friendly_name": "Testlampe"}}]

    async def areas(self):
        return {}

    async def cached_states(self):
        return await self.all_states()

    async def cached_area_map(self):
        return {}

    async def controllable_devices(self, **kwargs):
        return [{"entity_id": "light.eval_lamp", "name": "Testlampe", "state": "off"}]

    async def tesla_status(self, **kwargs):
        return {}

    async def call_service(self, domain, service, entity_id, data=None):
        if domain != "light" or service not in {"turn_on", "turn_off", "toggle"} or entity_id != "light.eval_lamp":
            self.trace.append("forbidden_stub_action")
            raise RuntimeError("eval_tool_denied")
        self.trace.append("home_control")
        return {"ok": True, "synthetic": True}

    def get_upcoming_events(self, **kwargs):
        (self.trace if self.calendar_requested else self.context_trace).append("calendar_read")
        if self.calendar_timeout:
            raise TimeoutError("synthetic_calendar_timeout")
        return []

    def get_context(self, **kwargs):
        (self.trace if self.calendar_requested else self.context_trace).append("calendar_read")
        if self.calendar_timeout:
            raise TimeoutError("synthetic_calendar_timeout")
        return "Synthetischer Kalender: keine Termine."

    def format_for_prompt(self, events, **kwargs):
        return "Synthetischer Kalender: keine Termine."
