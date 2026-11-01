"""Phase 1b: mem0 auf Claude (LLM) + lokales fastembed-Embedding (768d) umgestellt.

Rein gemockt — KEIN echter mem0-/Qdrant-/Anthropic-Call, kein Modell-Download.
Prüft die Config von `_build_mem0_config()` / `_create_mem0_client()`, die
Härtung des Anthropic-LLM und die Hilfsfunktionen von scripts/reindex_mem0.py.
"""
from __future__ import annotations

import importlib.util
import json
import sys
import types
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import MagicMock

import pytest

import app.services.ltm_service as ls

FAKE_KEY = "sk-ant-test-not-a-real-key"
_MEM0_ENV = ("MEM0_LLM_MODEL", "MEM0_EMBED_MODEL", "MEM0_EMBED_DIMS", "FASTEMBED_CACHE_PATH")


@pytest.fixture(autouse=True)
def _clean_env(monkeypatch):
    for k in _MEM0_ENV:
        monkeypatch.delenv(k, raising=False)
    monkeypatch.setenv("ANTHROPIC_API_KEY", FAKE_KEY)
    # fastembed-Cache nicht ins echte /data schreiben
    monkeypatch.setattr(ls, "_prepare_fastembed_cache", lambda: None)


def _fake_mem0(monkeypatch, *, dims: int | None = 768, raises: Exception | None = None):
    """Registriert ein Fake-`mem0`-Modul; gibt (FakeMemory-Klasse, captured-dict) zurück."""
    captured: dict = {}
    client = MagicMock(name="mem0_client")
    client.embedding_model.config.embedding_dims = dims
    client.llm.client.messages.create = MagicMock(name="orig_create", return_value="resp")

    class FakeMemory:
        @staticmethod
        def from_config(config):
            captured["config"] = config
            if raises:
                raise raises
            return client

    mod = types.ModuleType("mem0")
    mod.Memory = FakeMemory  # type: ignore[attr-defined]
    monkeypatch.setitem(sys.modules, "mem0", mod)
    captured["client"] = client
    return captured


# ---------------------------------------------------------------------------
# Config
# ---------------------------------------------------------------------------

class TestMem0Config:
    def test_llm_is_anthropic_claude(self):
        cfg = ls._build_mem0_config(FAKE_KEY)
        llm = cfg["llm"]
        assert llm["provider"] == "anthropic"
        assert llm["config"]["model"] == "claude-haiku-5-5"
        assert llm["config"]["model"].startswith("claude")
        assert llm["config"]["api_key"] == FAKE_KEY
        # keine Sampling-Parameter an neuere Claude-Modelle senden
        assert llm["config"]["temperature"] is None

    def test_embedder_is_local_768d(self):
        cfg = ls._build_mem0_config(FAKE_KEY)
        assert cfg["embedder"]["provider"] == "fastembed"  # ONNX, lokal, kein torch
        assert cfg["embedder"]["config"]["model"] == "jinaai/jina-embeddings-v2-base-de"
        vs = cfg["vector_store"]
        assert vs["provider"] == "qdrant"
        assert vs["config"]["collection_name"] == "brain_ltm"
        assert vs["config"]["embedding_model_dims"] == 768

    def test_no_ollama_reference_in_config(self):
        blob = json.dumps(ls._build_mem0_config(FAKE_KEY)).lower()
        for needle in ("ollama", "nomic", "qwen", "11434"):
            assert needle not in blob

    def test_no_ollama_in_create_function_source(self):
        import inspect

        src = (inspect.getsource(ls._create_mem0_client) + inspect.getsource(ls._build_mem0_config)).lower()
        assert "ollama" not in src

    def test_env_overrides(self, monkeypatch):
        monkeypatch.setenv("MEM0_LLM_MODEL", "claude-sonnet-5-5")
        monkeypatch.setenv("MEM0_EMBED_MODEL", "sentence-transformers/paraphrase-multilingual-mpnet-base-v2")
        cfg = ls._build_mem0_config(FAKE_KEY)
        assert cfg["llm"]["config"]["model"] == "claude-sonnet-5-5"
        assert cfg["embedder"]["config"]["model"].endswith("paraphrase-multilingual-mpnet-base-v2")
        assert cfg["vector_store"]["config"]["embedding_model_dims"] == 768

    def test_invalid_dims_env_falls_back_to_768(self, monkeypatch):
        monkeypatch.setenv("MEM0_EMBED_DIMS", "abc")
        assert ls._build_mem0_config(FAKE_KEY)["vector_store"]["config"]["embedding_model_dims"] == 768

    def test_mem0_telemetry_disabled_by_default(self):
        import os

        assert os.environ.get("MEM0_TELEMETRY", "").lower() in ("false", "0")


# ---------------------------------------------------------------------------
# _create_mem0_client
# ---------------------------------------------------------------------------

class TestCreateMem0Client:
    def test_builds_client_from_new_config(self, monkeypatch):
        cap = _fake_mem0(monkeypatch)
        client = ls._create_mem0_client()
        assert client is cap["client"]
        cfg = cap["config"]
        assert cfg["llm"]["provider"] == "anthropic"
        assert cfg["llm"]["config"]["api_key"] == FAKE_KEY
        assert cfg["embedder"]["provider"] == "fastembed"
        assert cfg["vector_store"]["config"]["embedding_model_dims"] == 768

    def test_api_key_not_logged(self, monkeypatch, capsys):
        _fake_mem0(monkeypatch)
        ls._create_mem0_client()
        out = capsys.readouterr()
        assert FAKE_KEY not in out.out + out.err

    def test_returns_none_without_api_key(self, monkeypatch):
        cap = _fake_mem0(monkeypatch)
        monkeypatch.delenv("ANTHROPIC_API_KEY", raising=False)
        monkeypatch.setattr(ls, "_resolve_anthropic_key", lambda: "")
        assert ls._create_mem0_client() is None
        assert "config" not in cap  # mem0 wurde gar nicht erst gebaut

    def test_returns_none_on_dimension_mismatch(self, monkeypatch):
        _fake_mem0(monkeypatch, dims=384)
        assert ls._create_mem0_client() is None

    def test_returns_none_on_init_error(self, monkeypatch):
        _fake_mem0(monkeypatch, raises=RuntimeError("qdrant down"))
        assert ls._create_mem0_client() is None

    def test_llm_hardened_after_create(self, monkeypatch):
        cap = _fake_mem0(monkeypatch)
        orig_create = cap["client"].llm.client.messages.create  # MagicMock vor der Härtung
        client = ls._create_mem0_client()
        # _parse_response ersetzt, create setzt output_config-Default (Fake-Call, kein API-Zugriff)
        assert client.llm._parse_response is ls._parse_anthropic_response
        client.llm.client.messages.create(model="claude-haiku-5-5", messages=[])
        assert orig_create.call_args.kwargs["output_config"] == {"effort": "low"}


# ---------------------------------------------------------------------------
# Anthropic-LLM-Härtung
# ---------------------------------------------------------------------------

class TestAnthropicHardening:
    def test_parse_skips_thinking_block(self):
        resp = SimpleNamespace(content=[
            SimpleNamespace(type="thinking", thinking="..."),
            SimpleNamespace(type="text", text='{"facts": []}'),
        ])
        assert ls._parse_anthropic_response(resp, None) == '{"facts": []}'

    def test_parse_no_text_block_returns_empty(self):
        resp = SimpleNamespace(content=[SimpleNamespace(type="thinking", thinking="...")])
        assert ls._parse_anthropic_response(resp, None) == ""

    def test_parse_tools(self):
        resp = SimpleNamespace(content=[
            SimpleNamespace(type="thinking"),
            SimpleNamespace(type="text", text="hi"),
            SimpleNamespace(type="tool_use", name="t", input={"a": 1}),
        ])
        out = ls._parse_anthropic_response(resp, [{"name": "t"}])
        assert out == {"content": "hi", "tool_calls": [{"name": "t", "arguments": {"a": 1}}]}

    def test_create_gets_default_effort_low(self):
        orig = MagicMock(return_value="resp")
        client = SimpleNamespace(llm=SimpleNamespace(client=SimpleNamespace(messages=SimpleNamespace(create=orig))))
        ls._harden_mem0_anthropic_llm(client)
        assert client.llm.client.messages.create(model="m", messages=[]) == "resp"
        kwargs = orig.call_args.kwargs
        assert kwargs["output_config"] == {"effort": "low"}
        assert kwargs["model"] == "m"

    def test_create_respects_explicit_output_config(self):
        orig = MagicMock(return_value="resp")
        client = SimpleNamespace(llm=SimpleNamespace(client=SimpleNamespace(messages=SimpleNamespace(create=orig))))
        ls._harden_mem0_anthropic_llm(client)
        client.llm.client.messages.create(model="m", output_config={"effort": "high"})
        assert orig.call_args.kwargs["output_config"] == {"effort": "high"}

    def test_harden_never_raises(self):
        ls._harden_mem0_anthropic_llm(object())  # kein .llm
        ls._harden_mem0_anthropic_llm(SimpleNamespace(llm=SimpleNamespace()))  # kein .client


# ---------------------------------------------------------------------------
# get_all: mem0 v2 liefert ohne top_k nur 20 Treffer
# ---------------------------------------------------------------------------

class TestGetAllLimit:
    def test_get_all_passes_explicit_top_k(self, tmp_path, monkeypatch):
        monkeypatch.setattr(ls, "_DONNA_MEM0_ENABLED", False)
        svc = ls.LTMService(db_path=str(tmp_path / "ltm"))
        mock_mem0 = MagicMock()
        mock_mem0.get_all.return_value = {"results": [
            {"id": "1", "memory": "a", "metadata": {"category": "user_fact"}},
        ]}
        svc._mem0_enabled = True
        svc._mem0_client = mock_mem0
        assert len(svc.get_all()) == 1
        assert mock_mem0.get_all.call_args.kwargs["top_k"] >= 1000
        svc.count_by_user("u")
        assert mock_mem0.get_all.call_args.kwargs["top_k"] >= 1000

    def test_search_passes_top_k_not_limit(self, tmp_path, monkeypatch):
        """mem0 v2 kennt nur top_k; 'limit' würde ignoriert → immer 20 Treffer."""
        monkeypatch.setattr(ls, "_DONNA_MEM0_ENABLED", False)
        svc = ls.LTMService(db_path=str(tmp_path / "ltm"))
        mock_mem0 = MagicMock()
        mock_mem0.search.return_value = {"results": []}
        svc._mem0_enabled = True
        svc._mem0_client = mock_mem0
        svc.recall_relevant("q", top_k=3)
        kwargs = mock_mem0.search.call_args.kwargs
        assert kwargs["top_k"] == 3
        assert "limit" not in kwargs


# ---------------------------------------------------------------------------
# Failure-Cooldown für den mem0-Init
# ---------------------------------------------------------------------------

class TestInitCooldown:
    def _svc(self, tmp_path, monkeypatch):
        monkeypatch.setattr(ls, "_DONNA_MEM0_ENABLED", False)
        svc = ls.LTMService(db_path=str(tmp_path / "ltm"))
        svc._mem0_enabled = True
        return svc

    def test_failed_init_is_not_retried_during_cooldown(self, tmp_path, monkeypatch):
        svc = self._svc(tmp_path, monkeypatch)
        create = MagicMock(return_value=None)
        monkeypatch.setattr(ls, "_create_mem0_client", create)
        monkeypatch.setenv("MEM0_INIT_COOLDOWN_S", "300")
        assert svc._get_mem0() is None
        assert svc._get_mem0() is None
        assert svc.get_all() == []  # Chroma-Fallback, kein weiterer Init
        assert create.call_count == 1

    def test_retry_after_cooldown_expired_and_success_clears_failure(self, tmp_path, monkeypatch):
        svc = self._svc(tmp_path, monkeypatch)
        client = MagicMock(name="client")
        create = MagicMock(side_effect=[None, client])
        monkeypatch.setattr(ls, "_create_mem0_client", create)
        monkeypatch.setenv("MEM0_INIT_COOLDOWN_S", "300")
        assert svc._get_mem0() is None
        svc._mem0_init_failed_at -= 301  # Cooldown abgelaufen
        assert svc._get_mem0() is client
        assert svc._mem0_init_failed_at is None
        assert svc._get_mem0() is client
        assert create.call_count == 2

    def test_cooldown_zero_disables_it(self, tmp_path, monkeypatch):
        svc = self._svc(tmp_path, monkeypatch)
        create = MagicMock(return_value=None)
        monkeypatch.setattr(ls, "_create_mem0_client", create)
        monkeypatch.setenv("MEM0_INIT_COOLDOWN_S", "0")
        svc._get_mem0()
        svc._get_mem0()
        assert create.call_count == 2

    def test_cooldown_env_default_and_invalid(self, monkeypatch):
        monkeypatch.delenv("MEM0_INIT_COOLDOWN_S", raising=False)
        assert ls._mem0_init_cooldown_s() == 300.0
        monkeypatch.setenv("MEM0_INIT_COOLDOWN_S", "abc")
        assert ls._mem0_init_cooldown_s() == 300.0
        monkeypatch.setenv("MEM0_INIT_COOLDOWN_S", "-5")
        assert ls._mem0_init_cooldown_s() == 0.0

    def test_mem0_ready_never_triggers_init(self, tmp_path, monkeypatch):
        svc = self._svc(tmp_path, monkeypatch)
        create = MagicMock(return_value=MagicMock())
        monkeypatch.setattr(ls, "_create_mem0_client", create)
        assert svc.mem0_ready() is False
        create.assert_not_called()  # Health-Check darf keinen Init auslösen
        svc._get_mem0()
        assert svc.mem0_ready() is True

    def test_disabled_never_creates_client(self, tmp_path, monkeypatch):
        monkeypatch.setattr(ls, "_DONNA_MEM0_ENABLED", False)
        svc = ls.LTMService(db_path=str(tmp_path / "ltm"))
        create = MagicMock()
        monkeypatch.setattr(ls, "_create_mem0_client", create)
        assert svc._get_mem0() is None and svc.mem0_ready() is False
        create.assert_not_called()


# ---------------------------------------------------------------------------
# scripts/reindex_mem0.py (nur reine Hilfsfunktionen + Dry-Run)
# ---------------------------------------------------------------------------

class FakePoint:
    def __init__(self, pid, payload, vector=None):
        self.id, self.payload, self.vector = pid, payload, vector


class FakeQdrant:
    """Minimaler QdrantClient-Ersatz: scroll/count/collection_exists (kein Netzwerk)."""

    def __init__(self, collections: dict):
        self.collections = collections

    def collection_exists(self, name):
        return name in self.collections

    def scroll(self, collection_name, limit, offset, with_payload, with_vectors):
        pts = list(self.collections[collection_name])
        return [FakePoint(p.id, p.payload, p.vector if with_vectors else None) for p in pts], None

    def count(self, collection_name, exact=True):
        return SimpleNamespace(count=len(self.collections[collection_name]))


def _raw_doc(reindex) -> dict:
    return {
        "format": reindex.BACKUP_FORMAT,
        "meta": {"collection": "brain_ltm"},
        "qdrant": {
            "brain_ltm": {"exists": True, "count": 2, "points": [
                {"id": "q1", "payload": {"data": "Fakt eins", "created_at": "2025-01-01T00:00:00Z",
                                         "updated_at": "2025-01-02T00:00:00Z", "user_id": "mike"},
                 "vector": [0.5, 0.25]},
                {"id": "q2", "payload": {"unbekannt": 1}, "vector": None}]},
            "brain_ltm_entities": {"exists": False, "count": 0, "points": []},
        },
        "chroma": {"path": "p", "collection": "ltm_memories", "exists": True, "count": 1,
                   "records": [{"id": "c1", "document": "Chroma-Fakt", "metadata": {"category": "user_fact"}}]},
        "entries": [{"text": "Fakt eins"}],
        "discarded": [],
    }


def _canary_fakes(reindex, *, store_texts, stored_dim=768, embed_dim=768):
    client = MagicMock(name="canary_client")
    client.embedding_model.embed.return_value = [0.0] * embed_dim
    qdrant = FakeQdrant({"canary": [FakePoint(i, {"data": t}, [0.1] * stored_dim)
                                    for i, t in enumerate(store_texts)]})
    return client, qdrant


@pytest.fixture(scope="module")
def reindex():
    path = Path(__file__).resolve().parents[1] / "scripts" / "reindex_mem0.py"
    spec = importlib.util.spec_from_file_location("reindex_mem0", path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def _seed_chroma(path: Path, docs: list[tuple[str, str, str]]):
    import chromadb
    from chromadb.config import Settings

    client = chromadb.PersistentClient(path=str(path), settings=Settings(anonymized_telemetry=False))
    col = client.get_or_create_collection("ltm_memories", metadata={"hnsw:space": "l2"}, embedding_function=None)
    col.add(
        ids=[f"id{i}" for i in range(len(docs))],
        documents=[d[0] for d in docs],
        metadatas=[{"category": d[1], "session_id": d[2]} for d in docs],
        embeddings=[[0.1 * (i + 1)] * 8 for i in range(len(docs))],
    )


class TestReindexHelpers:
    def test_filter_and_dedupe(self, reindex):
        mk = reindex.make_entry
        entries = [
            mk("Mike mag Pizza", category="user_preference", source="chroma"),
            mk("  mike   MAG pizza ", source="qdrant"),            # Duplikat (Whitespace/Case)
            mk("hallo", source="chroma"),                          # Floskel
            mk("Meine E-Mail ist a@b.de", source="chroma"),         # PII
            mk("", source="chroma"),                               # leer
            mk("Mike mag Pizza", user_id="other", source="chroma"),  # anderer User -> behalten
            mk("Etwas", category="gibts_nicht"),                    # ungültige Kategorie -> user_fact
        ]
        kept, stats = reindex.filter_and_dedupe(entries)
        assert [e["text"] for e in kept] == ["Mike mag Pizza", "Mike mag Pizza", "Etwas"]
        assert kept[0]["source"] == "chroma" and kept[0]["category"] == "user_preference"
        assert kept[2]["category"] == "user_fact"
        assert stats == {"input": 7, "empty": 1, "pii": 1, "filler": 1, "duplicate": 1, "kept": 3}

    def test_filter_logs_and_collects_discarded_with_reason(self, reindex, caplog):
        import logging

        mk = reindex.make_entry
        discarded: list = []
        with caplog.at_level(logging.WARNING, logger="reindex_mem0"):
            reindex.filter_and_dedupe([
                mk("Meine E-Mail ist geheim@example.org", source="chroma", original_id="p1"),
                mk("hallo", source="qdrant", original_id="f1"),
                mk("", source="qdrant", original_id="e1"),
                mk("Fakt", original_id="k1"),
                mk("fakt", original_id="d1"),
            ], discarded)
        assert [(d["reason"], d["entry"]["original_id"]) for d in discarded] == [
            ("pii", "p1"), ("filler", "f1"), ("empty", "e1"), ("duplicate", "d1")]
        log_text = caplog.text
        for needle in ("pii", "filler", "empty", "duplicate", "p1", "f1", "e1", "d1"):
            assert needle in log_text
        assert "geheim@example.org" not in log_text  # PII darf nicht ins Log

    def test_normalize_text(self, reindex):
        assert reindex.normalize_text("  Mike   MAG\tPizza \n") == "mike mag pizza"
        assert reindex.normalize_text("Straße") == reindex.normalize_text("STRASSE")  # casefold
        assert reindex.normalize_text(None) == "" and reindex.normalize_text("") == ""

    def test_read_backup_legacy_entries_format(self, reindex, tmp_path):
        f = tmp_path / "legacy.json"
        f.write_text(json.dumps({"entries": [
            {"text": "Alt", "category": "user_habit", "session_id": "s", "user_id": "anna",
             "original_id": "7", "created_at": "2025-01-01"}]}), encoding="utf-8")
        out = reindex.read_backup(str(f))
        assert out[0]["text"] == "Alt" and out[0]["user_id"] == "anna" and out[0]["created_at"] == "2025-01-01"
        f.write_text(json.dumps([{"text": "NurListe"}]), encoding="utf-8")  # reine Liste
        assert reindex.read_backup(str(f))[0]["text"] == "NurListe"

    def test_read_backup_raw_format(self, reindex, tmp_path):
        doc = {
            "format": reindex.BACKUP_FORMAT, "meta": {"collection": "brain_ltm"},
            "chroma": {"records": [{"id": "c1", "document": "Aus Chroma", "metadata": {"category": "idea"}}]},
            "qdrant": {"brain_ltm": {"points": [
                {"id": "q1", "payload": {"data": "Aus Qdrant", "user_id": "anna",
                                         "created_at": "2025-02-02", "updated_at": "2025-03-03"}},
                {"id": "q2", "payload": {"foo": "kein Text"}}]},
                "brain_ltm_entities": {"points": [{"id": "x", "payload": {"data": "Entity"}}]}},
        }
        f = tmp_path / "raw.json"
        f.write_text(json.dumps(doc), encoding="utf-8")
        out = reindex.read_backup(str(f))
        assert [e["text"] for e in out] == ["Aus Chroma", "Aus Qdrant", ""]  # Entities-Collection nicht dabei
        q = out[1]
        assert (q["user_id"], q["created_at"], q["updated_at"]) == ("anna", "2025-02-02", "2025-03-03")
        assert out[0]["user_id"] == "mike" and out[0]["category"] == "idea"

    def test_read_chroma(self, reindex, tmp_path):
        _seed_chroma(tmp_path / "ltm", [("Mike wohnt in YOUR_HOME_CITY", "user_fact", "s1"),
                                         ("Mike streamt abends", "user_habit", "s2")])
        out = reindex.read_chroma(str(tmp_path / "ltm"), "ltm_memories", "mike")
        assert {e["text"] for e in out} == {"Mike wohnt in YOUR_HOME_CITY", "Mike streamt abends"}
        assert {e["category"] for e in out} == {"user_fact", "user_habit"}
        assert all(e["user_id"] == "mike" and e["source"] == "chroma" for e in out)

    def test_read_chroma_missing_path(self, reindex, tmp_path):
        assert reindex.read_chroma(str(tmp_path / "nope"), "ltm_memories", "mike") == []

    def test_read_chroma_takes_user_id_from_metadata(self, reindex, tmp_path):
        import chromadb
        from chromadb.config import Settings

        client = chromadb.PersistentClient(path=str(tmp_path / "ltm"), settings=Settings(anonymized_telemetry=False))
        col = client.get_or_create_collection("ltm_memories", embedding_function=None)
        col.add(ids=["a", "b"], documents=["Eins", "Zwei"], embeddings=[[0.1] * 8, [0.2] * 8],
                metadatas=[{"category": "user_fact", "user_id": "anna", "created_at": "2025-01-01"},
                           {"category": "user_fact"}])
        out = {e["text"]: e for e in reindex.read_chroma(str(tmp_path / "ltm"), "ltm_memories", "mike")}
        assert out["Eins"]["user_id"] == "anna" and out["Eins"]["created_at"] == "2025-01-01"
        assert out["Zwei"]["user_id"] == "mike"
        raw = reindex.dump_chroma_raw(str(tmp_path / "ltm"), "ltm_memories")
        assert raw["count"] == 2 and {r["id"] for r in raw["records"]} == {"a", "b"}
        assert next(r for r in raw["records"] if r["id"] == "a")["metadata"]["created_at"] == "2025-01-01"

    def test_backup_roundtrip(self, reindex, tmp_path):
        doc = _raw_doc(reindex)
        path = reindex.write_backup(doc, str(tmp_path), allow_ephemeral=True)
        assert path.exists() and not list(tmp_path.glob("*.tmp"))
        data = json.loads(path.read_text(encoding="utf-8"))
        assert data["format"] == reindex.BACKUP_FORMAT
        assert data["counts"]["qdrant:brain_ltm"] == 2 and data["counts"]["chroma"] == 1
        # Roh-Payload bleibt komplett (inkl. Zeitstempel, Vektor)
        pt = data["qdrant"]["brain_ltm"]["points"][0]
        assert pt["payload"]["created_at"] == "2025-01-01T00:00:00Z" and pt["vector"] == [0.5, 0.25]
        back = reindex.read_backup(str(path))
        assert {e["text"] for e in back} == {"Fakt eins", "Chroma-Fakt", ""}

    def test_write_backup_detects_corrupted_file(self, reindex, tmp_path, monkeypatch):
        orig = Path.read_bytes
        monkeypatch.setattr(Path, "read_bytes", lambda self: orig(self)[:-5])  # abgeschnitten
        with pytest.raises(reindex.BackupError):
            reindex.write_backup(_raw_doc(reindex), str(tmp_path), allow_ephemeral=True)

    def test_write_backup_unwritable_dir_raises(self, reindex, tmp_path):
        blocker = tmp_path / "datei"
        blocker.write_text("x")
        with pytest.raises(reindex.BackupError):
            reindex.write_backup(_raw_doc(reindex), str(blocker / "sub"), allow_ephemeral=True)

    def test_resolve_backup_dir_no_silent_temp_fallback(self, reindex, monkeypatch):
        monkeypatch.setattr(reindex.os.path, "ismount", lambda p: False)
        with pytest.raises(reindex.BackupError) as ei:
            reindex.resolve_backup_dir(None)
        assert "--backup-dir" in str(ei.value)

    def test_resolve_backup_dir_rejects_ephemeral_without_override(self, reindex, tmp_path):
        # B1: Temp-/Overlay-Pfad ohne Override → Abbruch (nicht nur Warnung)
        with pytest.raises(reindex.BackupError):
            reindex.resolve_backup_dir(str(tmp_path))

    def test_resolve_backup_dir_allows_ephemeral_with_override(self, reindex, tmp_path, caplog):
        with caplog.at_level("WARNING", logger="reindex_mem0"):
            assert reindex.resolve_backup_dir(str(tmp_path), allow_ephemeral=True) == tmp_path
        assert "persistent" in caplog.text.lower()

    def test_argparse_no_abbrev(self, reindex):
        with pytest.raises(SystemExit):
            reindex.parse_args(["--appl"])  # Abkürzung von --apply darf nicht akzeptiert werden
        assert reindex.parse_args(["--force-drop-qdrant"]).force_drop_qdrant is True

    def test_add_entries_skips_existing_and_counts_failures(self, reindex):
        client = MagicMock()
        client.add.side_effect = [None, RuntimeError("boom")]
        entries = [reindex.make_entry("A"), reindex.make_entry("B", user_id="anna", created_at="2025-01-01"),
                   reindex.make_entry("C")]
        skip = {("mike", reindex.normalize_text("A"))}
        added, skipped, failed = reindex.add_entries(client, entries, skip)
        assert (added, skipped, failed) == (1, 1, 1)
        # infer=False -> kein LLM-Call, Text unverändert; user_id aus dem Eintrag, Zeitstempel erhalten
        kwargs = client.add.call_args_list[0].kwargs
        assert kwargs["infer"] is False and kwargs["user_id"] == "anna"
        assert kwargs["metadata"]["original_created_at"] == "2025-01-01"
        assert client.add.call_args_list[0].args[0] == [{"role": "user", "content": "B"}]

    # --- B2/M2: Orphans + Race --------------------------------------------------

    def test_find_orphans(self, reindex):
        mk = reindex.make_entry
        source = [mk("Bekannt", source="chroma")]
        pts = [
            {"id": 1, "payload": {"data": "bekannt"}},                         # in Quelle (casefold) -> kein Orphan
            {"id": 2, "payload": {"data": "Nur in Qdrant"}},                   # Orphan
            {"id": 3, "payload": {"foo": "ohne Text"}},                        # Orphan (unlesbar)
            {"id": 4, "payload": {"data": "Bekannt", "user_id": "test-x"}},    # anderer User -> Orphan
        ]
        assert [o["id"] for o in reindex.find_qdrant_orphans(pts, source)] == [2, 3, 4]

    def test_fingerprint_detects_insert_update_delete(self, reindex):
        base = [{"id": 1, "payload": {"data": "a"}}, {"id": 2, "payload": {"data": "b"}}]
        fp = reindex.qdrant_fingerprint(base)
        assert fp == reindex.qdrant_fingerprint(list(reversed(base)))
        assert fp != reindex.qdrant_fingerprint(base + [{"id": 3, "payload": {}}])
        assert fp != reindex.qdrant_fingerprint(base[:1])
        assert fp != reindex.qdrant_fingerprint([base[0], {"id": 2, "payload": {"data": "b2"}}])

    def test_check_qdrant_unchanged(self, reindex):
        q = FakeQdrant({"brain_ltm": [FakePoint(1, {"data": "a"})]})
        start = {"brain_ltm": reindex.dump_qdrant_raw(q, "brain_ltm"),
                 "brain_ltm_entities": reindex.dump_qdrant_raw(q, "brain_ltm_entities")}
        assert reindex.check_qdrant_unchanged(q, start) == []
        q.collections["brain_ltm"].append(FakePoint(2, {"data": "neu"}))  # API schreibt zwischendurch
        problems = reindex.check_qdrant_unchanged(q, start)
        assert problems and "brain_ltm" in problems[0] and "1 → 2" in problems[0]

    def test_dump_qdrant_raw_keeps_all_fields(self, reindex):
        pl = {"data": "x", "hash": "h", "created_at": "c", "updated_at": "u", "user_id": "mike", "extra": [1, 2]}
        raw = reindex.dump_qdrant_raw(FakeQdrant({"brain_ltm": [FakePoint("uuid-1", pl, [0.1, 0.2])]}), "brain_ltm")
        assert raw["count"] == 1 and raw["points"][0]["payload"] == pl and raw["points"][0]["vector"] == [0.1, 0.2]
        assert reindex.dump_qdrant_raw(FakeQdrant({}), "brain_ltm")["exists"] is False

    # --- M3: Canary ------------------------------------------------------------

    def test_canary_ok_and_blocks_llm(self, reindex):
        client, qdrant = _canary_fakes(reindex, store_texts=reindex.CANARY_TEXTS)
        reindex.canary_check(client, qdrant, "canary", 768)
        assert client.add.call_count == 2
        assert all(c.kwargs["infer"] is False for c in client.add.call_args_list)
        with pytest.raises(reindex.CanaryError):  # LLM ist während des Canarys gesperrt
            client.llm.generate_response([])

    def test_canary_fails_on_changed_text(self, reindex):
        client, qdrant = _canary_fakes(reindex, store_texts=["verändert 1", reindex.CANARY_TEXTS[1]])
        with pytest.raises(reindex.CanaryError, match="verändert"):
            reindex.canary_check(client, qdrant, "canary", 768)

    def test_canary_fails_on_wrong_stored_dim(self, reindex):
        client, qdrant = _canary_fakes(reindex, store_texts=reindex.CANARY_TEXTS, stored_dim=384)
        with pytest.raises(reindex.CanaryError, match="Dim"):
            reindex.canary_check(client, qdrant, "canary", 768)

    def test_canary_fails_on_wrong_embed_dim(self, reindex):
        client, qdrant = _canary_fakes(reindex, store_texts=reindex.CANARY_TEXTS, embed_dim=384)
        with pytest.raises(reindex.CanaryError, match="probe"):
            reindex.canary_check(client, qdrant, "canary", 768)
        client.add.assert_not_called()

    def test_canary_fails_if_llm_called(self, reindex):
        client, qdrant = _canary_fakes(reindex, store_texts=reindex.CANARY_TEXTS)

        def add_calling_llm(*a, **k):
            try:
                client.llm.generate_response([])
            except reindex.CanaryError:
                pass  # mem0 würde die Exception ggf. schlucken
        client.add.side_effect = add_calling_llm
        with pytest.raises(reindex.CanaryError, match="LLM"):
            reindex.canary_check(client, qdrant, "canary", 768)

    # --- main(): Reihenfolge/Abbruchbedingungen ---------------------------------

    def test_dry_run_writes_nothing(self, reindex, tmp_path, monkeypatch):
        _seed_chroma(tmp_path / "ltm", [("Mike wohnt in YOUR_HOME_CITY", "user_fact", "s1")])
        monkeypatch.setattr(reindex, "_get_qdrant", lambda url: FakeQdrant({}))
        monkeypatch.setattr(reindex, "write_backup", lambda *a, **k: pytest.fail("Dry-Run darf nicht schreiben"))
        monkeypatch.setattr(reindex, "delete_collections", lambda *a, **k: pytest.fail("Dry-Run darf nicht löschen"))
        rc = reindex.main(["--source", "chroma", "--chroma-path", str(tmp_path / "ltm")])
        assert rc == 0

    def test_empty_source_aborts_without_delete(self, reindex, tmp_path, monkeypatch):
        monkeypatch.setattr(reindex, "_get_qdrant", lambda url: FakeQdrant({}))
        monkeypatch.setattr(reindex, "delete_collections", lambda *a, **k: pytest.fail("darf nicht löschen"))
        rc = reindex.main(["--apply", "--source", "chroma", "--chroma-path", str(tmp_path / "leer")])
        assert rc == 1

    def test_apply_aborts_when_qdrant_unreachable(self, reindex, tmp_path, monkeypatch):
        _seed_chroma(tmp_path / "ltm", [("Fakt", "user_fact", "s1")])

        def boom(url):
            raise ConnectionError("down")
        monkeypatch.setattr(reindex, "_get_qdrant", boom)
        monkeypatch.setattr(reindex, "delete_collections", lambda *a, **k: pytest.fail("darf nicht löschen"))
        assert reindex.main(["--apply", "--source", "chroma", "--chroma-path", str(tmp_path / "ltm"),
                             "--backup-dir", str(tmp_path / "bk")]) == 1

    def _apply_env(self, reindex, tmp_path, monkeypatch, qdrant_points):
        _seed_chroma(tmp_path / "ltm", [("Mike wohnt in YOUR_HOME_CITY", "user_fact", "s1")])
        q = FakeQdrant({"brain_ltm": qdrant_points})
        calls: list[str] = []
        monkeypatch.setattr(reindex, "_get_qdrant", lambda url: q)
        monkeypatch.setattr(reindex, "run_canary", lambda url: calls.append("canary"))
        monkeypatch.setattr(reindex, "delete_collections", lambda *a, **k: calls.append("delete"))
        fake_client = MagicMock()
        fake_client.get_all.return_value = {"results": [{"memory": "Mike wohnt in YOUR_HOME_CITY"}]}
        monkeypatch.setattr(reindex, "build_client", lambda *a, **k: (fake_client, {}))
        args = ["--apply", "--source", "chroma", "--chroma-path", str(tmp_path / "ltm"),
                "--allow-ephemeral-backup", "--backup-dir", str(tmp_path / "bk")]
        return q, calls, args

    def test_apply_aborts_on_qdrant_orphans_even_with_source_chroma(self, reindex, tmp_path, monkeypatch):
        q, calls, args = self._apply_env(reindex, tmp_path, monkeypatch,
                                         [FakePoint(1, {"data": "Nur in Qdrant", "user_id": "mike"})])
        assert reindex.main(args) == 1
        assert calls == []  # kein Canary, kein Löschen
        assert not (tmp_path / "bk").exists()

    def test_force_drop_qdrant_backs_up_raw_qdrant_before_delete(self, reindex, tmp_path, monkeypatch):
        q, calls, args = self._apply_env(reindex, tmp_path, monkeypatch,
                                         [FakePoint(1, {"data": "Nur in Qdrant", "user_id": "mike",
                                                        "created_at": "2025-05-05"}, [0.3, 0.4])])
        monkeypatch.setattr(reindex, "delete_collections", lambda *a, **k: calls.append(
            "delete:backup_exists=%s" % bool(list((tmp_path / "bk").glob("*.json")))))
        assert reindex.main(args + ["--force-drop-qdrant"]) == 0
        assert calls == ["canary", "delete:backup_exists=True"]
        data = json.loads(next((tmp_path / "bk").glob("*.json")).read_text(encoding="utf-8"))
        pt = data["qdrant"]["brain_ltm"]["points"][0]
        assert pt["payload"]["created_at"] == "2025-05-05" and pt["vector"] == [0.3, 0.4]
        assert data["chroma"]["count"] == 1  # Chroma roh im Backup

    def test_apply_aborts_if_qdrant_changes_before_delete(self, reindex, tmp_path, monkeypatch):
        q, calls, args = self._apply_env(reindex, tmp_path, monkeypatch,
                                         [FakePoint(1, {"data": "Mike wohnt in YOUR_HOME_CITY", "user_id": "mike"})])
        # API schreibt während des Canarys einen neuen Punkt
        monkeypatch.setattr(reindex, "run_canary", lambda url: q.collections["brain_ltm"].append(
            FakePoint(2, {"data": "Neu von API", "user_id": "mike"})))
        assert reindex.main(args) == 1
        assert calls == []  # nicht gelöscht

    def test_apply_aborts_without_persistent_backup_dir(self, reindex, tmp_path, monkeypatch):
        q, calls, args = self._apply_env(reindex, tmp_path, monkeypatch,
                                         [FakePoint(1, {"data": "Mike wohnt in YOUR_HOME_CITY", "user_id": "mike"})])
        monkeypatch.setattr(reindex.os.path, "ismount", lambda p: False)
        args = args[:-2]  # ohne --backup-dir
        assert reindex.main(args) == 1
        assert calls == []

    def test_apply_aborts_on_canary_failure(self, reindex, tmp_path, monkeypatch):
        q, calls, args = self._apply_env(reindex, tmp_path, monkeypatch,
                                         [FakePoint(1, {"data": "Mike wohnt in YOUR_HOME_CITY", "user_id": "mike"})])

        def bad_canary(url):
            raise reindex.CanaryError("kaputt")
        monkeypatch.setattr(reindex, "run_canary", bad_canary)
        assert reindex.main(args) == 1
        assert calls == []
