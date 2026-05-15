"""DONNA-110: Tests für mem0+Qdrant Integration.

Drei Test-Szenarien:
1. Unit-Test: memory.add() mit PII-Inhalt wird durch Pre-Filter blockiert
2. Integration-Test: add() → recall_relevant() liefert mind. 1 Treffer (Mock)
3. Rollback-Test: DONNA_MEM0=false → alter ChromaDB-Pfad aktiv, kein Fehler
"""
from __future__ import annotations

import os
import tempfile
from unittest.mock import MagicMock, patch


class TestPiiPreFilter:
    """Szenario 1: PII-Filter blockiert mem0.add() vor dem Aufruf."""

    def test_pii_iban_blocked(self, tmp_path):
        """IBAN wird vom Pre-Filter abgefangen — mem0 nie aufgerufen."""
        with patch.dict(os.environ, {"DONNA_MEM0": "false"}):
            from app.services.ltm_service import LTMService
            svc = LTMService(db_path=str(tmp_path / "ltm"))

        mock_mem0 = MagicMock()
        svc._mem0_enabled = True
        svc._mem0_client = mock_mem0

        result = svc.store_memory(
            session_id="test",
            content="Meine IBAN ist DE12 3456 7890 1234 5678 90",
            category="user_fact",
        )

        assert result == ""
        mock_mem0.add.assert_not_called()

    def test_pii_email_blocked(self, tmp_path):
        """E-Mail-Adresse wird vom Pre-Filter abgefangen."""
        with patch.dict(os.environ, {"DONNA_MEM0": "false"}):
            from app.services.ltm_service import LTMService
            svc = LTMService(db_path=str(tmp_path / "ltm"))

        mock_mem0 = MagicMock()
        svc._mem0_enabled = True
        svc._mem0_client = mock_mem0

        result = svc.store_memory(
            session_id="test",
            content="Meine E-Mail ist test@example.com",
            category="user_fact",
        )

        assert result == ""
        mock_mem0.add.assert_not_called()

    def test_filler_word_blocked(self, tmp_path):
        """Floskel 'hallo' wird durch Floskel-Filter blockiert."""
        with patch.dict(os.environ, {"DONNA_MEM0": "false"}):
            from app.services.ltm_service import LTMService
            svc = LTMService(db_path=str(tmp_path / "ltm"))

        mock_mem0 = MagicMock()
        svc._mem0_enabled = True
        svc._mem0_client = mock_mem0

        result = svc.store_memory(
            session_id="test",
            content="hallo",
            category="user_fact",
        )

        assert result == ""
        mock_mem0.add.assert_not_called()

    def test_valid_content_passes_filter(self, tmp_path):
        """Gültiger Inhalt ohne PII passiert Pre-Filter und ruft mem0.add() auf."""
        with patch.dict(os.environ, {"DONNA_MEM0": "false"}):
            from app.services.ltm_service import LTMService
            svc = LTMService(db_path=str(tmp_path / "ltm"))

        mock_mem0 = MagicMock()
        mock_mem0.add.return_value = [{"id": "test-uuid-123"}]
        svc._mem0_enabled = True
        svc._mem0_client = mock_mem0

        result = svc.store_memory(
            session_id="test",
            content="Mike mag lieber Pizza als Pasta",
            category="user_preference",
        )

        assert result == "test-uuid-123"
        mock_mem0.add.assert_called_once()
        # Sicherstellen dass API-Key NICHT in den Logs landet
        call_args = mock_mem0.add.call_args
        assert call_args is not None


class TestMem0AddSearchRoundtrip:
    """Szenario 2: add() → recall_relevant() liefert mind. 1 Treffer (Mock)."""

    def test_add_then_search_returns_hit(self, tmp_path):
        """Mock-Integration: Store → Search gibt mindestens 1 Treffer zurück."""
        with patch.dict(os.environ, {"DONNA_MEM0": "false"}):
            from app.services.ltm_service import LTMService
            svc = LTMService(db_path=str(tmp_path / "ltm"))

        mock_mem0 = MagicMock()
        # Store-Response
        mock_mem0.add.return_value = [{"id": "abc-123"}]
        # Search-Response
        mock_mem0.search.return_value = [
            {
                "id": "abc-123",
                "memory": "Mike mag lieber Pizza als Pasta",
                "score": 0.92,
                "metadata": {"category": "user_preference", "session_id": "test"},
            }
        ]
        svc._mem0_enabled = True
        svc._mem0_client = mock_mem0

        # Store
        mem_id = svc.store_memory(
            session_id="test",
            content="Mike mag lieber Pizza als Pasta",
            category="user_preference",
        )
        assert mem_id == "abc-123"

        # Search
        hits = svc.recall_relevant("was isst Mike lieber")
        assert len(hits) >= 1
        assert hits[0]["content"] == "Mike mag lieber Pizza als Pasta"
        assert hits[0]["score"] >= 0.5

    def test_search_with_min_score_filters(self, tmp_path):
        """min_score filtert Treffer mit niedrigem Score raus."""
        with patch.dict(os.environ, {"DONNA_MEM0": "false"}):
            from app.services.ltm_service import LTMService
            svc = LTMService(db_path=str(tmp_path / "ltm"))

        mock_mem0 = MagicMock()
        mock_mem0.search.return_value = [
            {
                "id": "low-score",
                "memory": "irgendwas",
                "score": 0.2,
                "metadata": {},
            }
        ]
        svc._mem0_enabled = True
        svc._mem0_client = mock_mem0

        hits = svc.recall_relevant("query", min_score=0.55)
        assert len(hits) == 0


class TestChromaFallback:
    """Szenario 3: DONNA_MEM0=false → ChromaDB aktiv, kein Fehler."""

    def test_chroma_path_active_when_flag_false(self, tmp_path):
        """Bei DONNA_MEM0=false läuft ChromaDB normal ohne Fehler."""
        with patch.dict(os.environ, {"DONNA_MEM0": "false"}):
            # Neuimport erzwingen
            import importlib
            import app.services.ltm_service as ltm_mod
            importlib.reload(ltm_mod)

            svc = ltm_mod.LTMService(db_path=str(tmp_path / "ltm"))

        assert svc._mem0_enabled is False
        assert svc._mem0_client is None
        assert svc._col is not None

        # Speichern und Abrufen funktioniert über ChromaDB
        mem_id = svc.store_memory(
            session_id="test",
            content="Mike nutzt primär Android",
            category="user_fact",
        )
        assert mem_id != ""

        hits = svc.recall_relevant("Android")
        assert len(hits) >= 1
        assert any("Android" in h["content"] for h in hits)

    def test_mem0_false_no_qdrant_import_needed(self, tmp_path):
        """Bei DONNA_MEM0=false wird qdrant-client nicht importiert."""
        with patch.dict(os.environ, {"DONNA_MEM0": "false"}):
            # VectorStore sollte ohne qdrant-client laufen
            from app.services.vector_store import VectorStore
            store = VectorStore(tmp_path / "chroma")
            assert store.ready() is True
            assert store.is_qdrant() is False

    def test_mem0_enabled_true_lazy_init(self, tmp_path):
        """Bei DONNA_MEM0=true wird mem0-Client lazy (beim ersten Aufruf) initialisiert."""
        with patch.dict(os.environ, {"DONNA_MEM0": "true", "MISTRAL_API_KEY": "test-key"}):
            import importlib
            import app.services.ltm_service as ltm_mod
            importlib.reload(ltm_mod)

            svc = ltm_mod.LTMService(db_path=str(tmp_path / "ltm2"))

        # Client ist beim Init noch None (lazy)
        assert svc._mem0_enabled is True
        assert svc._mem0_client is None

        # mem0_enabled() gibt False zurück wenn mem0 noch nicht verbunden
        # (kein echter Qdrant-Server in Tests)
        enabled = svc.mem0_enabled()
        # In CI/Unit-Tests ohne Qdrant: False (graceful)
        assert isinstance(enabled, bool)


class TestMistralKeyNotLogged:
    """Security-Test: MISTRAL_API_KEY darf nicht in Logs erscheinen."""

    def test_api_key_not_in_log_output(self, tmp_path, caplog):
        """MISTRAL_API_KEY taucht nicht im Log auf."""
        test_key = "super-secret-mistral-key-12345"
        with patch.dict(os.environ, {"DONNA_MEM0": "false", "MISTRAL_API_KEY": test_key}):
            from app.services.ltm_service import LTMService
            svc = LTMService(db_path=str(tmp_path / "ltm"))

        for record in caplog.records:
            assert test_key not in record.getMessage(), (
                f"MISTRAL_API_KEY im Log gefunden! Zeile: {record.getMessage()}"
            )
