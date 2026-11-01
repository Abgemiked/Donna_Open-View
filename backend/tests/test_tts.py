"""Tests für /tts — Edge-TTS (Voice-Batch, A/B gegen Piper-lokal).

Testet (edge-tts wird gemockt, KEIN echter Netzcall):
1. /tts liefert audio/mpeg mit nicht-leerem Body
2. Stimme: Default Katja, Query-Param/Body-Feld, alte Piper-IDs -> Default (kein 422)
3. Leerer/nicht sprechbarer Text -> 204
4. Edge-Ausfall -> 503, Timeout -> 504 (Client wechselt auf die andere Engine)
5. Cache: gleicher Satz wird nur einmal synthetisiert; Pre-Synthese füllt den Cache
6. pre_synthesize() wirft nie
7. clean_for_speech / resolve_voice / split_sentences
"""
from __future__ import annotations

import asyncio

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.routes import tts as tts_mod


FAKE_MP3 = b"\xff\xfb\x90\x00" + b"\x00" * 64  # MP3-Frame-Header + Padding


# ---------------------------------------------------------------------------
# Fixtures
# ---------------------------------------------------------------------------

@pytest.fixture(autouse=True)
def _clean_state():
    """Cache/Inflight zwischen Tests leeren, Semaphore neu (eigener Loop pro TestClient)."""
    tts_mod._cache.clear()
    tts_mod._inflight.clear()
    tts_mod._semaphore = None
    yield
    tts_mod._cache.clear()
    tts_mod._inflight.clear()
    tts_mod._semaphore = None


@pytest.fixture
def synth_calls(monkeypatch):
    """Mockt die Edge-Synthese; protokolliert (text, voice)."""
    calls: list[tuple[str, str]] = []

    async def fake(text: str, voice: str) -> bytes:
        calls.append((text, voice))
        return FAKE_MP3

    monkeypatch.setattr(tts_mod, "_synthesize_edge", fake)
    return calls


@pytest.fixture
def client_with_mocks():
    """TestClient mit gemocktem require_admin via Dependency-Override."""
    from app.core.auth import require_admin

    test_app = FastAPI()
    test_app.include_router(tts_mod.router)
    test_app.dependency_overrides[require_admin] = lambda: "admin"

    with TestClient(test_app) as c:
        yield c


# ---------------------------------------------------------------------------
# Test 1: Audio-Response
# ---------------------------------------------------------------------------

def test_tts_voice_input_returns_mpeg_audio(client_with_mocks, synth_calls):
    """POST /tts mit was_voice_input=True → 200 audio/mpeg, Body nicht leer."""
    resp = client_with_mocks.post("/tts", json={"text": "Hallo Mike", "was_voice_input": True})
    assert resp.status_code == 200
    assert resp.headers["content-type"].startswith("audio/mpeg")
    assert len(resp.content) > 0
    assert resp.content == FAKE_MP3


def test_tts_text_input_returns_mpeg_audio(client_with_mocks, synth_calls):
    """was_voice_input=False → ebenfalls Audio (TTS ist nicht mehr deaktiviert)."""
    resp = client_with_mocks.post("/tts", json={"text": "Hallo Mike", "was_voice_input": False})
    assert resp.status_code == 200
    assert resp.headers["content-type"].startswith("audio/mpeg")
    assert resp.content


# ---------------------------------------------------------------------------
# Test 2: Stimme
# ---------------------------------------------------------------------------

def test_tts_default_voice_is_katja(client_with_mocks, synth_calls):
    client_with_mocks.post("/tts", json={"text": "Hallo Mike"})
    assert synth_calls == [("Hallo Mike", "de-DE-KatjaNeural")]


def test_tts_voice_query_param_selects_seraphina(client_with_mocks, synth_calls):
    resp = client_with_mocks.post(
        "/tts?voice=de-DE-SeraphinaMultilingualNeural", json={"text": "Hallo Mike"},
    )
    assert resp.status_code == 200
    assert synth_calls[0][1] == "de-DE-SeraphinaMultilingualNeural"


def test_tts_voice_body_field_selects_seraphina(client_with_mocks, synth_calls):
    client_with_mocks.post(
        "/tts", json={"text": "Hallo Mike", "voice": "de-DE-SeraphinaMultilingualNeural"},
    )
    assert synth_calls[0][1] == "de-DE-SeraphinaMultilingualNeural"


def test_tts_query_param_beats_body_field(client_with_mocks, synth_calls):
    client_with_mocks.post(
        "/tts?voice=de-DE-KatjaNeural",
        json={"text": "Hallo Mike", "voice": "de-DE-SeraphinaMultilingualNeural"},
    )
    assert synth_calls[0][1] == "de-DE-KatjaNeural"


def test_tts_legacy_piper_voice_accepted_falls_back_to_default(client_with_mocks, synth_calls):
    """Alte Clients senden Piper-IDs + speaker_id — weiter 200 (kein 422), Default-Stimme."""
    resp = client_with_mocks.post("/tts", json={
        "text": "Hallo Mike",
        "voice": "de_DE-mls-medium",
        "speaker_id": 184,
        "was_voice_input": True,
    })
    assert resp.status_code == 200
    assert synth_calls[0][1] == "de-DE-KatjaNeural"


def test_tts_unknown_voice_never_reaches_edge(client_with_mocks, synth_calls):
    """Nur Allowlist-Stimmen werden an Edge gereicht (z. B. männliche/Injection-Strings nicht)."""
    client_with_mocks.post("/tts?voice=de-DE-ConradNeural", json={"text": "Hallo"})
    assert synth_calls[0][1] == "de-DE-KatjaNeural"


# ---------------------------------------------------------------------------
# Test 3: nichts Sprechbares
# ---------------------------------------------------------------------------

def test_tts_unspeakable_text_returns_204(client_with_mocks, synth_calls):
    resp = client_with_mocks.post("/tts", json={"text": "**  ##  https://example.org/x  **"})
    assert resp.status_code == 204
    assert synth_calls == []


# ---------------------------------------------------------------------------
# Test 4: Fehlerpfade (Client nutzt dann die andere Engine)
# ---------------------------------------------------------------------------

def test_tts_edge_unavailable_returns_503(client_with_mocks, monkeypatch):
    async def boom(text: str, voice: str) -> bytes:
        raise tts_mod.TTSUnavailable("offline")

    monkeypatch.setattr(tts_mod, "_synthesize_edge", boom)
    resp = client_with_mocks.post("/tts", json={"text": "Hallo Mike"})
    assert resp.status_code == 503


def test_tts_edge_timeout_returns_504(client_with_mocks, monkeypatch):
    async def hang(text: str, voice: str) -> bytes:
        await asyncio.sleep(5)
        return FAKE_MP3

    monkeypatch.setattr(tts_mod, "_synthesize_edge", hang)
    monkeypatch.setattr(tts_mod, "SYNTH_TIMEOUT_S", 0.05)
    resp = client_with_mocks.post("/tts", json={"text": "Hallo Mike"})
    assert resp.status_code == 504


def test_tts_failure_is_not_cached(client_with_mocks, monkeypatch):
    """Nach einem Fehler klappt der nächste Versuch (kein vergifteter Cache/Inflight)."""
    state = {"n": 0}

    async def flaky(text: str, voice: str) -> bytes:
        state["n"] += 1
        if state["n"] == 1:
            raise tts_mod.TTSUnavailable("kurz weg")
        return FAKE_MP3

    monkeypatch.setattr(tts_mod, "_synthesize_edge", flaky)
    assert client_with_mocks.post("/tts", json={"text": "Hallo"}).status_code == 503
    assert client_with_mocks.post("/tts", json={"text": "Hallo"}).status_code == 200


# ---------------------------------------------------------------------------
# Test 5: Cache + Pre-Synthese (DONNA-137)
# ---------------------------------------------------------------------------

def test_tts_cache_hit_synthesizes_once(client_with_mocks, synth_calls):
    for _ in range(3):
        assert client_with_mocks.post("/tts", json={"text": "Hallo Mike"}).status_code == 200
    assert len(synth_calls) == 1


def test_pre_synthesize_fills_cache_per_sentence(synth_calls):
    from app.routes.tts import pre_synthesize

    asyncio.run(pre_synthesize("Hallo Mike. Wie geht es dir? Alles gut!"))
    assert [c[0] for c in synth_calls] == ["Hallo Mike.", "Wie geht es dir?", "Alles gut!"]
    assert ("de-DE-KatjaNeural", "Hallo Mike.") in tts_mod._cache


def test_pre_synthesize_then_endpoint_hits_cache(client_with_mocks, synth_calls):
    asyncio.run(tts_mod.pre_synthesize("Hallo Mike. Wie geht es dir?"))
    before = len(synth_calls)
    resp = client_with_mocks.post("/tts", json={"text": "Wie geht es dir?"})
    assert resp.status_code == 200
    assert len(synth_calls) == before  # kein neuer Edge-Call


def test_pre_synthesize_limits_sentence_count(synth_calls):
    text = " ".join(f"Satz {i}." for i in range(20))
    asyncio.run(tts_mod.pre_synthesize(text))
    assert len(synth_calls) == tts_mod.PRE_SYNTH_MAX_SENTENCES


def test_pre_synthesize_synthesizes_first_sentence_before_the_rest(monkeypatch):
    """Latenz: Der ERSTE Satz wird fertig synthetisiert, BEVOR die uebrigen Saetze starten.

    Der Client fragt /tts der Reihe nach ab; genau die Wartezeit des ersten Satzes bestimmt, wie schnell Donna
    zu sprechen beginnt. Ein langsamer erster Satz darf NICHT erst nach den uebrigen fertig werden.
    """
    order: list[str] = []
    first_started = asyncio.Event()
    release_first = asyncio.Event()

    async def fake(text: str, voice: str) -> bytes:
        order.append(f"start:{text}")
        if text == "Eins.":
            first_started.set()
            await release_first.wait()   # ersten Satz kuenstlich bremsen
        order.append(f"done:{text}")
        return FAKE_MP3

    monkeypatch.setattr(tts_mod, "_synthesize_edge", fake)

    async def run() -> None:
        task = asyncio.ensure_future(tts_mod.pre_synthesize("Eins. Zwei. Drei."))
        await first_started.wait()
        # Waehrend der erste Satz haengt, darf KEIN weiterer Satz gestartet worden sein
        assert order == ["start:Eins."]
        release_first.set()
        await task

    asyncio.run(run())
    # Erster Satz zuerst fertig, danach erst die anderen gestartet
    assert order[0] == "start:Eins."
    assert order[1] == "done:Eins."
    assert set(order[2:]) == {"start:Zwei.", "done:Zwei.", "start:Drei.", "done:Drei."}


# ---------------------------------------------------------------------------
# Test 6: pre_synthesize wirft nie
# ---------------------------------------------------------------------------

def test_pre_synthesize_swallows_errors(monkeypatch):
    async def boom(text: str, voice: str) -> bytes:
        raise tts_mod.TTSUnavailable("offline")

    monkeypatch.setattr(tts_mod, "_synthesize_edge", boom)
    # Kein Fehler, keine Exception
    asyncio.run(tts_mod.pre_synthesize("Hallo Mike."))


def test_pre_synthesize_empty_text_is_noop(synth_calls):
    asyncio.run(tts_mod.pre_synthesize("   "))
    assert synth_calls == []


# ---------------------------------------------------------------------------
# Test 7: Helfer
# ---------------------------------------------------------------------------

def test_clean_for_speech_strips_markdown_urls_emoji_and_units():
    raw = "**Wetter**: 21°C und 40 % Regen 🌧️ — mehr auf [Wetter](https://wetter.de) oder https://x.de/y"
    out = tts_mod.clean_for_speech(raw)
    assert "*" not in out and "http" not in out and "🌧" not in out
    assert "21 Grad" in out and "40 Prozent" in out
    assert "Wetter" in out


def test_clean_for_speech_drops_action_marker():
    assert tts_mod.clean_for_speech("Mache ich. DONNA_ACTION: {\"a\":1}") == "Mache ich."


def test_resolve_voice():
    assert tts_mod.resolve_voice(None) == "de-DE-KatjaNeural"
    assert tts_mod.resolve_voice("") == "de-DE-KatjaNeural"
    assert tts_mod.resolve_voice("de_DE-kerstin-low") == "de-DE-KatjaNeural"
    assert tts_mod.resolve_voice("de-DE-SeraphinaMultilingualNeural") == "de-DE-SeraphinaMultilingualNeural"


def test_split_sentences():
    assert tts_mod.split_sentences("Eins. Zwei!\nDrei?") == ["Eins.", "Zwei!", "Drei?"]


def test_edge_tts_adapter_collects_audio_chunks(monkeypatch):
    """_synthesize_edge sammelt nur 'audio'-Chunks (edge_tts.Communicate gemockt, kein Netz)."""
    edge_tts = pytest.importorskip("edge_tts")

    class FakeCommunicate:
        def __init__(self, text, voice, rate="+0%"):
            assert voice == "de-DE-KatjaNeural"

        async def stream(self):
            yield {"type": "WordBoundary", "offset": 0}
            yield {"type": "audio", "data": b"AAA"}
            yield {"type": "audio", "data": b"BBB"}

    monkeypatch.setattr(edge_tts, "Communicate", FakeCommunicate)
    out = asyncio.run(tts_mod._synthesize_edge("Hallo", "de-DE-KatjaNeural"))
    assert out == b"AAABBB"


def test_edge_tts_adapter_empty_stream_raises_unavailable(monkeypatch):
    edge_tts = pytest.importorskip("edge_tts")

    class EmptyCommunicate:
        def __init__(self, text, voice, rate="+0%"): ...

        async def stream(self):
            if False:
                yield {}

    monkeypatch.setattr(edge_tts, "Communicate", EmptyCommunicate)
    with pytest.raises(tts_mod.TTSUnavailable):
        asyncio.run(tts_mod._synthesize_edge("Hallo", "de-DE-KatjaNeural"))


# ---------------------------------------------------------------------------
# Per-Stimme-Sprechtempo (Seraphina schneller, Katja unveraendert)
# ---------------------------------------------------------------------------

def _capture_communicate(monkeypatch):
    """Ersetzt edge_tts.Communicate durch einen Fake, der die Aufrufargumente festhaelt."""
    edge_tts = pytest.importorskip("edge_tts")
    captured: list[dict] = []

    class CapturingCommunicate:
        def __init__(self, text, voice, **kwargs):
            captured.append({"text": text, "voice": voice, **kwargs})

        async def stream(self):
            yield {"type": "audio", "data": b"X"}

    monkeypatch.setattr(edge_tts, "Communicate", CapturingCommunicate)
    return captured


def test_synthesize_edge_seraphina_uses_faster_rate(monkeypatch):
    captured = _capture_communicate(monkeypatch)
    monkeypatch.setitem(
        tts_mod._VOICE_RATE, "de-DE-SeraphinaMultilingualNeural", "+15%"
    )
    asyncio.run(tts_mod._synthesize_edge("Hallo", "de-DE-SeraphinaMultilingualNeural"))
    assert captured[0]["rate"] == "+15%"


def test_synthesize_edge_seraphina_default_rate_is_plus_15():
    assert tts_mod._VOICE_RATE["de-DE-SeraphinaMultilingualNeural"] == "+15%"


def test_synthesize_edge_katja_stays_neutral(monkeypatch):
    captured = _capture_communicate(monkeypatch)
    asyncio.run(tts_mod._synthesize_edge("Hallo", "de-DE-KatjaNeural"))
    assert captured[0]["rate"] == "+0%"
    assert "de-DE-KatjaNeural" not in tts_mod._VOICE_RATE


def test_synthesize_edge_seraphina_rate_follows_configured_value(monkeypatch):
    captured = _capture_communicate(monkeypatch)
    monkeypatch.setitem(
        tts_mod._VOICE_RATE, "de-DE-SeraphinaMultilingualNeural", "+25%"
    )
    asyncio.run(tts_mod._synthesize_edge("Hallo", "de-DE-SeraphinaMultilingualNeural"))
    assert captured[0]["rate"] == "+25%"


def test_rate_from_env_uses_valid_env_value(monkeypatch):
    monkeypatch.setenv("TTS_SERAPHINA_RATE", "+20%")
    assert tts_mod._rate_from_env("TTS_SERAPHINA_RATE", "+15%") == "+20%"
    monkeypatch.setenv("TTS_SERAPHINA_RATE", "-10%")
    assert tts_mod._rate_from_env("TTS_SERAPHINA_RATE", "+15%") == "-10%"


@pytest.mark.parametrize("bad", ["fast", "15%", "+15", "+1500%", "+15 %", "", "+%", "+15%; x"])
def test_rate_from_env_broken_value_falls_back_without_crash(monkeypatch, bad):
    monkeypatch.setenv("TTS_SERAPHINA_RATE", bad)
    assert tts_mod._rate_from_env("TTS_SERAPHINA_RATE", "+15%") == "+15%"


def test_rate_from_env_unset_returns_default(monkeypatch):
    monkeypatch.delenv("TTS_SERAPHINA_RATE", raising=False)
    assert tts_mod._rate_from_env("TTS_SERAPHINA_RATE", "+15%") == "+15%"


# ---------------------------------------------------------------------------
# Review-Fixes: Zeilen als Satzgrenzen, Stimme der Pre-Synthese, Engine-Gate in chat.py
# ---------------------------------------------------------------------------

def test_clean_for_speech_keep_newlines_preserves_single_line_breaks():
    out = tts_mod.clean_for_speech("Punkt eins\n\n\nPunkt zwei  mit   Leerraum\n- Punkt drei", keep_newlines=True)
    assert out == "Punkt eins\nPunkt zwei mit Leerraum\nPunkt drei"
    # Standard (Endpunkt) kollabiert weiterhin alles zu einer Zeile
    assert tts_mod.clean_for_speech("Punkt eins\n\nPunkt zwei") == "Punkt eins Punkt zwei"


def test_pre_synthesize_splits_lines_without_punctuation(synth_calls):
    """Zeilenweise Antworten (Listen/Briefing) ohne Satzzeichen werden zeilenweise gechunkt."""
    asyncio.run(tts_mod.pre_synthesize("Guten Morgen Mike\nHeute ist es sonnig\n- Termin um zehn"))
    assert [c[0] for c in synth_calls] == ["Guten Morgen Mike", "Heute ist es sonnig", "Termin um zehn"]


def test_pre_synthesize_uses_requested_voice_and_hits_cache_for_that_voice(client_with_mocks, synth_calls):
    asyncio.run(tts_mod.pre_synthesize("Hallo Mike.", "de-DE-SeraphinaMultilingualNeural"))
    assert synth_calls == [("Hallo Mike.", "de-DE-SeraphinaMultilingualNeural")]
    before = len(synth_calls)
    resp = client_with_mocks.post("/tts?voice=de-DE-SeraphinaMultilingualNeural", json={"text": "Hallo Mike."})
    assert resp.status_code == 200
    assert len(synth_calls) == before          # Treffer: gleiche Stimme
    client_with_mocks.post("/tts", json={"text": "Hallo Mike."})   # Katja → Miss (andere Stimme)
    assert len(synth_calls) == before + 1


@pytest.mark.parametrize("mode,engine,expected", [
    ("voice", "edge", True),
    ("voice", "piper", False),     # Piper-Wahl: NICHTS geht an Microsoft
    ("voice", None, False),        # alter Client ohne Angabe: sicherheitshalber nicht
    ("text", "edge", False),       # Text-Chat: nie
    (None, "edge", False),
])
def test_chat_presynth_gate(mode, engine, expected):
    from app.routes.chat import ChatIn, _wants_edge_presynth

    payload = ChatIn(message="Hallo", input_mode=mode, tts_engine=engine, tts_voice="de-DE-KatjaNeural")
    assert _wants_edge_presynth(payload) is expected


def test_chat_in_accepts_voice_fields_optional():
    from app.routes.chat import ChatIn

    p = ChatIn(message="Hallo")
    assert p.tts_engine is None and p.tts_voice is None and p.input_mode is None
