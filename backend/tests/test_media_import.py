"""A3 — Tests für die Medien-Import-Skripte (Netflix-CSV + YouTube-Takeout).

TDD. Deckt die Review-Rat-Blocker ab:
  B1 CSV crasht nie (csv.Error/Überfeld/fehlende Datei → skip+log, errors++).
  B2 UTF-16-CSV (BOM + NUL-Heuristik) wird dekodiert, kein Crash.
  B3 Deutscher Takeout-Name (Wiedergabeverlauf.json) wird erkannt; HTML-only/fehlend → Exit != 0.
  B4 mem0-Pfad schreibt ROH (infer=False), NICHT über den umschreibenden store_memory-Pfad.
  B5 Idempotenz: zweiter Lauf mit gleichen Daten → stored=0, duplicate=N (persistentes Ledger).
  B6 Whitelist-Sanitize (alle Unicode-C*-Kategorien + U+2028/29 + <>, Längengrenze).
  B7 Serien-Dedup (Netflix: einmal pro Serie); YouTube-Suffix nur exakt randständig.
  Sicherheit: Path-Traversal, Zip-Bomb (Anzahl/Größe), Symlink-Eintrag, Stream-Cap bei
  lügender Directory-Größe.

Es wird NIE eine echte LTM/Qdrant/ChromaDB berührt und NIE eine echte Nutzerdatei
verarbeitet — nur synthetische Fixtures in tmp_path. LTM ist gemockt.
"""
from __future__ import annotations

import json
import zipfile
from pathlib import Path
from unittest.mock import MagicMock

import pytest
from structlog.testing import capture_logs

from app.scripts.import_netflix_csv import (
    canonical_profile,
    import_netflix_file,
    profile_from_path,
)
from app.scripts.import_youtube_takeout import (
    UnsafeArchiveError,
    import_youtube_takeout,
    main as yt_main,
    parse_watch_history,
    parse_watch_history_html,
    _watch_html_profile,
)
from app.scripts.media_import_common import (
    MEDIA_SEEN_CATEGORY,
    SeenLedger,
    build_content,
    sanitize_title,
    store_seen,
)

# Cross-Checks gegen die echten Quellen der Wahrheit (nicht nur die importierte Konstante):
from app.services.ltm_service import _VALID_CATEGORIES
from app.routes.chat import _LIVE_BLOCKED_CATEGORIES


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _mock_ltm():
    """Gemockter LTMService. _mem0_enabled ist KEIN echtes True → store_memory-Pfad."""
    ltm = MagicMock()
    ltm.store_memory.return_value = "fake-id"
    return ltm


def _stored_contents(ltm) -> list[str]:
    out = []
    for call in ltm.store_memory.call_args_list:
        if "content" in call.kwargs:
            out.append(call.kwargs["content"])
        elif len(call.args) >= 2:
            out.append(call.args[1])
    return out


def _stored_categories(ltm) -> list[str]:
    out = []
    for call in ltm.store_memory.call_args_list:
        if "category" in call.kwargs:
            out.append(call.kwargs["category"])
        elif len(call.args) >= 3:
            out.append(call.args[2])
    return out


def _fresh_ledger(tmp_path, name="ledger.json") -> SeenLedger:
    return SeenLedger(path=str(tmp_path / name))


# ---------------------------------------------------------------------------
# Kategorie: Literal + Cross-Checks
# ---------------------------------------------------------------------------

def test_category_literal_and_cross_checks():
    assert MEDIA_SEEN_CATEGORY == "media_seen"
    assert "media_seen" in _VALID_CATEGORIES          # ltm_service akzeptiert sie
    assert "media_seen" in _LIVE_BLOCKED_CATEGORIES    # chat.py blockt sie im Stream


# ---------------------------------------------------------------------------
# B6 Whitelist-Sanitize
# ---------------------------------------------------------------------------

def test_sanitize_strips_all_control_bidi_and_limits_length():
    raw = ("A\x00B\x1bC\x0bD\x0cE‮F G H\x85I<j>K")
    out = sanitize_title(raw)
    for bad in ("\x00", "\x1b", "\x0b", "\x0c", "‮", " ", " ", "\x85", "<", ">"):
        assert bad not in out
    for good in ("A", "B", "C", "D", "E", "F", "G", "H", "I", "j", "K"):
        assert good in out


def test_sanitize_length_cap():
    out = sanitize_title("x" * 5000)
    assert len(out) <= 200


def test_sanitize_through_netflix(tmp_path):
    # Marker-/Control-/Bidi-/Überlängen-Titel durch Netflix → Content sauber.
    dirty = "Evil‮\x00Title<x>" + "y" * 400
    csv = f'Title,Date\r\n"{dirty}",01.01.2024\r\n'
    p = tmp_path / "Mike_History.csv"
    p.write_bytes(csv.encode("utf-8"))
    ltm = _mock_ltm()
    import_netflix_file(str(p), profile="Mike", ltm=ltm, session_id="s",
                        ledger=_fresh_ledger(tmp_path))
    contents = _stored_contents(ltm)
    assert contents  # Schleife darf nicht leer grün laufen
    for c in contents:
        for bad in ("\x00", "‮", "<", ">"):
            assert bad not in c
        assert len(c) <= 260  # "Mike hat auf Netflix gesehen: " + <=200


def test_sanitize_through_youtube(tmp_path):
    # T1: der YouTube-Zweig (_clean_video_title) muss ebenfalls sanitizen.
    # Mutation sanitize_title(raw)->str(raw) in _clean_video_title → dieser Test wird rot.
    dirty_title = "Watched Evil‮\x00Clip<script>" + "z" * 400
    entries = [{"header": "YouTube", "title": dirty_title, "titleUrl": "x"}]
    zp = _make_zip(tmp_path, {_WATCH_ARC: _watch_entries(entries)})
    ltm = _mock_ltm()
    import_youtube_takeout(str(zp), ltm=ltm, profile="Mike", session_id="s",
                           extract_root=str(tmp_path / "iso"), ledger=_fresh_ledger(tmp_path))
    contents = _stored_contents(ltm)
    assert contents  # nicht leer grün
    for c in contents:
        for bad in ("\x00", "‮", "<", ">"):
            assert bad not in c
        assert len(c) <= 260


# ---------------------------------------------------------------------------
# Netflix: Encoding
# ---------------------------------------------------------------------------

def test_netflix_utf8_bom_umlaut_real_decode(tmp_path):
    csv = 'Title,Date\r\n"Tatort Nürnberg",16.03.2024\r\n"Dark: Staffel 1: Geheimnisse",15.03.2024\r\n'
    p = tmp_path / "Ämi_History.csv"
    p.write_bytes(csv.encode("utf-8-sig"))
    ltm = _mock_ltm()
    stats = import_netflix_file(str(p), profile=None, ltm=ltm, session_id="s",
                                ledger=_fresh_ledger(tmp_path))
    contents = _stored_contents(ltm)
    assert any("Nürnberg" in c for c in contents)   # echter Decode, nicht nur Dateiname
    assert all("Ämi" in c for c in contents)
    assert set(_stored_categories(ltm)) == {MEDIA_SEEN_CATEGORY}
    assert stats.stored == 2


def test_netflix_german_column_variants(tmp_path):
    csv = 'Titel,Datum\r\n"Haus des Geldes",01.01.2024\r\n'
    p = tmp_path / "Mike_History.csv"
    p.write_bytes(csv.encode("utf-8"))
    ltm = _mock_ltm()
    stats = import_netflix_file(str(p), profile="Mike", ltm=ltm, session_id="s",
                                ledger=_fresh_ledger(tmp_path))
    assert stats.stored == 1
    assert any("Haus des Geldes" in c for c in _stored_contents(ltm))


def test_netflix_latin1_fallback(tmp_path):
    csv = 'Title,Date\r\n"Fußball Legenden",10.10.2024\r\n'
    p = tmp_path / "Family_History.csv"
    p.write_bytes(csv.encode("latin-1"))
    ltm = _mock_ltm()
    stats = import_netflix_file(str(p), profile=None, ltm=ltm, session_id="s",
                                ledger=_fresh_ledger(tmp_path))
    assert stats.stored == 1
    assert any("Fußball Legenden" in c for c in _stored_contents(ltm))


def test_netflix_utf16_does_not_crash(tmp_path):
    # Excel "Unicode-Text"-Export (UTF-16 mit BOM) → kein Crash, Titel kommt durch.
    csv = 'Title,Date\r\n"Fußball Nürnberg",10.10.2024\r\n'
    p = tmp_path / "Mike_History.csv"
    p.write_bytes(csv.encode("utf-16"))  # BOM FF FE
    ltm = _mock_ltm()
    stats = import_netflix_file(str(p), profile="Mike", ltm=ltm, session_id="s",
                                ledger=_fresh_ledger(tmp_path))
    assert stats.errors == 0
    assert stats.stored == 1
    assert any("Nürnberg" in c for c in _stored_contents(ltm))


# ---------------------------------------------------------------------------
# B1 Netflix: crasht nie
# ---------------------------------------------------------------------------

def test_netflix_missing_title_column_skips(tmp_path):
    csv = 'Date,Something\r\n01.01.2024,foo\r\n'
    p = tmp_path / "Mike_History.csv"
    p.write_bytes(csv.encode("utf-8"))
    ltm = _mock_ltm()
    stats = import_netflix_file(str(p), profile="Mike", ltm=ltm, session_id="s",
                                ledger=_fresh_ledger(tmp_path))
    assert stats.stored == 0 and stats.errors >= 1
    ltm.store_memory.assert_not_called()


def test_netflix_missing_file_skips(tmp_path):
    ltm = _mock_ltm()
    stats = import_netflix_file(str(tmp_path / "weg.csv"), profile="Mike", ltm=ltm,
                                session_id="s", ledger=_fresh_ledger(tmp_path))
    assert stats.stored == 0 and stats.errors >= 1
    ltm.store_memory.assert_not_called()


def test_netflix_oversized_field_does_not_crash(tmp_path, monkeypatch):
    import app.scripts.import_netflix_csv as mod
    monkeypatch.setattr(mod, "_FIELD_SIZE_LIMIT", 50)
    huge = "H" * 500
    csv = f'Title,Date\r\n"{huge}",01.01.2024\r\n'
    p = tmp_path / "Mike_History.csv"
    p.write_bytes(csv.encode("utf-8"))
    ltm = _mock_ltm()
    stats = import_netflix_file(str(p), profile="Mike", ltm=ltm, session_id="s",
                                ledger=_fresh_ledger(tmp_path))
    # Kein Traceback; die kaputte Iteration wird als Fehler verbucht.
    assert stats.errors >= 1


def test_netflix_main_one_bad_file_does_not_abort_others(tmp_path, monkeypatch):
    import app.scripts.import_netflix_csv as mod
    good = tmp_path / "Mike_History.csv"
    good.write_bytes('Title,Date\r\n"Guter Film",01.01.2024\r\n'.encode("utf-8"))
    bad = tmp_path / "Ämi_History.csv"
    bad.write_bytes(b"\xff\xfe\x00\x00broken")  # Müll

    ltm = _mock_ltm()
    monkeypatch.setattr(mod, "_build_ltm", lambda: ltm)
    monkeypatch.setattr(mod, "_default_ledger_path", lambda: str(tmp_path / "l.json"))
    rc = mod.main([str(bad), str(good)])  # kaputte Datei zuerst
    # Trotz kaputter erster Datei wird die gute importiert:
    assert any("Guter Film" in c for c in _stored_contents(ltm))
    assert rc != 0  # Fehler sichtbar im Exit-Code


# ---------------------------------------------------------------------------
# B7 Netflix: Serien-Dedup
# ---------------------------------------------------------------------------

def test_netflix_series_dedup_once_per_series(tmp_path):
    csv = ('Title,Date\r\n'
           '"Dark: Staffel 1: Geheimnisse",01.01.2024\r\n'
           '"Dark: Staffel 1: Lügen",02.01.2024\r\n'
           '"Dark: Staffel 2: Zeit",03.01.2024\r\n')
    p = tmp_path / "Mike_History.csv"
    p.write_bytes(csv.encode("utf-8"))
    ltm = _mock_ltm()
    stats = import_netflix_file(str(p), profile="Mike", ltm=ltm, session_id="s",
                                ledger=_fresh_ledger(tmp_path))
    assert stats.stored == 1
    assert stats.duplicate == 2
    contents = _stored_contents(ltm)
    assert any(c.endswith("Dark") for c in contents)       # Serienname gespeichert
    assert all("Geheimnisse" not in c for c in contents)   # nicht die Episode


def test_netflix_movie_title_not_truncated(tmp_path):
    csv = 'Title,Date\r\n"Mission: Impossible",01.01.2024\r\n'
    p = tmp_path / "Mike_History.csv"
    p.write_bytes(csv.encode("utf-8"))
    ltm = _mock_ltm()
    import_netflix_file(str(p), profile="Mike", ltm=ltm, session_id="s",
                        ledger=_fresh_ledger(tmp_path))
    # Kein Staffel/Season-Marker → Doppelpunkt-Titel bleibt vollständig.
    assert any("Mission: Impossible" in c for c in _stored_contents(ltm))


def test_netflix_empty_titles_skipped(tmp_path):
    csv = 'Title,Date\r\n"",01.01.2024\r\n"   ",02.01.2024\r\n"Echt",03.01.2024\r\n'
    p = tmp_path / "Mike_History.csv"
    p.write_bytes(csv.encode("utf-8"))
    ltm = _mock_ltm()
    stats = import_netflix_file(str(p), profile="Mike", ltm=ltm, session_id="s",
                                ledger=_fresh_ledger(tmp_path))
    assert stats.stored == 1 and stats.skipped >= 2


# ---------------------------------------------------------------------------
# Netflix: Profil
# ---------------------------------------------------------------------------

@pytest.mark.parametrize("fname,expected", [
    ("Mike_History.csv", "Mike"),
    ("Ämi_History.csv", "Ämi"),
    ("Yannik_History.csv", "Yannik"),
    ("Family_History.csv", "Family"),
])
def test_profile_from_path(fname, expected):
    assert profile_from_path(fname) == expected


def test_profile_from_path_unknown_returns_none():
    assert profile_from_path("irgendwas.csv") is None


def test_canonical_profile_casing():
    assert canonical_profile("mike") == "Mike"
    assert canonical_profile("ÄMI") == "Ämi"
    assert canonical_profile("DreamDuo") == "DreamDuo"
    assert canonical_profile("Fremd") == "Fremd"   # unbekannt: unverändert (nicht raten)
    assert canonical_profile(None) is None


def test_build_content_has_profile_and_source():
    c = build_content("DreamDuo", "Netflix", "Inception")
    assert "DreamDuo" in c and "Netflix" in c and "Inception" in c


# ---------------------------------------------------------------------------
# B5 Idempotenz (persistentes Ledger)
# ---------------------------------------------------------------------------

def test_netflix_idempotent_second_run(tmp_path):
    csv = 'Title,Date\r\n"Film A",01.01.2024\r\n"Film B",02.01.2024\r\n'
    p = tmp_path / "Mike_History.csv"
    p.write_bytes(csv.encode("utf-8"))
    lpath = str(tmp_path / "ledger.json")

    ltm1 = _mock_ltm()
    s1 = import_netflix_file(str(p), profile="Mike", ltm=ltm1, session_id="s",
                             ledger=SeenLedger(path=lpath))
    assert s1.stored == 2 and s1.duplicate == 0

    # Zweiter Lauf, FRISCHES Ledger-Objekt aus derselben Datei → alles bereits vorhanden.
    ltm2 = _mock_ltm()
    s2 = import_netflix_file(str(p), profile="Mike", ltm=ltm2, session_id="s",
                             ledger=SeenLedger(path=lpath))
    assert s2.stored == 0 and s2.duplicate == 2
    ltm2.store_memory.assert_not_called()


def test_ledger_persists_on_flush(tmp_path):
    lpath = str(tmp_path / "l.json")
    led = SeenLedger(path=lpath)
    k = led.key("Mike", "Netflix", "X")
    assert not led.contains(k)
    led.add(k)
    led.flush()
    assert Path(lpath).exists()
    led2 = SeenLedger(path=lpath)
    assert led2.contains(k)


def test_ledger_add_does_not_write_per_call(tmp_path):
    # M3: add() darf NICHT pro Aufruf die ganze Datei neu schreiben (O(n²)) — nur flush().
    lpath = tmp_path / "l.json"
    led = SeenLedger(path=str(lpath))
    for i in range(5000):
        led.add(led.key("Mike", "Netflix", f"Film {i}"))
    assert not lpath.exists()          # noch nichts geschrieben → add() ist O(1)
    led.flush()
    assert lpath.exists()
    reloaded = SeenLedger(path=str(lpath))
    assert reloaded.contains(led.key("Mike", "Netflix", "Film 4999"))


def test_ledger_corrupt_file_is_backed_up(tmp_path):
    # Nebenbefund: kaputter Ledger → Warnung + .corrupt-Backup (nicht still leeren).
    lpath = tmp_path / "l.json"
    lpath.write_text("{ das ist kein JSON", encoding="utf-8")
    led = SeenLedger(path=str(lpath))
    assert not led.contains(led.key("Mike", "Netflix", "X"))   # leer gestartet
    assert (tmp_path / "l.json.corrupt").exists()              # Backup angelegt


# ---------------------------------------------------------------------------
# B4 mem0: ROH-Schreibpfad (infer=False), kein store_memory-Rewrite
# ---------------------------------------------------------------------------

class _FakeMem0Client:
    def __init__(self):
        self.add_calls = []

    def add(self, messages, user_id=None, infer=None, metadata=None):
        self.add_calls.append(dict(messages=messages, user_id=user_id,
                                   infer=infer, metadata=metadata))
        return {"results": []}  # leere Extraktion — bei infer=True wäre das Fantasie-Erfolg


class _FakeMem0Ltm:
    """Imitiert einen mem0-aktiven LTMService (echtes True)."""
    _mem0_enabled = True

    def __init__(self, client):
        self._client = client

    def _get_mem0(self):
        return self._client

    def store_memory(self, *a, **k):
        raise AssertionError("mem0-Pfad darf store_memory (Rewrite) NICHT nutzen")


def test_mem0_path_writes_raw_with_infer_false():
    client = _FakeMem0Client()
    ltm = _FakeMem0Ltm(client)
    result = store_seen(ltm, "s", "Mike", "Netflix", "Inception")
    assert result == "stored"
    assert len(client.add_calls) == 1
    call = client.add_calls[0]
    assert call["infer"] is False                       # ROH, kein Umschreiben
    assert call["metadata"]["category"] == "media_seen"
    assert call["metadata"].get("profile") == "Mike"    # Profil als Metadatum
    assert "Inception" in call["messages"][0]["content"]
    assert "Mike" in call["messages"][0]["content"]     # robust auch im Content


def test_mock_ltm_uses_store_memory_path():
    # Gegenprobe: ein gewöhnlicher (nicht-mem0) ltm nutzt store_memory.
    ltm = _mock_ltm()
    assert store_seen(ltm, "s", "Mike", "Netflix", "X") == "stored"
    ltm.store_memory.assert_called_once()


# ---------------------------------------------------------------------------
# YouTube: ZIP + Parsing
# ---------------------------------------------------------------------------

def _make_zip(tmp_path, members, name="takeout.zip") -> Path:
    zp = tmp_path / name
    with zipfile.ZipFile(zp, "w", zipfile.ZIP_DEFLATED) as zf:
        for arc, data in members.items():
            if isinstance(data, zipfile.ZipInfo):
                raise ValueError("use _make_zip_special for ZipInfo")
            zf.writestr(arc, data)
    return zp


_WATCH_ARC = "Takeout/YouTube and YouTube Music/history/watch-history.json"
_WATCH_ARC_DE = "Takeout/YouTube und YouTube Music/Verlauf/Wiedergabeverlauf.json"


def _watch_entries(entries) -> bytes:
    return json.dumps(entries, ensure_ascii=False).encode("utf-8")


def test_youtube_zip_isolated_extract_and_import(tmp_path):
    entries = [
        {"header": "YouTube", "title": "Watched Rust in 100 Seconds",
         "titleUrl": "https://youtube.com/watch?v=a", "time": "2026-10-29T18:12:00.000Z"},
        {"header": "YouTube", "title": "Watched Blender Donut",
         "titleUrl": "https://youtube.com/watch?v=b", "time": "2026-10-28T10:00:00+02:00"},
    ]
    zp = _make_zip(tmp_path, {_WATCH_ARC: _watch_entries(entries)})
    ltm = _mock_ltm()
    stats = import_youtube_takeout(str(zp), ltm=ltm, profile="Mike", session_id="s",
                                   extract_root=str(tmp_path / "iso"),
                                   ledger=_fresh_ledger(tmp_path))
    assert stats.stored == 2 and stats.errors == 0
    contents = _stored_contents(ltm)
    assert any("Rust in 100 Seconds" in c for c in contents)
    assert all("Mike" in c for c in contents)
    assert set(_stored_categories(ltm)) == {MEDIA_SEEN_CATEGORY}


def test_youtube_german_filename_recognized(tmp_path):
    entries = [{"header": "YouTube", "title": "Kochvideo angesehen", "titleUrl": "x"}]
    zp = _make_zip(tmp_path, {_WATCH_ARC_DE: _watch_entries(entries)})
    ltm = _mock_ltm()
    stats = import_youtube_takeout(str(zp), ltm=ltm, profile="Mike", session_id="s",
                                   extract_root=str(tmp_path / "iso"),
                                   ledger=_fresh_ledger(tmp_path))
    assert stats.stored == 1
    assert any("Kochvideo" in c for c in _stored_contents(ltm))


def test_youtube_single_json_fallback_in_history_folder(tmp_path):
    # Variierter, aber watch-/wiedergabe-haltiger JSON-Name im Verlauf-Ordner → Fallback.
    arc = "Takeout/YouTube/history/watch-history-2026.json"
    entries = [{"header": "YouTube", "title": "Watched Fallback Video", "titleUrl": "x"}]
    zp = _make_zip(tmp_path, {arc: _watch_entries(entries)})
    ltm = _mock_ltm()
    stats = import_youtube_takeout(str(zp), ltm=ltm, profile="Mike", session_id="s",
                                   extract_root=str(tmp_path / "iso"),
                                   ledger=_fresh_ledger(tmp_path))
    assert stats.stored == 1


def test_youtube_searchhistory_not_mistaken_for_watch(tmp_path):
    # M1: NUR Suchverlauf vorhanden (Wiedergabe pausiert) → NICHT als gesehen importieren.
    arc = "Takeout/YouTube and YouTube Music/history/Suchverlauf.json"
    entries = [{"header": "YouTube", "title": "Nach Katzenvideos gesucht", "titleUrl": "x"}]
    zp = _make_zip(tmp_path, {arc: _watch_entries(entries)})
    ltm = _mock_ltm()
    stats = import_youtube_takeout(str(zp), ltm=ltm, profile="Mike", session_id="s",
                                   extract_root=str(tmp_path / "iso"),
                                   ledger=_fresh_ledger(tmp_path))
    assert stats.stored == 0
    assert stats.errors >= 1           # sichtbar: Verlaufs-JSON fehlt (nur Suchverlauf)
    ltm.store_memory.assert_not_called()


def test_youtube_search_json_excluded_even_as_only_json(tmp_path):
    # M1: englischer search-history als einzige *.json → ausgeschlossen.
    arc = "Takeout/YouTube/history/search-history.json"
    entries = [{"header": "YouTube", "title": "Searched for cats", "titleUrl": "x"}]
    zp = _make_zip(tmp_path, {arc: _watch_entries(entries)})
    ltm = _mock_ltm()
    stats = import_youtube_takeout(str(zp), ltm=ltm, profile="Mike", session_id="s",
                                   extract_root=str(tmp_path / "iso"),
                                   ledger=_fresh_ledger(tmp_path))
    assert stats.stored == 0 and stats.errors >= 1


def test_youtube_html_only_is_error_not_silent(tmp_path):
    arc = "Takeout/YouTube and YouTube Music/history/watch-history.html"
    zp = _make_zip(tmp_path, {arc: b"<html>x</html>"})
    ltm = _mock_ltm()
    stats = import_youtube_takeout(str(zp), ltm=ltm, profile="Mike", session_id="s",
                                   extract_root=str(tmp_path / "iso"),
                                   ledger=_fresh_ledger(tmp_path))
    assert stats.stored == 0 and stats.errors >= 1   # kein stiller Skip → Exit != 0
    ltm.store_memory.assert_not_called()


def test_youtube_main_missing_history_nonzero_exit(tmp_path, monkeypatch):
    import app.scripts.import_youtube_takeout as mod
    arc = "Takeout/YouTube and YouTube Music/history/watch-history.html"
    zp = _make_zip(tmp_path, {arc: b"<html>x</html>"})
    monkeypatch.setattr(mod, "_build_ltm", lambda: _mock_ltm())
    monkeypatch.setattr(mod, "_default_ledger_path", lambda: str(tmp_path / "l.json"))
    rc = yt_main([str(zp), "--extract-root", str(tmp_path / "iso")])
    assert rc != 0


def test_parse_watch_history_strips_prefix_and_exact_suffix():
    entries = [
        {"header": "YouTube", "title": "Watched The Expanse Review", "titleUrl": "x"},
        {"header": "YouTube", "title": "Kochvideo angesehen", "titleUrl": "y"},
    ]
    res = parse_watch_history(entries)
    assert "The Expanse Review" in res.titles
    assert "Kochvideo" in res.titles
    assert res.discarded == 0


def test_parse_watch_history_does_not_mangle_midstring():
    entries = [
        {"header": "YouTube", "title": "Watched Wie ich es angesehen haben", "titleUrl": "x"},
    ]
    assert parse_watch_history(entries).titles == ["Wie ich es angesehen haben"]


def test_parse_watch_history_missing_titleurl_kept():
    entries = [{"header": "YouTube", "title": "Watched Privates Video"}]
    assert "Privates Video" in parse_watch_history(entries).titles


def test_parse_watch_history_removed_and_url_only_discarded():
    # M2: verworfene Einträge werden GEZÄHLT (nicht stiller Verlust).
    entries = [
        {"header": "YouTube", "title": "Watched a video that has been removed"},
        {"header": "YouTube", "title": "https://www.youtube.com/watch?v=zzz"},
    ]
    res = parse_watch_history(entries)
    assert res.titles == []
    assert res.discarded == 2


def test_parse_watch_history_non_youtube_header_skipped():
    entries = [
        {"header": "Google Ads", "title": "Watched Werbung", "titleUrl": "a"},
        {"header": "YouTube", "title": "Watched Echtes Video", "titleUrl": "b"},
    ]
    res = parse_watch_history(entries)
    assert res.titles == ["Echtes Video"]


def test_parse_watch_history_counts_infile_duplicates():
    # M2: "Foo"×3 → 1 Titel, 2 verworfen (nicht 3 stored).
    entries = [{"header": "YouTube", "title": "Watched Foo", "titleUrl": "x"} for _ in range(3)]
    res = parse_watch_history(entries)
    assert res.titles == ["Foo"]
    assert res.discarded == 2


# ---------------------------------------------------------------------------
# M2 ehrliche Zähler im Import + kein stiller 0/0/0
# ---------------------------------------------------------------------------

def test_youtube_titleless_entries_not_silent(tmp_path):
    # Gültiges JSON mit Einträgen, aber 0 Titel → kein stilles 0/0/0, Exit != 0.
    entries = [{"header": "YouTube"} for _ in range(5)]
    zp = _make_zip(tmp_path, {_WATCH_ARC: _watch_entries(entries)})
    ltm = _mock_ltm()
    stats = import_youtube_takeout(str(zp), ltm=ltm, profile="Mike", session_id="s",
                                   extract_root=str(tmp_path / "iso"),
                                   ledger=_fresh_ledger(tmp_path))
    assert stats.stored == 0
    assert stats.skipped == 5        # verworfene Einträge gezählt
    assert stats.errors >= 1         # Einträge vorhanden, 0 Titel → sichtbarer Fehler
    ltm.store_memory.assert_not_called()


def test_youtube_infile_duplicates_counted_as_skipped(tmp_path):
    entries = [{"header": "YouTube", "title": "Watched Foo", "titleUrl": "x"} for _ in range(3)]
    zp = _make_zip(tmp_path, {_WATCH_ARC: _watch_entries(entries)})
    ltm = _mock_ltm()
    stats = import_youtube_takeout(str(zp), ltm=ltm, profile="Mike", session_id="s",
                                   extract_root=str(tmp_path / "iso"),
                                   ledger=_fresh_ledger(tmp_path))
    assert stats.stored == 1
    assert stats.skipped == 2
    assert stats.errors == 0


def test_youtube_idempotent_second_run(tmp_path):
    entries = [{"header": "YouTube", "title": "Watched Same", "titleUrl": "a"}]
    zp = _make_zip(tmp_path, {_WATCH_ARC: _watch_entries(entries)})
    lpath = str(tmp_path / "l.json")
    s1 = import_youtube_takeout(str(zp), ltm=_mock_ltm(), profile="Mike", session_id="s",
                                extract_root=str(tmp_path / "iso1"), ledger=SeenLedger(path=lpath))
    s2 = import_youtube_takeout(str(zp), ltm=_mock_ltm(), profile="Mike", session_id="s",
                                extract_root=str(tmp_path / "iso2"), ledger=SeenLedger(path=lpath))
    assert s1.stored == 1
    assert s2.stored == 0 and s2.duplicate == 1


# ---------------------------------------------------------------------------
# ÄNDERUNG 1 (#8): YouTube-HTML-Verlauf — profil-bewusst (der ECHTE Takeout ist HTML)
# ---------------------------------------------------------------------------

# Reale Takeout-Pfade: Hauptkonto (Mike) + YouTube-Kids-Kind (Yannik) + Suchverlauf.
_WATCH_HTML_MIKE = "Takeout/YouTube und YouTube Music/Verlauf/Wiedergabeverlauf.html"
_WATCH_HTML_YANNIK = "Takeout/YouTube und YouTube Music/YouTube Kids/Yannik/Wiedergabeverlauf.html"
_SEARCH_HTML = "Takeout/YouTube und YouTube Music/Verlauf/Suchverlauf.html"


def _watch_cell(inner: str) -> str:
    """Eine Takeout-"outer-cell" mit YouTube-Header + content-cell (wie der echte Export)."""
    return (
        '<div class="outer-cell mdl-cell mdl-cell--12-col mdl-shadow--2dp">'
        '<div class="header-cell mdl-cell mdl-cell--12-col">'
        '<p class="mdl-typography--title">YouTube<br></p></div>'
        '<div class="content-cell mdl-cell mdl-cell--6-col mdl-typography--body-1">'
        f'{inner}'
        '</div></div>'
    )


def _watch_html(videos) -> bytes:
    """Baut eine Takeout-Wiedergabe-HTML aus (Titel[, Kanal])-Tupeln bzw. nackten Titeln.

    Struktur je Video wie im echten Export:
        Watched <a href="…/watch?v=…">TITEL</a><br><a href="…/channel/…">KANAL</a><br>DATUM
    """
    cells = []
    for i, v in enumerate(videos):
        title, channel = (v if isinstance(v, tuple) else (v, "Ein Kanal"))
        cells.append(_watch_cell(
            f'Watched <a href="https://www.youtube.com/watch?v=vid{i}">{title}</a><br>'
            f'<a href="https://www.youtube.com/channel/UC{i:022d}">{channel}</a><br>'
            'Oct 29, 2026, 6:12:00 PM CET'
        ))
    return ("<!DOCTYPE html><html><head><meta charset='utf-8'></head><body>"
            + "".join(cells) + "</body></html>").encode("utf-8")


def _html_raw_cells(inner_cells) -> bytes:
    """Rohe content-cell-Inhalte 1:1 (für Removed/URL-only-Fälle)."""
    return ("<html><body>" + "".join(_watch_cell(c) for c in inner_cells)
            + "</body></html>").encode("utf-8")


# -- Unit: HTML-Parser --

def test_parse_watch_history_html_basic():
    html = _watch_html(["Rust in 100 Seconds", "Blender Donut Tutorial"]).decode("utf-8")
    res = parse_watch_history_html(html)
    assert res.titles == ["Rust in 100 Seconds", "Blender Donut Tutorial"]
    assert res.discarded == 0


def test_parse_watch_history_html_skips_removed_and_url_only():
    # URL-only-Titel (reiner Link als Anker-Text) + In-Datei-Dublette → discarded.
    html = _html_raw_cells([
        'Watched <a href="https://www.youtube.com/watch?v=a">Echtes Video</a><br>',
        # reine URL als "Titel":
        'Watched <a href="https://www.youtube.com/watch?v=b">https://www.youtube.com/watch?v=b</a><br>',
        # "Removed": ohne Anker (wie echter Takeout) → gar nicht extrahiert:
        'Watched a video that has been removed<br>Oct 29, 2026',
        # Dublette von "Echtes Video":
        'Watched <a href="https://www.youtube.com/watch?v=a2">Echtes Video</a><br>',
    ]).decode("utf-8")
    res = parse_watch_history_html(html)
    assert res.titles == ["Echtes Video"]
    assert res.discarded == 2        # URL-only + Dublette (Removed hatte keinen Anker)


def test_parse_watch_history_html_unescapes_entities():
    html = _watch_html(["Rick &amp; Morty: S1 &lt;Pilot&gt;"]).decode("utf-8")
    res = parse_watch_history_html(html)
    assert res.titles == ["Rick & Morty: S1 Pilot"]   # &amp;→&, <>-entfernt via sanitize


def test_parse_watch_history_html_does_not_strip_watched_wrapper(tmp_path):
    # R2 (reproduziert): "Watched"/" angesehen" stehen im echten Takeout AUSSERHALB des
    # Ankers — der Anker-Innentext ist schon der reine Titel. Ein (fälschlicher) Wrapper-
    # Strip würde "Watched Over Me"→"Over Me" / "… angesehen"→"…" verstümmeln.
    # Mutation sanitize_title→_clean_video_title im HTML-Pfad → dieser Test wird rot.
    html = _html_raw_cells([
        'Watched <a href="https://www.youtube.com/watch?v=a">Watched Over Me</a><br>',
        'Watched <a href="https://www.youtube.com/watch?v=b">Das letzte Mal angesehen</a><br>',
    ]).decode("utf-8")
    res = parse_watch_history_html(html)
    assert res.titles == ["Watched Over Me", "Das letzte Mal angesehen"]


def test_parse_watch_history_html_redos_time_budget():
    # R1 (gemessen): bösartiges HTML ("<a "×n ohne '>' + fehlendes </a>) darf NICHT
    # quadratisch werden. Mit [^>]*/(.*?) früher Minuten; mit [^<>]/[^<]{0,2000} linear.
    import time
    evil = ("<a " * 30000) + ("x" * 60000)   # ~150 KB, kein '>' und kein </a>
    t0 = time.perf_counter()
    res = parse_watch_history_html(evil)
    dt = time.perf_counter() - t0
    assert res.titles == []
    assert dt < 1.0, f"HTML-Parsing zu langsam ({dt:.2f}s) — ReDoS-Verdacht"


# -- Unit: Profil-Zuordnung aus dem ZIP-Pfad --

@pytest.mark.parametrize("path,default,expected", [
    (_WATCH_HTML_MIKE, "Mike", "Mike"),
    (_WATCH_HTML_YANNIK, "Mike", "Yannik"),
    ("Takeout/YouTube and YouTube Music/history/watch-history.html", "Mike", "Mike"),
    ("Takeout/YouTube und YouTube Music/YouTube Kids/Lena/Wiedergabeverlauf.html", "Mike", "Lena"),
])
def test_watch_html_profile_resolution(path, default, expected):
    assert _watch_html_profile(path, default) == expected


# -- Integration: Import aus dem ZIP --

def test_youtube_html_mike_profile_import(tmp_path):
    zp = _make_zip(tmp_path, {_WATCH_HTML_MIKE: _watch_html(
        ["Rust in 100 Seconds", "Blender Donut", "The Expanse Review"])})
    ltm = _mock_ltm()
    stats = import_youtube_takeout(str(zp), ltm=ltm, profile="Mike", session_id="s",
                                   extract_root=str(tmp_path / "iso"),
                                   ledger=_fresh_ledger(tmp_path))
    assert stats.stored == 3 and stats.errors == 0
    contents = _stored_contents(ltm)
    assert any("Rust in 100 Seconds" in c for c in contents)
    assert all(c.startswith("Mike hat auf YouTube gesehen") for c in contents)
    assert set(_stored_categories(ltm)) == {MEDIA_SEEN_CATEGORY}


def test_youtube_html_yannik_kids_profile_import(tmp_path):
    zp = _make_zip(tmp_path, {_WATCH_HTML_YANNIK: _watch_html(["Peppa Wutz", "Feuerwehrmann Sam"])})
    ltm = _mock_ltm()
    stats = import_youtube_takeout(str(zp), ltm=ltm, profile="Mike", session_id="s",
                                   extract_root=str(tmp_path / "iso"),
                                   ledger=_fresh_ledger(tmp_path))
    assert stats.stored == 2 and stats.errors == 0
    contents = _stored_contents(ltm)
    # Kids-Profil "Yannik" aus dem Pfad — NICHT das übergebene Default-Profil "Mike".
    assert all(c.startswith("Yannik hat auf YouTube gesehen") for c in contents)
    assert any("Peppa Wutz" in c for c in contents)


def test_youtube_html_both_profiles_in_one_zip(tmp_path):
    # BEIDE Verlaufs-HTMLs im selben ZIP → jeweils mit eigenem Profil importiert.
    zp = _make_zip(tmp_path, {
        _WATCH_HTML_MIKE: _watch_html(["Mikes Doku"]),
        _WATCH_HTML_YANNIK: _watch_html(["Yanniks Cartoon"]),
    })
    ltm = _mock_ltm()
    stats = import_youtube_takeout(str(zp), ltm=ltm, profile="Mike", session_id="s",
                                   extract_root=str(tmp_path / "iso"),
                                   ledger=_fresh_ledger(tmp_path))
    assert stats.stored == 2 and stats.errors == 0
    contents = _stored_contents(ltm)
    assert any(c == "Mike hat auf YouTube gesehen: Mikes Doku" for c in contents)
    assert any(c == "Yannik hat auf YouTube gesehen: Yanniks Cartoon" for c in contents)


def test_youtube_html_removed_and_url_only_skipped(tmp_path):
    zp = _make_zip(tmp_path, {_WATCH_HTML_MIKE: _html_raw_cells([
        'Watched <a href="https://www.youtube.com/watch?v=a">Guter Film</a><br>',
        'Watched <a href="https://www.youtube.com/watch?v=b">https://www.youtube.com/watch?v=b</a><br>',
        'Watched a video that has been removed<br>Oct 29, 2026',
    ])})
    ltm = _mock_ltm()
    stats = import_youtube_takeout(str(zp), ltm=ltm, profile="Mike", session_id="s",
                                   extract_root=str(tmp_path / "iso"),
                                   ledger=_fresh_ledger(tmp_path))
    assert stats.stored == 1
    assert any("Guter Film" in c for c in _stored_contents(ltm))
    assert all("has been removed" not in c for c in _stored_contents(ltm))


def test_youtube_searchhistory_html_not_imported(tmp_path):
    # Suchverlauf.html ist KEIN Sehverlauf → nicht importieren; kein Sehverlauf da → Fehler.
    # F2 (R4): die Fixture enthält bewusst AUCH einen echten /watch-Anker ("Geheimer
    # Suchtreffer"). Würde die Datei fälschlich als Sehverlauf behandelt (Mutation: alle
    # .html erlauben + Hints-Check entfernt), landete dieser Titel im LTM → der Test wird
    # rot. So ist der Ausschluss nicht vakuös grün.
    search = _html_raw_cells([
        'Searched for <a href="https://www.youtube.com/results?search_query=katzen">katzen</a><br>',
        'Watched <a href="https://www.youtube.com/watch?v=leak">Geheimer Suchtreffer</a><br>',
    ])
    zp = _make_zip(tmp_path, {_SEARCH_HTML: search})
    ltm = _mock_ltm()
    stats = import_youtube_takeout(str(zp), ltm=ltm, profile="Mike", session_id="s",
                                   extract_root=str(tmp_path / "iso"),
                                   ledger=_fresh_ledger(tmp_path))
    assert stats.stored == 0 and stats.errors >= 1
    ltm.store_memory.assert_not_called()
    assert all("Geheimer Suchtreffer" not in c for c in _stored_contents(ltm))


def test_youtube_html_marker_and_control_chars_neutralized(tmp_path):
    # UNTRUSTED HTML → sanitize_title VOR dem LTM-Schreiben (C0/Bidi/<>).
    # <>-Zeichen stehen im echten Takeout escaped (&lt;/&gt;) → nach unescape im Titel →
    # müssen von sanitize_title entfernt werden; rohe C0/Bidi-Bytes bleiben roh.
    dirty = "Evil‮Clip\x00&lt;script&gt;" + "z" * 400
    zp = _make_zip(tmp_path, {_WATCH_HTML_MIKE: _html_raw_cells([
        f'Watched <a href="https://www.youtube.com/watch?v=a">{dirty}</a><br>',
    ])})
    ltm = _mock_ltm()
    import_youtube_takeout(str(zp), ltm=ltm, profile="Mike", session_id="s",
                           extract_root=str(tmp_path / "iso"), ledger=_fresh_ledger(tmp_path))
    contents = _stored_contents(ltm)
    assert contents  # nicht leer grün
    for c in contents:
        for bad in ("\x00", "‮", "<", ">"):
            assert bad not in c
        assert len(c) <= 260


def test_youtube_html_idempotent_second_run(tmp_path):
    zp = _make_zip(tmp_path, {_WATCH_HTML_MIKE: _watch_html(["Rust in 100 Seconds", "Blender Donut"])})
    lpath = str(tmp_path / "l.json")
    s1 = import_youtube_takeout(str(zp), ltm=_mock_ltm(), profile="Mike", session_id="s",
                                extract_root=str(tmp_path / "iso1"), ledger=SeenLedger(path=lpath))
    s2 = import_youtube_takeout(str(zp), ltm=_mock_ltm(), profile="Mike", session_id="s",
                                extract_root=str(tmp_path / "iso2"), ledger=SeenLedger(path=lpath))
    assert s1.stored == 2
    assert s2.stored == 0 and s2.duplicate == 2


def test_youtube_html_found_but_titleless_is_error(tmp_path):
    # Als Verlauf BENANNTE HTML ohne gültige Video-Anker → sichtbarer Fehler (kein stiller 0/0/0).
    zp = _make_zip(tmp_path, {_WATCH_HTML_MIKE: b"<html><body>kein inhalt</body></html>"})
    ltm = _mock_ltm()
    stats = import_youtube_takeout(str(zp), ltm=ltm, profile="Mike", session_id="s",
                                   extract_root=str(tmp_path / "iso"),
                                   ledger=_fresh_ledger(tmp_path))
    assert stats.stored == 0 and stats.errors >= 1
    ltm.store_memory.assert_not_called()


# ---------------------------------------------------------------------------
# Sicherheit: Path-Traversal / Zip-Bomb / Symlink / Stream-Cap
# ---------------------------------------------------------------------------

def _yt(zp, tmp_path, iso="iso"):
    return import_youtube_takeout(str(zp), ltm=_mock_ltm(), profile="Mike", session_id="s",
                                  extract_root=str(tmp_path / iso), ledger=SeenLedger())


def test_reject_path_traversal_dotdot(tmp_path):
    zp = _make_zip(tmp_path, {
        _WATCH_ARC: _watch_entries([{"header": "YouTube", "title": "Watched X", "titleUrl": "a"}]),
        "../evil.txt": b"pwned"})
    with pytest.raises(UnsafeArchiveError):
        _yt(zp, tmp_path)
    assert not (tmp_path / "evil.txt").exists()


def test_reject_absolute_path(tmp_path):
    zp = _make_zip(tmp_path, {
        _WATCH_ARC: _watch_entries([{"header": "YouTube", "title": "Watched X", "titleUrl": "a"}]),
        "/etc/evil": b"pwned"})
    with pytest.raises(UnsafeArchiveError):
        _yt(zp, tmp_path)


def test_reject_backslash_traversal(tmp_path):
    zp = _make_zip(tmp_path, {
        _WATCH_ARC: _watch_entries([{"header": "YouTube", "title": "Watched X", "titleUrl": "a"}]),
        "..\\..\\evil.txt": b"pwned"})
    with pytest.raises(UnsafeArchiveError):
        _yt(zp, tmp_path)


def test_reject_too_many_entries(tmp_path, monkeypatch):
    import app.scripts.import_youtube_takeout as mod
    monkeypatch.setattr(mod, "MAX_ENTRIES", 3)
    members = {f"Takeout/f_{i}.txt": b"x" for i in range(5)}
    members[_WATCH_ARC] = _watch_entries([{"header": "YouTube", "title": "Watched X", "titleUrl": "a"}])
    zp = _make_zip(tmp_path, members)
    with pytest.raises(UnsafeArchiveError):
        _yt(zp, tmp_path)


def test_reject_too_large_total(tmp_path, monkeypatch):
    import app.scripts.import_youtube_takeout as mod
    monkeypatch.setattr(mod, "MAX_TOTAL_UNCOMPRESSED", 1000)
    zp = _make_zip(tmp_path, {
        _WATCH_ARC: _watch_entries([{"header": "YouTube", "title": "Watched X", "titleUrl": "a"}]),
        "Takeout/big.txt": b"A" * 100_000})
    with pytest.raises(UnsafeArchiveError):
        _yt(zp, tmp_path)


def test_reject_single_member_too_large(tmp_path, monkeypatch):
    import app.scripts.import_youtube_takeout as mod
    monkeypatch.setattr(mod, "MAX_MEMBER_UNCOMPRESSED", 500)
    zp = _make_zip(tmp_path, {
        _WATCH_ARC: _watch_entries([{"header": "YouTube", "title": "Watched X", "titleUrl": "a"}]),
        "Takeout/single.txt": b"B" * 10_000})
    with pytest.raises(UnsafeArchiveError):
        _yt(zp, tmp_path)


def test_reject_symlink_entry(tmp_path):
    zp = tmp_path / "sym.zip"
    with zipfile.ZipFile(zp, "w") as zf:
        zf.writestr(_WATCH_ARC, _watch_entries([{"header": "YouTube", "title": "Watched X", "titleUrl": "a"}]))
        info = zipfile.ZipInfo("Takeout/link")
        info.external_attr = (0xA1FF) << 16   # S_IFLNK | 0777 → external_attr >> 28 == 0xA
        zf.writestr(info, "/etc/passwd")
    with pytest.raises(UnsafeArchiveError):
        _yt(zp, tmp_path)


def test_stream_cap_catches_lying_directory_size(tmp_path, monkeypatch):
    # Opus-Lücke: Audit (Directory-Größe) wird umgangen → Stream-Cap muss greifen.
    import app.scripts.import_youtube_takeout as mod
    monkeypatch.setattr(mod, "_audit_archive", lambda zf: None)   # Directory "lügt"/übersprungen
    monkeypatch.setattr(mod, "MAX_MEMBER_UNCOMPRESSED", 500)
    big = _watch_entries([{"header": "YouTube", "title": "Watched " + "Z" * 10_000, "titleUrl": "a"}])
    zp = _make_zip(tmp_path, {_WATCH_ARC: big})
    with pytest.raises(UnsafeArchiveError):
        _yt(zp, tmp_path)


def _assert_no_leak(capsys, caps, secret: str):
    # structlog nutzt PrintLoggerFactory → stdout; daher BEIDE Kanäle prüfen
    # (capsys = gerenderte Zeile, capture_logs = Event-Ebene, cache-unabhängig).
    out = capsys.readouterr().out
    joined = out + " " + " ".join(repr(c) for c in caps)
    assert secret not in joined


def test_netflix_no_title_leak_in_logs_on_store_error(tmp_path, capsys):
    # B10/T2: Fehler-Log darf den Seh-Titel nicht ausplaudern. Mutation title=title → rot.
    csv = 'Title,Date\r\n"GeheimerFilmTitel",01.01.2024\r\n'
    p = tmp_path / "Mike_History.csv"
    p.write_bytes(csv.encode("utf-8"))
    ltm = _mock_ltm()
    ltm.store_memory.side_effect = RuntimeError("boom")
    with capture_logs() as caps:
        stats = import_netflix_file(str(p), profile="Mike", ltm=ltm, session_id="s",
                                    ledger=_fresh_ledger(tmp_path))
    assert stats.errors >= 1
    _assert_no_leak(capsys, caps, "GeheimerFilmTitel")


def test_youtube_no_title_leak_in_logs_on_store_error(tmp_path, capsys):
    entries = [{"header": "YouTube", "title": "Watched GeheimesYTVideo", "titleUrl": "x"}]
    zp = _make_zip(tmp_path, {_WATCH_ARC: _watch_entries(entries)})
    ltm = _mock_ltm()
    ltm.store_memory.side_effect = RuntimeError("boom")
    with capture_logs() as caps:
        stats = import_youtube_takeout(str(zp), ltm=ltm, profile="Mike", session_id="s",
                                       extract_root=str(tmp_path / "iso"),
                                       ledger=_fresh_ledger(tmp_path))
    assert stats.errors >= 1
    _assert_no_leak(capsys, caps, "GeheimesYTVideo")
