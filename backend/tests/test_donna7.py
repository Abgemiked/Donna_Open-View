"""Tests für DONNA-7: Mood-Detection, Consistency-Tracking, LTM-Curation."""
from __future__ import annotations

import asyncio
from datetime import date, timedelta
from pathlib import Path

import pytest

from app.services.mood_service import MoodService, detect_mood
from app.services.consistency_service import ConsistencyService
from app.services.ltm_service import LTMService


# ---------------------------------------------------------------------------
# Mood-Detection
# ---------------------------------------------------------------------------


def test_mood_detection_frustrated() -> None:
    """'das nervt mich echt' → mood=frustrated, confidence > 0."""
    mood, confidence = detect_mood("das nervt mich echt")
    assert mood == "frustrated"
    assert confidence > 0


def test_mood_detection_happy() -> None:
    """'super, das klappt perfekt!' → mood=happy."""
    mood, confidence = detect_mood("super, das klappt perfekt!")
    assert mood == "happy"
    assert confidence > 0


def test_mood_detection_low_confidence() -> None:
    """Unklarer Text → confidence < 0.7 (kein einzelnes Keyword dominiert)."""
    # Kein echtes Mood-Keyword im Text
    mood, confidence = detect_mood("ich überlege mal was ich heute noch machen soll")
    # Entweder neutral oder confidence sehr niedrig
    assert mood == "neutral" or confidence < 0.7


def test_mood_detection_neutral() -> None:
    """Text ohne Mood-Keywords → ("neutral", 0.0)."""
    mood, confidence = detect_mood("wie spät ist es?")
    assert mood == "neutral"
    assert confidence == 0.0


def test_mood_log_and_history(tmp_path: Path) -> None:
    """Log einer Mood + Abruf über get_mood_history."""
    svc = MoodService(db_path=str(tmp_path / "mood.db"))
    log_id = svc.log_mood(
        session_id="sess1",
        mood="frustrated",
        confidence=0.8,
        text_snippet="das nervt mich echt",
    )
    assert log_id > 0

    history = svc.get_mood_history(days=7)
    assert len(history) >= 1
    assert any(h["mood"] == "frustrated" for h in history)


def test_mood_correct(tmp_path: Path) -> None:
    """Korrektur einer Mood-Erkennung."""
    svc = MoodService(db_path=str(tmp_path / "mood.db"))
    log_id = svc.log_mood("sess1", "frustrated", 0.8, "das nervt")
    success = svc.correct_mood(log_id, "happy")
    assert success is True

    history = svc.get_mood_history(days=1)
    corrected = next((h for h in history if h["id"] == log_id), None)
    assert corrected is not None
    assert corrected["corrected_mood"] == "happy"


def test_mood_correct_invalid_mood(tmp_path: Path) -> None:
    """Ungültige Mood → correct_mood gibt False zurück."""
    svc = MoodService(db_path=str(tmp_path / "mood.db"))
    log_id = svc.log_mood("sess1", "happy", 0.9, "super")
    result = svc.correct_mood(log_id, "invented_mood_xyz")
    assert result is False


# ---------------------------------------------------------------------------
# Consistency-Tracking
# ---------------------------------------------------------------------------


def test_consistency_streak(tmp_path: Path) -> None:
    """3 Tage hintereinander → streak=3."""
    svc = ConsistencyService(db_path=str(tmp_path / "cons.db"))

    # Direktes Einfügen der letzten 3 Tage (incl. heute)
    import sqlite3
    conn = sqlite3.connect(str(tmp_path / "cons.db"))
    today = date.today()
    for i in range(3):
        d = (today - timedelta(days=i)).isoformat()
        conn.execute(
            "INSERT OR REPLACE INTO usage_log (date, message_count) VALUES (?, 1)",
            (d,),
        )
    conn.commit()
    conn.close()

    assert svc.get_streak() == 3


def test_consistency_streak_gap(tmp_path: Path) -> None:
    """Lücke von 2 Tagen → streak=1 (nur heute)."""
    svc = ConsistencyService(db_path=str(tmp_path / "cons.db"))

    import sqlite3
    conn = sqlite3.connect(str(tmp_path / "cons.db"))
    today = date.today()
    # Heute + vor 3 Tagen (Lücke)
    for delta in [0, 3]:
        d = (today - timedelta(days=delta)).isoformat()
        conn.execute(
            "INSERT OR REPLACE INTO usage_log (date, message_count) VALUES (?, 1)",
            (d,),
        )
    conn.commit()
    conn.close()

    assert svc.get_streak() == 1


def test_consistency_30d(tmp_path: Path) -> None:
    """5 Tage in letzten 30 Tagen → total_30d=5."""
    svc = ConsistencyService(db_path=str(tmp_path / "cons.db"))

    import sqlite3
    conn = sqlite3.connect(str(tmp_path / "cons.db"))
    today = date.today()
    for i in [0, 5, 10, 15, 20]:
        d = (today - timedelta(days=i)).isoformat()
        conn.execute(
            "INSERT OR REPLACE INTO usage_log (date, message_count) VALUES (?, 1)",
            (d,),
        )
    # Ein Eintrag vor 35 Tagen — soll nicht mitzählen
    old = (today - timedelta(days=35)).isoformat()
    conn.execute(
        "INSERT OR REPLACE INTO usage_log (date, message_count) VALUES (?, 1)",
        (old,),
    )
    conn.commit()
    conn.close()

    assert svc.get_total_30d() == 5


def test_consistency_record_message(tmp_path: Path) -> None:
    """record_message inkrementiert today_count."""
    svc = ConsistencyService(db_path=str(tmp_path / "cons.db"))
    svc.record_message()
    svc.record_message()
    assert svc.get_today_count() == 2


def test_consistency_summary_keys(tmp_path: Path) -> None:
    """get_summary() enthält streak, total_30d, today_count."""
    svc = ConsistencyService(db_path=str(tmp_path / "cons.db"))
    summary = svc.get_summary()
    assert "streak" in summary
    assert "total_30d" in summary
    assert "today_count" in summary


# ---------------------------------------------------------------------------
# LTM-Curation (Dry-Run)
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_ltm_curation_dry_run(tmp_path: Path) -> None:
    """Dry-Run löscht nichts, loggt aber Duplikate/Low-Confidence."""
    from app.jobs.ltm_curation import run_curation

    ltm = LTMService(db_path=str(tmp_path / "ltm"))
    ltm.store_memory("sess1", "Ich mag Kaffee", "user_preference")
    ltm.store_memory("sess2", "Ich trinke gerne Kaffee", "user_preference")

    count_before = ltm._col.count()  # noqa: SLF001

    report = await run_curation(ltm, dry_run=True)

    # Dry-Run darf nichts löschen
    count_after = ltm._col.count()  # noqa: SLF001
    assert count_after == count_before

    # Report enthält expected keys
    assert "merged" in report
    assert "archived_low_confidence" in report
    assert "archived_orphan" in report
    assert report["dry_run"] is True


@pytest.mark.asyncio
async def test_ltm_curation_empty(tmp_path: Path) -> None:
    """Leere LTM → run_curation gibt Report mit 0 zurück ohne Fehler."""
    from app.jobs.ltm_curation import run_curation

    ltm = LTMService(db_path=str(tmp_path / "ltm_empty"))
    report = await run_curation(ltm, dry_run=True)

    assert report["merged"] == 0
    assert report["archived_low_confidence"] == 0
    assert report["archived_orphan"] == 0
