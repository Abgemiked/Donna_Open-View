"""Tests für LTMService (Phase 6 — Long-Term Memory)."""
import pytest
from pathlib import Path
from app.services.ltm_service import LTMService, _contains_pii


@pytest.fixture
def ltm(tmp_path: Path) -> LTMService:
    return LTMService(db_path=str(tmp_path / "ltm_test"))


def test_store_and_recall(ltm: LTMService) -> None:
    """Gespeicherter Fakt wird bei semantisch ähnlicher Anfrage zurückgegeben."""
    ltm.store_memory("sess1", "Ich mag Kaffee sehr gerne", "user_preference")
    results = ltm.recall_relevant("Was trinkt der Nutzer am liebsten?", top_k=5)
    assert len(results) >= 1
    assert any("Kaffee" in r["content"] for r in results)


def test_deduplication(ltm: LTMService) -> None:
    """Identischer Inhalt wird nicht doppelt gespeichert."""
    ltm.store_memory("sess1", "Ich wohne in YOUR_HOME_CITY", "user_fact")
    ltm.store_memory("sess1", "Ich wohne in YOUR_HOME_CITY", "user_fact")
    all_memories = ltm.get_all()
    your_home_city = [m for m in all_memories if "YOUR_HOME_CITY" in m["content"]]
    assert len(your_home_city) == 1


def test_category_filter(ltm: LTMService) -> None:
    """Recall filtert nach Kategorie korrekt."""
    ltm.store_memory("sess1", "Ich mag Pizza", "user_preference")
    ltm.store_memory("sess2", "Ich bin 30 Jahre alt", "user_fact")
    results = ltm.recall_relevant("Essen", top_k=5, category="user_fact")
    assert all(r["category"] == "user_fact" for r in results)


@pytest.mark.parametrize("pii_text", [
    "DE89370400440532013000",                          # IBAN (uppercase)
    "de89370400440532013000",                          # IBAN (lowercase bypass)
    "4111 1111 1111 1111",                             # Kreditkarte (formatiert)
    "4111-1111-1111-1111",                             # Kreditkarte (Bindestrich)
    "4111111111111111",                                # Kreditkarte (unformatiert)
    "mike@example.com",                                # E-Mail
    "passwort: geheim123",                             # Passwort-Hint
    "Password=abc123",                                 # Passwort-Hint (EN)
])
def test_pii_filter_blocks(pii_text: str) -> None:
    """PII-Muster werden von _contains_pii erkannt."""
    assert _contains_pii(pii_text), f"Erwartet PII-Erkennung für: {pii_text!r}"


@pytest.mark.parametrize("safe_text", [
    "Ich mag Tee",
    "Mike wohnt in YOUR_HOME_CITY",
    "Lieblingsfarbe ist Blau",
])
def test_pii_filter_passes_safe(safe_text: str) -> None:
    """Normale Texte werden nicht als PII erkannt (kein False-Positive)."""
    assert not _contains_pii(safe_text), f"Unerwartete PII-Erkennung für: {safe_text!r}"


def test_pii_blocked_in_store(ltm: LTMService) -> None:
    """store_memory() speichert keine PII — gibt leeren String zurück."""
    result = ltm.store_memory("sess1", "meine IBAN: DE89370400440532013000", "user_fact")
    assert result == ""
    all_mems = ltm.get_all()
    assert not any("DE89" in m["content"] for m in all_mems)


def test_delete_memory(ltm: LTMService) -> None:
    """Gelöschte Memory ist nicht mehr abrufbar."""
    mem_id = ltm.store_memory("sess1", "Ich heiße Mike", "user_fact")
    assert ltm.delete_memory(mem_id) is True
    results = ltm.recall_relevant("Name des Nutzers", top_k=5)
    assert not any("Mike" in r["content"] for r in results)
