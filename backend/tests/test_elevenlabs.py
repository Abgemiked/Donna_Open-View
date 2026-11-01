"""Tests für ElevenLabs-TTS (TB1) — primäre Cloud-Stimme mit Edge als Fallback.

KEIN echter Netzcall: ElevenLabs wird via httpx.MockTransport bzw. Monkeypatch gemockt.
Deckt ab:
1. _synthesize_elevenlabs: Erfolg → MP3; korrekter Endpoint/Header/Body/Output-Format
2. IPv4-Transport (local_address="YOUR_SERVER_IP") konfiguriert
3. Provider-Router: Key vorhanden + Erfolg → ElevenLabs (Edge NICHT aufgerufen)
4. 401/403/429/Fehler → Edge-Fallback aufgerufen
5. Key fehlt → direkt Edge
6. pre_synthesize credit-sparsam bei ElevenLabs: nur der ERSTE Satz vorab
7. Endpoint /tts: Key + Erfolg → 200 audio/mpeg; Key + 401 → Fallback → 200
"""
from __future__ import annotations

import asyncio
import json

import httpx
import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.routes import tts as tts_mod

FAKE_MP3 = b"\xff\xfb\x90\x00" + b"\x00" * 64
EDGE_MP3 = b"\xff\xfb\x90\x00EDGE"


@pytest.fixture(autouse=True)
def _clean_state():
    tts_mod._cache.clear()
    tts_mod._inflight.clear()
    tts_mod._semaphore = None
    tts_mod._el_backoff_until = 0.0  # Backoff zwischen Tests zuruecksetzen
    yield
    tts_mod._cache.clear()
    tts_mod._inflight.clear()
    tts_mod._semaphore = None
    tts_mod._el_backoff_until = 0.0


@pytest.fixture
def edge_calls(monkeypatch):
    calls: list[tuple[str, str]] = []

    async def fake_edge(text: str, voice: str) -> bytes:
        calls.append((text, voice))
        return EDGE_MP3

    monkeypatch.setattr(tts_mod, "_synthesize_edge", fake_edge)
    return calls


# ---------------------------------------------------------------------------
# 1. _synthesize_elevenlabs — Erfolg + korrekter Request
# ---------------------------------------------------------------------------

def test_synthesize_elevenlabs_success_returns_mp3(monkeypatch):
    monkeypatch.setattr(tts_mod, "_elevenlabs_key", lambda: "secret-key")
    captured: dict = {}

    def handler(request: httpx.Request) -> httpx.Response:
        captured["url"] = str(request.url)
        captured["path"] = request.url.path
        captured["xi_api_key"] = request.headers.get("xi-api-key")
        captured["output_format"] = request.url.params.get("output_format")
        captured["body"] = json.loads(request.content)
        return httpx.Response(200, content=FAKE_MP3, headers={"content-type": "audio/mpeg"})

    client = httpx.AsyncClient(transport=httpx.MockTransport(handler))

    async def run():
        try:
            return await tts_mod._synthesize_elevenlabs(
                "Hallo Mike", "voiceXYZ", "eleven_v4_turbo", client=client
            )
        finally:
            await client.aclose()

    out = asyncio.run(run())
    assert out == FAKE_MP3
    assert captured["path"] == "/v1/text-to-speech/voiceXYZ"
    assert captured["xi_api_key"] == "secret-key"
    assert captured["output_format"].startswith("mp3_")
    assert captured["body"]["text"] == "Hallo Mike"
    assert captured["body"]["model_id"] == "eleven_v4_turbo"


def test_synthesize_elevenlabs_401_raises_elevenlabs_error(monkeypatch):
    monkeypatch.setattr(tts_mod, "_elevenlabs_key", lambda: "secret-key")

    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(401, content=b"unauthorized")

    client = httpx.AsyncClient(transport=httpx.MockTransport(handler))

    async def run():
        try:
            return await tts_mod._synthesize_elevenlabs("Hi", "v", "eleven_v4_turbo", client=client)
        finally:
            await client.aclose()

    with pytest.raises(tts_mod.ElevenLabsError) as ei:
        asyncio.run(run())
    assert ei.value.status == 401


def test_synthesize_elevenlabs_no_key_raises(monkeypatch):
    monkeypatch.setattr(tts_mod, "_elevenlabs_key", lambda: None)
    with pytest.raises(tts_mod.ElevenLabsError):
        asyncio.run(tts_mod._synthesize_elevenlabs("Hi", "v", "eleven_v4_turbo"))


def test_synthesize_elevenlabs_never_logs_key(monkeypatch):
    import inspect
    src = inspect.getsource(tts_mod._synthesize_elevenlabs)
    # Key darf nie geloggt werden: kein log.*(... key ...) und kein header-dump
    assert "xi-api-key" in src  # header wird gesetzt
    # heuristisch: der Key wird nicht in einen log-Aufruf gegeben
    assert "log.info(\"tts" not in src or "key" not in src.split("log")[-1].lower()[:200]


# ---------------------------------------------------------------------------
# 2. IPv4-Transport
# ---------------------------------------------------------------------------

def test_elevenlabs_local_address_is_ipv4():
    assert tts_mod._EL_LOCAL_ADDRESS == "YOUR_SERVER_IP"


def test_elevenlabs_transport_sets_local_address(monkeypatch):
    captured: dict = {}
    real = httpx.AsyncHTTPTransport

    class CapturingTransport(real):
        def __init__(self, *args, **kwargs):
            captured.update(kwargs)
            super().__init__(*args, **kwargs)

    monkeypatch.setattr(httpx, "AsyncHTTPTransport", CapturingTransport)
    tts_mod._elevenlabs_transport()
    assert captured.get("local_address") == "YOUR_SERVER_IP"


# ---------------------------------------------------------------------------
# 3./4./5. Provider-Router mit Fallback
# ---------------------------------------------------------------------------

def test_router_elevenlabs_success_edge_not_called(monkeypatch, edge_calls):
    monkeypatch.setattr(tts_mod, "_elevenlabs_key", lambda: "k")

    async def fake_el(text, voice_id, model_id, **kw):
        return FAKE_MP3

    monkeypatch.setattr(tts_mod, "_synthesize_elevenlabs", fake_el)
    audio, provider = asyncio.run(tts_mod.synthesize_tts_audio(
        "Hallo", edge_voice="de-DE-KatjaNeural", el_voice_id="voiceX", model_id="eleven_v4_turbo"
    ))
    assert audio == FAKE_MP3
    assert provider == "elevenlabs"
    assert edge_calls == []  # Edge NICHT aufgerufen


@pytest.mark.parametrize("status", [401, 403, 429])
def test_router_elevenlabs_error_falls_back_to_edge(monkeypatch, edge_calls, status):
    monkeypatch.setattr(tts_mod, "_elevenlabs_key", lambda: "k")

    async def boom(text, voice_id, model_id, **kw):
        raise tts_mod.ElevenLabsError(f"status {status}", status=status)

    monkeypatch.setattr(tts_mod, "_synthesize_elevenlabs", boom)
    audio, provider = asyncio.run(tts_mod.synthesize_tts_audio(
        "Hallo", edge_voice="de-DE-KatjaNeural", el_voice_id="voiceX", model_id="eleven_v4_turbo"
    ))
    assert audio == EDGE_MP3
    assert provider == "edge"
    assert edge_calls == [("Hallo", "de-DE-KatjaNeural")]


def test_router_elevenlabs_unexpected_error_falls_back_to_edge(monkeypatch, edge_calls):
    """Fix-Runde A #1: auch ein NICHT-ElevenLabsError (z.B. ValueError beim Header-Encoding)
    darf nicht als 500 durchlaufen — es MUSS auf Edge zurueckfallen."""
    monkeypatch.setattr(tts_mod, "_elevenlabs_key", lambda: "k")

    async def boom(text, voice_id, model_id, **kw):
        raise ValueError("Header-Encoding kaputt")

    monkeypatch.setattr(tts_mod, "_synthesize_elevenlabs", boom)
    audio, provider = asyncio.run(tts_mod.synthesize_tts_audio(
        "Hallo", edge_voice="de-DE-KatjaNeural", el_voice_id="voiceX", model_id="eleven_v4_turbo"
    ))
    assert audio == EDGE_MP3
    assert provider == "edge"
    assert edge_calls == [("Hallo", "de-DE-KatjaNeural")]


def test_router_no_key_goes_direct_to_edge(monkeypatch, edge_calls):
    monkeypatch.setattr(tts_mod, "_elevenlabs_key", lambda: None)
    called = {"el": 0}

    async def fake_el(*a, **k):
        called["el"] += 1
        return FAKE_MP3

    monkeypatch.setattr(tts_mod, "_synthesize_elevenlabs", fake_el)
    audio, provider = asyncio.run(tts_mod.synthesize_tts_audio(
        "Hallo", edge_voice="de-DE-KatjaNeural", el_voice_id="voiceX", model_id="eleven_v4_turbo"
    ))
    assert audio == EDGE_MP3
    assert provider == "edge"
    assert called["el"] == 0
    assert edge_calls == [("Hallo", "de-DE-KatjaNeural")]


# ---------------------------------------------------------------------------
# Fix-Runde A #4: Negativ-Cache/Backoff nach 401/403/429
# ---------------------------------------------------------------------------

def test_backoff_after_429_skips_elevenlabs_on_next_call(monkeypatch, edge_calls):
    monkeypatch.setattr(tts_mod, "_elevenlabs_key", lambda: "k")
    el_calls = {"n": 0}

    async def boom_429(text, voice_id, model_id, **kw):
        el_calls["n"] += 1
        raise tts_mod.ElevenLabsError("429", status=429)

    monkeypatch.setattr(tts_mod, "_synthesize_elevenlabs", boom_429)

    async def run_twice():
        a1 = await tts_mod.synthesize_tts_audio(
            "Eins", edge_voice="de-DE-KatjaNeural", el_voice_id="v", model_id="eleven_v4_turbo")
        a2 = await tts_mod.synthesize_tts_audio(
            "Zwei", edge_voice="de-DE-KatjaNeural", el_voice_id="v", model_id="eleven_v4_turbo")
        return a1, a2

    (a1, p1), (a2, p2) = asyncio.run(run_twice())
    assert p1 == "edge" and p2 == "edge"
    assert el_calls["n"] == 1  # 2. Call versucht EL gar nicht erst (Backoff aktiv)
    assert len(edge_calls) == 2


def test_backoff_not_triggered_by_transient_500(monkeypatch, edge_calls):
    """500/Netzfehler sind transient → KEIN Backoff (darf gleich wieder EL versuchen)."""
    monkeypatch.setattr(tts_mod, "_elevenlabs_key", lambda: "k")
    el_calls = {"n": 0}

    async def boom_500(text, voice_id, model_id, **kw):
        el_calls["n"] += 1
        raise tts_mod.ElevenLabsError("500", status=500)

    monkeypatch.setattr(tts_mod, "_synthesize_elevenlabs", boom_500)

    async def run_twice():
        await tts_mod.synthesize_tts_audio(
            "Eins", edge_voice="de-DE-KatjaNeural", el_voice_id="v", model_id="eleven_v4_turbo")
        await tts_mod.synthesize_tts_audio(
            "Zwei", edge_voice="de-DE-KatjaNeural", el_voice_id="v", model_id="eleven_v4_turbo")

    asyncio.run(run_twice())
    assert el_calls["n"] == 2  # beide Male EL versucht (kein Backoff bei 500)


# ---------------------------------------------------------------------------
# Fix-Runde A #2/#3: trust_env=False + 6s-Timeout
# ---------------------------------------------------------------------------

def test_elevenlabs_client_disables_trust_env(monkeypatch):
    captured: dict = {}
    real = httpx.AsyncClient

    class CapturingClient(real):
        def __init__(self, *args, **kwargs):
            captured.update(kwargs)
            super().__init__(*args, **kwargs)

    monkeypatch.setattr(httpx, "AsyncClient", CapturingClient)
    client = tts_mod._build_elevenlabs_client()
    try:
        assert captured.get("trust_env") is False
    finally:
        asyncio.run(client.aclose())


def test_elevenlabs_timeout_is_six_seconds():
    assert tts_mod._EL_TIMEOUT_S == 6.0


# ---------------------------------------------------------------------------
# 6. pre_synthesize credit-sparsam bei ElevenLabs
# ---------------------------------------------------------------------------

def test_pre_synthesize_elevenlabs_only_first_sentence(monkeypatch):
    monkeypatch.setattr(tts_mod, "_elevenlabs_key", lambda: "k")
    calls: list[str] = []

    async def fake_el(text, voice_id, model_id, **kw):
        calls.append(text)
        return FAKE_MP3

    monkeypatch.setattr(tts_mod, "_synthesize_elevenlabs", fake_el)
    asyncio.run(tts_mod.pre_synthesize("Satz eins. Satz zwei. Satz drei."))
    assert calls == ["Satz eins."]  # NUR der erste Satz vorab (Credit-Schonung)


def test_pre_synthesize_edge_unchanged_without_key(monkeypatch):
    monkeypatch.setattr(tts_mod, "_elevenlabs_key", lambda: None)
    calls: list[str] = []

    async def fake_edge(text, voice):
        calls.append(text)
        return EDGE_MP3

    monkeypatch.setattr(tts_mod, "_synthesize_edge", fake_edge)
    asyncio.run(tts_mod.pre_synthesize("Satz eins. Satz zwei. Satz drei."))
    assert calls == ["Satz eins.", "Satz zwei.", "Satz drei."]  # Edge: alle Sätze wie bisher


# ---------------------------------------------------------------------------
# 7. Endpoint-Integration
# ---------------------------------------------------------------------------

@pytest.fixture
def client_with_mocks():
    from app.core.auth import require_admin

    test_app = FastAPI()
    test_app.include_router(tts_mod.router)
    test_app.dependency_overrides[require_admin] = lambda: "admin"
    with TestClient(test_app) as c:
        yield c


def test_endpoint_uses_elevenlabs_when_key_present(client_with_mocks, monkeypatch, edge_calls):
    monkeypatch.setattr(tts_mod, "_elevenlabs_key", lambda: "k")

    async def fake_el(text, voice_id, model_id, **kw):
        return FAKE_MP3

    monkeypatch.setattr(tts_mod, "_synthesize_elevenlabs", fake_el)
    resp = client_with_mocks.post("/tts", json={"text": "Hallo Mike"})
    assert resp.status_code == 200
    assert resp.headers["content-type"].startswith("audio/mpeg")
    assert resp.content == FAKE_MP3
    assert edge_calls == []


def test_endpoint_falls_back_to_edge_on_elevenlabs_401(client_with_mocks, monkeypatch, edge_calls):
    monkeypatch.setattr(tts_mod, "_elevenlabs_key", lambda: "k")

    async def boom(text, voice_id, model_id, **kw):
        raise tts_mod.ElevenLabsError("401", status=401)

    monkeypatch.setattr(tts_mod, "_synthesize_elevenlabs", boom)
    resp = client_with_mocks.post("/tts", json={"text": "Hallo Mike"})
    assert resp.status_code == 200
    assert resp.content == EDGE_MP3
    assert edge_calls and edge_calls[0][1] == "de-DE-KatjaNeural"
