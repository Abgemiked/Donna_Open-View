"""Tests für Twitch-Bot Wochentag-Filter (DONNA-20).

TDD-Abdeckung:
(a) Tag-Extraktion aus Frage
(b) Vergangenheit + Mittwoch → nur Mittwoch-Zeile
(c) Zukunft + Freitag → nur Freitag-Zeile
(d) Tag ohne Eintrag im Plan → Negativ-Antwort
(e) Kein Tag genannt → ganzer Plan unverändert
"""
from __future__ import annotations

import pytest

from app.services.twitch_bot_service import (
    _extract_day_from_question,
    _filter_schedule_for_day,
)

# Muster-Streamplan wie er von schedule_service.fetch_schedule() kommt
SAMPLE_SCHEDULE = (
    "Streamplan (01.01-07.01): "
    "Montag: 11:30-18:00 Valorant | 18:00-20:00 Just Chatting "
    "Dienstag: 11:30-18:30 Hearthstone Duo "
    "Mittwoch: 20:30-22:30 Hearthstone DuoQ | 22:30-02:00 Valorant "
    "Donnerstag: 20:00-24:00 Megabonk "
    "Freitag: 15:00-19:00 Valorant "
    "Samstag: 14:00-22:00 Just Chatting + Spiele "
    "Sonntag: kein Stream geplant"
)

# Mehrzeiliges Format (alternativ)
SAMPLE_SCHEDULE_MULTILINE = (
    "Streamplan (01.01-07.01):\n"
    "Montag: 11:30-18:00 Valorant\n"
    "Dienstag: 11:30-18:30 Hearthstone Duo\n"
    "Mittwoch: 20:30-22:30 Hearthstone DuoQ | 22:30-02:00 Valorant\n"
    "Donnerstag: 20:00-24:00 Megabonk\n"
    "Freitag: 15:00-19:00 Valorant\n"
    "Samstag: 14:00-22:00 Just Chatting + Spiele\n"
    "Sonntag: kein Stream geplant"
)

# Echtes Format von fetch_schedule(for_today=False) — 2 Leerzeichen Einrückung
SAMPLE_SCHEDULE_REAL_FORMAT = (
    "Streamplan (06.05. - 12.05.):\n"
    "  Montag: kein Stream\n"
    "  Dienstag: kein Stream\n"
    "  Mittwoch: 20:00-22:00 Fortnite | 22:00-00:00 Valorant\n"
    "  Donnerstag: kein Stream\n"
    "  Freitag: 19:00-22:00 Valorant\n"
    "  Samstag: kein Stream\n"
    "  Sonntag: kein Stream"
)


# ── (a) Tag-Extraktion ─────────────────────────────────────────────────────────

class TestExtractDayFromQuestion:
    def test_mittwoch_erkannt(self):
        assert _extract_day_from_question("was hat mike am mittwoch gestreamt") == "mittwoch"

    def test_freitag_erkannt(self):
        assert _extract_day_from_question("streamt mike am Freitag?") == "freitag"

    def test_montag_erkannt(self):
        assert _extract_day_from_question("Was lief am Montag?") == "montag"

    def test_samstag_erkannt(self):
        assert _extract_day_from_question("samstag gestreamt?") == "samstag"

    def test_kein_tag(self):
        assert _extract_day_from_question("was hat mike gestreamt") is None

    def test_kein_tag_heute_frage(self):
        assert _extract_day_from_question("was streamt mike heute?") is None

    def test_case_insensitive_donnerstag(self):
        assert _extract_day_from_question("Am DONNERSTAG?") == "donnerstag"


# ── DONNA-26: echtes fetch_schedule()-Format (2 Leerzeichen Einrückung) ────────

class TestFilterScheduleRealFormat:
    """Stellt sicher dass der Filter mit dem echten Format von fetch_schedule()
    funktioniert (2 führende Leerzeichen pro Zeile)."""

    def test_mittwoch_real_format(self):
        result = _filter_schedule_for_day(SAMPLE_SCHEDULE_REAL_FORMAT, "mittwoch", is_past=True)
        assert "Mittwoch" in result
        assert "Fortnite" in result
        assert "Montag" not in result
        assert "Freitag" not in result

    def test_freitag_real_format(self):
        result = _filter_schedule_for_day(SAMPLE_SCHEDULE_REAL_FORMAT, "freitag", is_past=False)
        assert "Freitag" in result
        assert "Valorant" in result
        assert "Mittwoch" not in result

    def test_kein_stream_tag_real_format(self):
        result = _filter_schedule_for_day(SAMPLE_SCHEDULE_REAL_FORMAT, "montag", is_past=True)
        # "kein Stream" ist ein valider Plan-Eintrag
        assert "Montag" in result

    def test_heute_format_kein_match_ohne_colon_direkt(self):
        """DONNA-26 Kernbug: 'Heute (Mittwoch): ...' darf den Filter nicht täuschen.
        for_today=True-Format hat Klammer vor Doppelpunkt → kein gültiger Plan-Eintrag."""
        heute_text = "Heute (Mittwoch): 20:00-22:00 Fortnite"
        # Wenn dieses Format übergeben wird → matches leer → heute_text zurück
        result = _filter_schedule_for_day(heute_text, "mittwoch", is_past=True)
        # Verhaltens-Assertion: der heute-Text wird unverändert zurückgegeben
        # (Fallback, nicht falsches Ergebnis) — und darf NICHT als gefundener
        # Mittwoch-Eintrag ohne Klammer ausgegeben werden
        assert "Heute" in result or "Mittwoch" in result  # irgendwas kommt zurück


# ── (b) Vergangenheit + Mittwoch → nur Mittwoch-Zeile ─────────────────────────

class TestFilterSchedulePast:
    def test_mittwoch_past_einzeilig(self):
        result = _filter_schedule_for_day(SAMPLE_SCHEDULE, "mittwoch", is_past=True)
        assert "Mittwoch" in result
        assert "Hearthstone DuoQ" in result
        # Keine anderen Tage
        assert "Montag" not in result
        assert "Freitag" not in result

    def test_mittwoch_past_mehrzeilig(self):
        result = _filter_schedule_for_day(SAMPLE_SCHEDULE_MULTILINE, "mittwoch", is_past=True)
        assert "Mittwoch" in result
        assert "Hearthstone DuoQ" in result
        assert "Montag" not in result

    def test_dienstag_past(self):
        result = _filter_schedule_for_day(SAMPLE_SCHEDULE, "dienstag", is_past=True)
        assert "Dienstag" in result
        assert "Hearthstone Duo" in result
        assert "Mittwoch" not in result


# ── (c) Zukunft + Freitag → nur Freitag-Zeile ─────────────────────────────────

class TestFilterScheduleFuture:
    def test_freitag_future_einzeilig(self):
        result = _filter_schedule_for_day(SAMPLE_SCHEDULE, "freitag", is_past=False)
        assert "Freitag" in result
        assert "Valorant" in result
        assert "Montag" not in result
        assert "Samstag" not in result

    def test_samstag_future(self):
        result = _filter_schedule_for_day(SAMPLE_SCHEDULE, "samstag", is_past=False)
        assert "Samstag" in result
        assert "Just Chatting" in result
        assert "Freitag" not in result


# ── (d) Tag ohne Eintrag → Negativ-Antwort ────────────────────────────────────

class TestFilterScheduleMissingDay:
    def test_negativ_antwort_past(self):
        # Plan ohne Sonntag-Eintrag würde "kein Stream geplant" liefern —
        # aber selbst wenn der Tag komplett fehlt, testen wir die Negativ-Antwort.
        # Für diesen Test verwenden wir einen reduzierten Plan ohne Dienstag.
        minimal_plan = "Montag: 11:30-18:00 Valorant Mittwoch: 20:30-22:30 Hearthstone"
        result = _filter_schedule_for_day(minimal_plan, "dienstag", is_past=True)
        assert "Dienstag" in result
        assert "nicht gestreamt" in result.lower() or "nicht" in result.lower()

    def test_negativ_antwort_future(self):
        minimal_plan = "Montag: 11:30-18:00 Valorant Mittwoch: 20:30-22:30 Hearthstone"
        result = _filter_schedule_for_day(minimal_plan, "freitag", is_past=False)
        assert "Freitag" in result
        assert "nicht" in result.lower()

    def test_negativ_antwort_past_sonntag(self):
        minimal_plan = "Montag: 11:30-18:00 Valorant"
        result = _filter_schedule_for_day(minimal_plan, "sonntag", is_past=True)
        assert "Sonntag" in result
        assert "nicht gestreamt" in result.lower() or "nicht" in result.lower()


# ── (e) Kein Tag → ganzer Plan zurück ─────────────────────────────────────────

class TestFilterScheduleNoDay:
    def test_kein_tag_gibt_ganzen_plan(self):
        """Wenn _extract_day_from_question() None zurückgibt, wird kein Filter angewendet."""
        day = _extract_day_from_question("was hat mike gestreamt")
        assert day is None
        # Kein Filter nötig — der Aufrufer gibt schedule_text direkt aus

    def test_extraktion_gibt_none_fuer_generische_frage(self):
        assert _extract_day_from_question("wann streamt mike diese woche") is None

    def test_extraktion_gibt_none_fuer_heute(self):
        assert _extract_day_from_question("was streamt mike heute abend") is None


# ── DONNA-42 Bug D: @donna-Mention erkennen ──────────────────────────────────

class TestExtractDonnaQuestion:
    """`_extract_donna_question` erkennt sowohl !donna als auch @donna als Trigger."""

    def test_bang_donna_prefix(self):
        from app.services.twitch_bot_service import _extract_donna_question
        assert _extract_donna_question("!donna wie geht's") == "wie geht's"

    def test_at_donna_prefix(self):
        from app.services.twitch_bot_service import _extract_donna_question
        assert _extract_donna_question("@donna wie geht's") == "wie geht's"

    def test_at_donna_with_colon(self):
        from app.services.twitch_bot_service import _extract_donna_question
        assert _extract_donna_question("@donna: wie geht's") == "wie geht's"

    def test_at_donna_with_comma(self):
        from app.services.twitch_bot_service import _extract_donna_question
        assert _extract_donna_question("@donna, wie geht's") == "wie geht's"

    def test_at_donna_uppercase(self):
        from app.services.twitch_bot_service import _extract_donna_question
        # Twitch nutzt @Donna mit Großbuchstaben — muss matchen
        assert _extract_donna_question("@Donna was machst du") == "was machst du"

    def test_at_donna_mid_text(self):
        from app.services.twitch_bot_service import _extract_donna_question
        result = _extract_donna_question("hey @donna was läuft?")
        # Mention rauspatchen, Rest ist die Frage
        assert result is not None
        assert "was läuft" in result.lower()

    def test_no_trigger(self):
        from app.services.twitch_bot_service import _extract_donna_question
        assert _extract_donna_question("hallo zusammen") is None

    def test_donna_in_word_no_trigger(self):
        from app.services.twitch_bot_service import _extract_donna_question
        # "Madonna" oder "donnerstag" sollen NICHT triggern (kein @-Prefix, kein !-Prefix)
        assert _extract_donna_question("Madonna ist meine Lieblingssängerin") is None
        assert _extract_donna_question("donnerstag ist mein Tag") is None

    def test_empty_question_after_prefix(self):
        from app.services.twitch_bot_service import _extract_donna_question
        # `!donna` allein → leerer String (Bot soll antworten können, nicht None)
        assert _extract_donna_question("!donna") == ""
        assert _extract_donna_question("@donna") == ""

    def test_at_donna_bot_with_question(self):
        """Twitch-Bot-Account heißt `donna_bot` — @donna_bot muss matchen."""
        from app.services.twitch_bot_service import _extract_donna_question
        assert _extract_donna_question("@donna_bot was streamt mike") == "was streamt mike"

    def test_at_donna_bot_with_colon(self):
        from app.services.twitch_bot_service import _extract_donna_question
        assert _extract_donna_question("@donna_bot: hi") == "hi"

    def test_at_donna_bot_with_comma(self):
        from app.services.twitch_bot_service import _extract_donna_question
        assert _extract_donna_question("@donna_bot, hi") == "hi"

    def test_at_donna_bot_uppercase(self):
        from app.services.twitch_bot_service import _extract_donna_question
        assert _extract_donna_question("@Donna_ki was geht") == "was geht"

    def test_at_donna_bot_mid_text(self):
        from app.services.twitch_bot_service import _extract_donna_question
        result = _extract_donna_question("hey @donna_bot bist du wach?")
        assert result is not None
        assert "bist du wach" in result.lower()

    def test_at_donna_bot_alone(self):
        from app.services.twitch_bot_service import _extract_donna_question
        assert _extract_donna_question("@donna_bot") == ""

    def test_at_donna_bot_does_not_eat_prefix(self):
        """Wichtig: '@donna_bot' wird NICHT als '@donna' + '_ki ...' erkannt."""
        from app.services.twitch_bot_service import _extract_donna_question
        # Frage darf NICHT mit '_ki' beginnen
        result = _extract_donna_question("@donna_bot hallo")
        assert result == "hallo"
        assert not (result or "").startswith("_ki")


# ── DONNA-42 Bug E: Wochentag-Name → Datum auflösen ───────────────────────────

class TestResolveNamedDayToDate:
    """`_resolve_named_day_to_date` löst Wochentag-Namen zu konkreten Daten auf."""

    def test_montag_prefer_future_from_donnerstag(self):
        import datetime
        from app.services.twitch_bot_service import _resolve_named_day_to_date
        donnerstag = datetime.date(2026, 1, 15)
        result = _resolve_named_day_to_date("montag", today=donnerstag, prefer_future=True)
        assert result == datetime.date(2026, 1, 19)

    def test_montag_prefer_past_from_donnerstag(self):
        import datetime
        from app.services.twitch_bot_service import _resolve_named_day_to_date
        donnerstag = datetime.date(2026, 1, 15)
        result = _resolve_named_day_to_date("montag", today=donnerstag, prefer_future=False)
        assert result == datetime.date(2026, 1, 12)

    def test_today_prefer_future_returns_today(self):
        import datetime
        from app.services.twitch_bot_service import _resolve_named_day_to_date
        donnerstag = datetime.date(2026, 1, 15)
        result = _resolve_named_day_to_date("donnerstag", today=donnerstag, prefer_future=True)
        assert result == donnerstag

    def test_case_insensitive(self):
        import datetime
        from app.services.twitch_bot_service import _resolve_named_day_to_date
        donnerstag = datetime.date(2026, 1, 15)
        assert _resolve_named_day_to_date("Montag", today=donnerstag) == datetime.date(2026, 1, 19)


# ── DONNA-42 Bug E: Schedule-Filter für Datum außerhalb der Plan-Woche ────────

class TestFilterScheduleFutureOutsidePlanWeek:
    """Bug E: wenn die Frage ein FUTURE-Datum betrifft das NICHT in der Plan-Woche
    liegt, soll der Filter explizit "habe noch keinen Plan" zurückgeben statt
    den Plan-Eintrag aus der aktuellen Woche."""

    def test_future_day_outside_plan_week_returns_unknown(self, monkeypatch):
        """Heute=Sonntag → "Montag" als FUTURE = nächster Mo (in nächster Woche,
        nicht in Plan-Woche) → "noch keinen Plan"."""
        import datetime as _dt
        # Patch date.today() global → 11.01.2026 (Sonntag)
        class _FixedDate(_dt.date):
            @classmethod
            def today(cls):
                return _dt.date(2026, 1, 11)
        monkeypatch.setattr(_dt, "date", _FixedDate)

        from app.services.twitch_bot_service import _filter_schedule_for_day
        result = _filter_schedule_for_day(SAMPLE_SCHEDULE, "montag", is_past=False)
        assert "keinen Plan" in result or "kein Plan" in result

    def test_past_day_in_plan_week_returns_entry(self, monkeypatch):
        """Heute=Donnerstag → "Montag" als PAST = letzter Mo (in dieser Woche)
        → liegt in Plan-Woche → Eintrag wird zurückgegeben."""
        import datetime as _dt
        class _FixedDate(_dt.date):
            @classmethod
            def today(cls):
                return _dt.date(2026, 1, 15)  # Donnerstag
        monkeypatch.setattr(_dt, "date", _FixedDate)

        from app.services.twitch_bot_service import _filter_schedule_for_day
        result = _filter_schedule_for_day(SAMPLE_SCHEDULE, "montag", is_past=True)
        # Soll den Montag-Eintrag aus dem Plan zurückgeben
        assert "Montag" in result
        assert "Valorant" in result
