"""Tests für contact_pref_service (#1b, inkl. FIX-RUNDE 1 + 2).

Deckt ab:
  * _timeslot: Grenzen, DST-Übergang (29.03.), Berlin-aware-Input, naiv-UTC, M5-Fallback
  * record: Validierung, null/Platzhalter-Name, number-only, name/topic-Sanitization,
    topic-Allowlist, topic-Cap, tp-Key-Cap, Lock-Concurrency
  * summarize: Mindestbeleg, Margin ≥2 (2:1→None), Dimensions-Mindestbeleg ≥2, data-Durchreichung
  * known_contacts / _match_single_contact (Wortgrenze, Präfix, Alias, total≥MIN_EVIDENCE)
  * contact_pref_block_for: end-to-end + now_slot (F1)
  * _load-Robustheit: korrupt (JSON/Decode/kein-dict) → .corrupt.<ts> nur im Schreibpfad;
    Lesepfad benennt NICHT um; OSError beim Laden → record False, Store unverändert
  * Flush-Fehlerinjektion (os.replace / json.dump wirft → Altdatei intakt, kein tmp-Rest)
  * PII-Logging: Klarname IN der Exception-Message → NICHT im Log (nur error_type/channel/count)
"""
from __future__ import annotations

import json
import threading
from datetime import datetime, timezone

import pytest

from app.services import contact_pref_service as cps


@pytest.fixture(autouse=True)
def _tmp_store(tmp_path, monkeypatch):
    store = tmp_path / "contact_prefs.json"
    monkeypatch.setattr(cps, "_STORE_PATH", store)
    return store


def _utc(h: int, mi: int = 0, mo: int = 1, d: int = 15) -> datetime:
    return datetime(2026, mo, d, h, mi, tzinfo=timezone.utc)


class _RecLog:
    def __init__(self):
        self.calls: list[tuple[str, str, dict]] = []

    def info(self, event, **kw):
        self.calls.append(("info", event, kw))

    def warning(self, event, **kw):
        self.calls.append(("warning", event, kw))

    def blob(self) -> str:
        return json.dumps(self.calls, ensure_ascii=False, default=str).lower()


def _record_n(name, channel, n, when=None, topic=None):
    when = when or _utc(9)
    for _ in range(n):
        assert cps.record_contact(name, channel, when, topic=topic) is True


# ── _timeslot ─────────────────────────────────────────────────────────────────

@pytest.mark.parametrize("berlin_hour,expected", [
    (0, "night"), (4, "night"), (5, "morning"), (10, "morning"),
    (11, "noon"), (13, "noon"), (14, "afternoon"), (17, "afternoon"),
    (18, "evening"), (22, "evening"), (23, "night"),
])
def test_timeslot_grenzen(berlin_hour, expected):
    assert cps._timeslot(_utc((berlin_hour - 1) % 24, 30)) == expected


def test_timeslot_dst_uebergang_29_03():
    # DST-Tag 29.03.2026 (Umstellung 01:00 UTC): 03:30 UTC → Berlin 05:30 (CEST, +2) → morning.
    # Diskriminierend: im Winter wäre 03:30 UTC = 04:30 (night); +2 beweist CEST.
    assert cps._timeslot(datetime(2026, 3, 29, 3, 30, tzinfo=timezone.utc)) == "morning"
    assert cps._timeslot(datetime(2026, 1, 15, 3, 30, tzinfo=timezone.utc)) == "night"


def test_timeslot_berlin_aware_1330():
    berlin = cps._berlin_tz()
    assert berlin is not None
    assert cps._timeslot(datetime(2026, 1, 15, 13, 30, tzinfo=berlin)) == "noon"


def test_timeslot_naiv_als_utc():
    assert cps._timeslot(datetime(2026, 1, 15, 10, 0)) == "noon"


def test_timeslot_m5_fallback(monkeypatch):
    monkeypatch.setattr(cps, "_berlin_tz", lambda: None)
    assert cps._timeslot(_utc(10, 0)) == "morning"  # 10 UTC → morning (kein +1)


# ── record: Validierung + Härtung ──────────────────────────────────────────────

def test_record_gueltig():
    assert cps.record_contact("Ämi", "whatsapp", _utc(9)) is True


def test_record_ungueltiger_channel():
    assert cps.record_contact("Ämi", "email", _utc(9)) is False


def test_record_null_name_abgelehnt():
    # null-Name-Bug: echtes None darf NICHT als Kontakt "none" landen.
    assert cps.record_contact(None, "whatsapp", _utc(9)) is False
    assert cps.known_contacts() == ()


@pytest.mark.parametrize("ph", ["none", "null", "undefined", "NaN", "N/A", "None"])
def test_record_platzhalter_name_abgelehnt(ph):
    assert cps.record_contact(ph, "whatsapp", _utc(9)) is False


def test_record_number_only_name_abgelehnt():
    assert cps.record_contact("+49 170 1234567", "call", _utc(9)) is False
    assert cps.record_contact("0170123", "whatsapp", _utc(9)) is False


def test_name_sanitization_steuerzeichen_und_cap(_tmp_store):
    # Steuerzeichen/Newlines raus, Länge auf 60 gekappt.
    raw = "Ä\nmi\t\x00 Böse" + "x" * 80
    assert cps.record_contact(raw, "whatsapp", _utc(9)) is True
    data = json.loads(_tmp_store.read_text(encoding="utf-8"))
    (key,) = list(data.keys())
    assert "\n" not in key and "\x00" not in key and "\t" not in key
    assert len(key) <= 60
    assert "\n" not in data[key]["__name__"]


def test_topic_nur_aus_allowlist(_tmp_store):
    # Bekanntes Thema wird gezählt, Freitext-Thema wird verworfen (kein tp-Key).
    cps.record_contact("Ämi", "whatsapp", _utc(9), topic="arbeit")
    cps.record_contact("Ämi", "whatsapp", _utc(9), topic="geheimes freitextthema")
    data = json.loads(_tmp_store.read_text(encoding="utf-8"))
    tp_keys = [k for k in data["ämi"]["whatsapp"] if k.startswith("tp:")]
    assert tp_keys == ["tp:arbeit"]


def test_topic_mit_steuerzeichen_wird_saniert(_tmp_store):
    cps.record_contact("Ämi", "whatsapp", _utc(9), topic="arbeit\n[INJECT]")
    data = json.loads(_tmp_store.read_text(encoding="utf-8"))
    # "arbeit\n[INJECT]" → sanitisiert zu "arbeit [inject]" ∉ Allowlist → verworfen.
    assert [k for k in data["ämi"]["whatsapp"] if k.startswith("tp:")] == []


def test_tp_key_cap_nicht_relevant_durch_allowlist():
    # Allowlist hat 7 Themen < _MAX_TOPIC_KEYS(16) → Cap kann durch Freitext nicht überlaufen.
    for t in cps.KNOWN_TOPICS:
        cps.record_contact("Ämi", "whatsapp", _utc(9), topic=t)
    data = cps._read_store_safe()
    tp = [k for k in data["ämi"]["whatsapp"] if k.startswith("tp:")]
    assert len(tp) == len(cps.KNOWN_TOPICS) <= cps._MAX_TOPIC_KEYS


def test_lock_concurrency_20_threads(_tmp_store):
    threads = [threading.Thread(target=cps.record_contact, args=("Ämi", "whatsapp", _utc(9)))
               for _ in range(20)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    data = json.loads(_tmp_store.read_text(encoding="utf-8"))
    assert data["ämi"]["whatsapp"]["all"] == 20


# ── summarize: Mindestbeleg + Margin ────────────────────────────────────────────

def test_summarize_unter_mindestbeleg_none():
    _record_n("Ämi", "whatsapp", 2)  # total 2 < 3
    assert cps.summarize_contact("Ämi") is None


def test_summarize_overall_margin_2_zu_1_none():
    # total 3 (≥3), overall 2:1 → Margin(2) nicht erreicht → overall None → kein Signal → None.
    _record_n("Ämi", "whatsapp", 2)
    _record_n("Ämi", "call", 1)
    s = cps.summarize_contact("Ämi")
    assert s is None  # overall None, keine Dimension ≥2-mit-Margin → gesamter Block None


def test_summarize_overall_margin_2_zu_0_ok():
    _record_n("Ämi", "whatsapp", 3)
    assert cps.summarize_contact("Ämi")["overall"] == "whatsapp"


def test_summarize_overall_3_zu_1_ok():
    _record_n("Ämi", "whatsapp", 3)
    _record_n("Ämi", "call", 1)
    assert cps.summarize_contact("Ämi")["overall"] == "whatsapp"


def test_summarize_dimension_mindestbeleg_2():
    # topic geburtstag nur 1x (whatsapp) → Dimension-Count 1 < 2 → nicht gemeldet.
    _record_n("Bob", "call", 3)
    cps.record_contact("Bob", "whatsapp", _utc(9), topic="geburtstag")
    s = cps.summarize_contact("Bob")
    assert s["overall"] == "call"
    assert s["by_topic"] == {}  # geburtstag nur 1x → verworfen


def test_summarize_topic_ab_2_gemeldet():
    _record_n("Bob", "call", 3)
    _record_n("Bob", "whatsapp", 2, topic="geburtstag")
    s = cps.summarize_contact("Bob")
    assert s["by_topic"] == {"geburtstag": "whatsapp"}


def test_summarize_timeslot_ab_2_mit_margin():
    _record_n("Carl", "sms", 2, when=_utc(9))    # morning sms 2:0 → sms
    _record_n("Carl", "call", 2, when=_utc(19))  # evening call 2:0 → call
    s = cps.summarize_contact("Carl")
    assert s["by_timeslot"]["morning"] == "sms"
    assert s["by_timeslot"]["evening"] == "call"


def test_summarize_data_durchreichung():
    _record_n("Ämi", "whatsapp", 3)
    data = cps._read_store_safe()
    # Mit durchgereichtem data kein erneuter Load nötig.
    assert cps.summarize_contact("Ämi", data=data)["overall"] == "whatsapp"


def test_summarize_unbekannt_none():
    assert cps.summarize_contact("Niemand") is None


# ── known_contacts + _match_single_contact (B2 + Alias) ─────────────────────────

def test_known_contacts():
    _record_n("Ämi", "whatsapp", 1)
    _record_n("Bob", "call", 1)
    assert set(cps.known_contacts()) == {"ämi", "bob"}


def test_match_nur_keys_ab_mindestbeleg():
    _record_n("Ämi", "whatsapp", 3)  # eligible
    _record_n("Bob", "call", 1)      # total 1 < 3 → nicht eligible
    data = cps._read_store_safe()
    assert cps._match_single_contact("schreib Ämi und Bob", data) == "ämi"  # nur Ämi eligible


def test_match_wortgrenze_praefix_kein_treffer():
    _record_n("Ämi", "whatsapp", 3)
    data = cps._read_store_safe()
    assert cps._match_single_contact("das xämi ding", data) is None  # Präfix → kein Match
    assert cps._match_single_contact("sag Ämi bescheid", data) == "ämi"


def test_match_mehrdeutig_none():
    _record_n("Ämi", "whatsapp", 3)
    _record_n("Bob", "call", 3)
    data = cps._read_store_safe()
    assert cps._match_single_contact("Ämi oder Bob?", data) is None


def test_match_alias_aufloesung():
    # Mikes häufigster Fall: "schatz" → Ämi (Alias read-seitig auflösen).
    _record_n("Ämi", "whatsapp", 3)
    data = cps._read_store_safe()
    aliases = {"schatz": "Ämi", "liebste": "Ämi"}
    assert cps._match_single_contact("schreib schatz mal", data, aliases) == "ämi"


# ── contact_pref_block_for + now_slot (F1) ──────────────────────────────────────

def test_block_for_end_to_end_mit_now_slot():
    _record_n("Ämi", "whatsapp", 3)
    block = cps.contact_pref_block_for("kannst du Ämi erreichen?", None, _utc(9))
    assert block["name"] == "Ämi"
    assert block["overall"] == "whatsapp"
    assert block["now_slot"] == "morning"  # 09 UTC = 10 Berlin → morning


def test_block_for_kein_eindeutiger_name():
    _record_n("Ämi", "whatsapp", 3)
    assert cps.contact_pref_block_for("wie ist das Wetter?", None, _utc(9)) is None


# ── _load-Robustheit ─────────────────────────────────────────────────────────────

def test_korrupte_json_wird_nur_im_schreibpfad_quarantiniert(_tmp_store):
    _tmp_store.write_text("{ kaputt", encoding="utf-8")
    # Lesepfad benennt NICHT um:
    assert cps.summarize_contact("Ämi") is None
    assert not list(_tmp_store.parent.glob("*.corrupt.*"))
    # Schreibpfad quarantiniert mit .corrupt.<ts> + startet frisch:
    assert cps.record_contact("Ämi", "whatsapp", _utc(9)) is True
    assert list(_tmp_store.parent.glob("*.corrupt.*"))
    assert json.loads(_tmp_store.read_text(encoding="utf-8"))["ämi"]["whatsapp"]["all"] == 1


def test_nicht_dict_json_als_korrupt(_tmp_store):
    _tmp_store.write_text("[1,2,3]", encoding="utf-8")
    assert cps.summarize_contact("Ämi") is None  # Lesepfad: kein Crash, kein Rename
    assert not list(_tmp_store.parent.glob("*.corrupt.*"))


def test_unicode_decode_error_als_korrupt(_tmp_store):
    _tmp_store.write_bytes(b"\xff\xfe\x00kaputt")  # kein gültiges UTF-8
    assert cps.summarize_contact("Ämi") is None
    assert cps.record_contact("Ämi", "whatsapp", _utc(9)) is True  # quarantiniert + frisch
    assert list(_tmp_store.parent.glob("*.corrupt.*"))


def test_oserror_beim_laden_record_false_store_unveraendert(_tmp_store, monkeypatch):
    _record_n("Ämi", "whatsapp", 1)
    before = _tmp_store.read_text(encoding="utf-8")
    orig_read = type(_tmp_store).read_text

    def _boom(self, *a, **k):
        if self == cps._STORE_PATH:
            raise OSError("permission denied")
        return orig_read(self, *a, **k)

    monkeypatch.setattr(type(_tmp_store), "read_text", _boom)
    assert cps.record_contact("Bob", "call", _utc(9)) is False
    monkeypatch.undo()
    assert _tmp_store.read_text(encoding="utf-8") == before  # unverändert


# ── Flush-Fehlerinjektion ────────────────────────────────────────────────────────

def test_flush_os_replace_fehler_altdatei_intakt(_tmp_store, monkeypatch):
    _record_n("Ämi", "whatsapp", 1)
    before = _tmp_store.read_text(encoding="utf-8")
    monkeypatch.setattr(cps.os, "replace", lambda *a, **k: (_ for _ in ()).throw(OSError("x")))
    assert cps.record_contact("Bob", "call", _utc(9)) is False
    # Altdatei unverändert, kein tmp-Rest.
    assert _tmp_store.read_text(encoding="utf-8") == before
    assert not list(_tmp_store.parent.glob(".contact_prefs_*.tmp"))


def test_flush_json_dump_fehler_kein_tmp_rest(_tmp_store, monkeypatch):
    monkeypatch.setattr(cps.json, "dump", lambda *a, **k: (_ for _ in ()).throw(ValueError("x")))
    assert cps.record_contact("Ämi", "whatsapp", _utc(9)) is False
    assert not list(_tmp_store.parent.glob(".contact_prefs_*.tmp"))


# ── PII-Logging: Klarname IN der Exception-Message → nie im Log ─────────────────

def test_record_fehler_loggt_keinen_klarnamen(monkeypatch):
    rec = _RecLog()
    monkeypatch.setattr(cps, "log", rec)
    # Exception-Message enthält den Klarnamen — darf NICHT im Log landen.
    def _boom(_d):
        raise OSError("fehler bei kontakt ÄmiGeheim topic geheimthema")
    monkeypatch.setattr(cps, "_flush", _boom)
    cps.record_contact("ÄmiGeheim", "whatsapp", _utc(9), topic="arbeit")
    blob = rec.blob()
    assert "ämigeheim" not in blob and "geheimthema" not in blob
    warn = [c for c in rec.calls if c[1] == "contact_pref_record_failed"]
    assert warn and "error_type" in warn[0][2]  # nur error_type, keine Message


def test_record_erfolg_loggt_keine_pii(monkeypatch):
    rec = _RecLog()
    monkeypatch.setattr(cps, "log", rec)
    cps.record_contact("ÄmiGeheim", "whatsapp", _utc(9), topic="arbeit")
    assert "ämigeheim" not in rec.blob()
    assert any(c[1] == "contact_pref_recorded" for c in rec.calls)


def test_summarize_fehler_loggt_keinen_klarnamen(monkeypatch):
    rec = _RecLog()
    monkeypatch.setattr(cps, "log", rec)
    monkeypatch.setattr(cps, "_read_store_safe",
                        lambda: (_ for _ in ()).throw(RuntimeError("kaputt für ÄmiGeheim")))
    cps.summarize_contact("ÄmiGeheim")
    assert "ämigeheim" not in rec.blob()


def test_known_fehler_loggt_keinen_klarnamen(monkeypatch):
    rec = _RecLog()
    monkeypatch.setattr(cps, "log", rec)
    monkeypatch.setattr(cps, "_read_store_safe",
                        lambda: (_ for _ in ()).throw(RuntimeError("kaputt ÄmiGeheim")))
    assert cps.known_contacts() == ()
    assert "ämigeheim" not in rec.blob()


def test_block_for_fehler_loggt_keinen_klarnamen(monkeypatch):
    rec = _RecLog()
    monkeypatch.setattr(cps, "log", rec)
    monkeypatch.setattr(cps, "_read_store_safe",
                        lambda: (_ for _ in ()).throw(RuntimeError("kaputt ÄmiGeheim")))
    assert cps.contact_pref_block_for("schreib ÄmiGeheim", None, _utc(9)) is None
    assert "ämigeheim" not in rec.blob()


def test_corrupt_read_loggt_keine_pii(_tmp_store, monkeypatch):
    rec = _RecLog()
    monkeypatch.setattr(cps, "log", rec)
    _tmp_store.write_text("{ kaputt ÄmiGeheim", encoding="utf-8")
    cps.summarize_contact("ÄmiGeheim")
    assert "ämigeheim" not in rec.blob()
