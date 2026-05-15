"""Tests für DONNA-31: Twitch Live Privacy Guard."""
from __future__ import annotations

import asyncio
from pathlib import Path
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from app.services.twitch_live_check import is_broadcaster_live, clear_cache
from app.services.prompt_builder import sanitize_ltm_content, PromptBuilder, PromptContext


# ---------------------------------------------------------------------------
# twitch_live_check: API-Mocks
# ---------------------------------------------------------------------------


@pytest.fixture(autouse=True)
def reset_cache():
    """Vor jedem Test den Cache leeren."""
    clear_cache()
    yield
    clear_cache()


def _make_streams_response(live: bool, status_code: int = 200):
    """Erstellt einen Mock-httpx-Response für den Helix /streams Endpoint."""
    mock_resp = MagicMock()
    mock_resp.status_code = status_code
    if live:
        mock_resp.json.return_value = {"data": [{"type": "live", "user_login": "your-twitch-channel"}]}
    else:
        mock_resp.json.return_value = {"data": []}
    return mock_resp


def _make_token_response():
    mock_resp = MagicMock()
    mock_resp.status_code = 200
    mock_resp.json.return_value = {"access_token": "test_token_abc", "expires_in": 3600}
    return mock_resp


@pytest.mark.asyncio
async def test_live_check_broadcaster_is_live():
    """Mock Twitch API live=true → is_broadcaster_live() gibt True zurück."""
    token_resp = _make_token_response()
    streams_resp = _make_streams_response(live=True)

    with patch("httpx.AsyncClient") as mock_client_cls:
        mock_client = AsyncMock()
        mock_client.__aenter__ = AsyncMock(return_value=mock_client)
        mock_client.__aexit__ = AsyncMock(return_value=False)
        mock_client.post = AsyncMock(return_value=token_resp)
        mock_client.get = AsyncMock(return_value=streams_resp)
        mock_client_cls.return_value = mock_client

        result = await is_broadcaster_live(
            broadcaster_login="your-twitch-channel",
            client_id="test_client_id",
            client_secret="test_client_secret",
        )

    assert result is True


@pytest.mark.asyncio
async def test_live_check_broadcaster_is_offline():
    """Mock Twitch API live=false → is_broadcaster_live() gibt False zurück."""
    token_resp = _make_token_response()
    streams_resp = _make_streams_response(live=False)

    with patch("httpx.AsyncClient") as mock_client_cls:
        mock_client = AsyncMock()
        mock_client.__aenter__ = AsyncMock(return_value=mock_client)
        mock_client.__aexit__ = AsyncMock(return_value=False)
        mock_client.post = AsyncMock(return_value=token_resp)
        mock_client.get = AsyncMock(return_value=streams_resp)
        mock_client_cls.return_value = mock_client

        result = await is_broadcaster_live(
            broadcaster_login="your-twitch-channel",
            client_id="test_client_id",
            client_secret="test_client_secret",
        )

    assert result is False


@pytest.mark.asyncio
async def test_live_check_timeout_failsafe():
    """Mock Twitch API timeout → fail-safe live=True (konservativ)."""
    import httpx

    token_resp = _make_token_response()

    with patch("httpx.AsyncClient") as mock_client_cls:
        mock_client = AsyncMock()
        mock_client.__aenter__ = AsyncMock(return_value=mock_client)
        mock_client.__aexit__ = AsyncMock(return_value=False)
        mock_client.post = AsyncMock(return_value=token_resp)
        mock_client.get = AsyncMock(side_effect=httpx.TimeoutException("timeout"))
        mock_client_cls.return_value = mock_client

        result = await is_broadcaster_live(
            broadcaster_login="your-twitch-channel",
            client_id="test_client_id",
            client_secret="test_client_secret",
        )

    assert result is True


@pytest.mark.asyncio
async def test_live_check_no_credentials_failsafe():
    """Keine Credentials → fail-safe live=True."""
    result = await is_broadcaster_live(
        broadcaster_login="your-twitch-channel",
        client_id=None,
        client_secret=None,
    )
    assert result is True


@pytest.mark.asyncio
async def test_live_check_api_error_failsafe():
    """API antwortet mit 503 → fail-safe live=True."""
    token_resp = _make_token_response()
    error_resp = _make_streams_response(live=False, status_code=503)

    with patch("httpx.AsyncClient") as mock_client_cls:
        mock_client = AsyncMock()
        mock_client.__aenter__ = AsyncMock(return_value=mock_client)
        mock_client.__aexit__ = AsyncMock(return_value=False)
        mock_client.post = AsyncMock(return_value=token_resp)
        mock_client.get = AsyncMock(return_value=error_resp)
        mock_client_cls.return_value = mock_client

        result = await is_broadcaster_live(
            broadcaster_login="your-twitch-channel",
            client_id="test_client_id",
            client_secret="test_client_secret",
        )

    assert result is True


# ---------------------------------------------------------------------------
# prompt_builder: Koordinaten-Sanitizer
# ---------------------------------------------------------------------------


def test_sanitize_ltm_content_removes_coords():
    """Koordinaten in LTM-Inhalten werden durch [Standort] ersetzt."""
    raw = "Fragte nach Wetter bei sich zu Hause (YOUR_LAT,YOUR_LON)"
    sanitized = sanitize_ltm_content(raw)
    assert "YOUR_LAT" not in sanitized
    assert "YOUR_LON" not in sanitized
    assert "[Standort]" in sanitized


def test_sanitize_ltm_content_preserves_normal_text():
    """Normaler Text ohne Koordinaten bleibt unverändert."""
    raw = "Lieblingsfilm: The Matrix. Wohnt in YOUR_HOME_CITY."
    assert sanitize_ltm_content(raw) == raw


def test_sanitize_ltm_content_multiple_coords():
    """Mehrere Koordinaten-Vorkommen werden alle ersetzt."""
    raw = "Standort 1: YOUR_LAT,YOUR_LON und Standort 2: 48.13743,11.57549"
    sanitized = sanitize_ltm_content(raw)
    assert "YOUR_LAT" not in sanitized
    assert "48.13743" not in sanitized
    assert sanitized.count("[Standort]") == 2


def test_prompt_builder_sanitizes_ltm_in_user_prompt():
    """PromptBuilder.build_user_prompt() sanitiert LTM-Inhalte."""
    builder = PromptBuilder()
    ctx = PromptContext(
        message="wie ist das wetter bei mir",
        ltm_memories=[
            {"category": "Fakt", "content": "Fragte nach Wetter (YOUR_LAT,YOUR_LON)"},
        ],
    )
    prompt = builder.build_user_prompt(ctx, include_history=False)
    assert "YOUR_LAT" not in prompt
    assert "YOUR_LON" not in prompt
    assert "[Standort]" in prompt


def test_prompt_builder_sanitizes_ltm_in_messages():
    """PromptBuilder.build_messages() sanitiert LTM-Inhalte im System-Prompt."""
    builder = PromptBuilder()
    ctx = PromptContext(
        message="wie ist das wetter bei mir",
        ltm_memories=[
            {"category": "Fakt", "content": "Koordinaten: YOUR_LAT,YOUR_LON"},
        ],
    )
    messages = builder.build_messages(ctx, system_prompt="Du bist Donna.")
    system_msg = messages[0]["content"]
    assert "YOUR_LAT" not in system_msg
    assert "[Standort]" in system_msg


# ---------------------------------------------------------------------------
# live_output_filter (Unit-Test)
# ---------------------------------------------------------------------------


def test_live_output_filter_removes_coords():
    """_live_output_filter ersetzt Koordinaten durch [geblockt: live]."""
    from app.routes.chat import _live_output_filter
    text = "Dein Standort ist YOUR_LAT,YOUR_LON — schönes Wetter dort!"
    result = _live_output_filter(text, location_city="YOUR_HOME_CITY")
    assert "YOUR_LAT" not in result
    assert "[geblockt: live]" in result


def test_live_output_filter_removes_city():
    """_live_output_filter ersetzt bekannte Stadtname durch [geblockt: live]."""
    from app.routes.chat import _live_output_filter
    text = "Das Wetter in YOUR_HOME_CITY ist sonnig mit 18°C."
    result = _live_output_filter(text, location_city="YOUR_HOME_CITY")
    assert "YOUR_HOME_CITY" not in result
    assert "[geblockt: live]" in result


def test_live_output_filter_passthrough_no_private():
    """_live_output_filter lässt allgemeine Texte unverändert."""
    from app.routes.chat import _live_output_filter
    text = "Das Spiel von heute war sehr spannend!"
    result = _live_output_filter(text, location_city=None)
    assert result == text


# ---------------------------------------------------------------------------
# DONNA-40 (Override): _classify_privacy_risk — semantische Klassifikation
# Keine Keyword-Liste. Guard-Entscheidung basiert auf Datenfluss:
#   Trigger 1: GPS im Payload (lat/lon)
#   Trigger 2: LTM/Vector-Hits enthalten echte PII
# ---------------------------------------------------------------------------


def _make_payload(lat=None, lon=None, message="hallo", client="windows"):
    """Hilfsfunktion: ChatIn-ähnliches Objekt ohne echten Pydantic-Import."""
    class _FakePayload:
        pass
    p = _FakePayload()
    p.lat = lat
    p.lon = lon
    p.message = message
    p.client = client
    return p


# --- Trigger 1: GPS-basiert ---

def test_classify_privacy_hallo_live_no_gps_no_ltm():
    """DONNA-40: 'hallo' + live=true + keine GPS + kein LTM → KEIN Guard."""
    from app.routes.chat import _classify_privacy_risk
    payload = _make_payload(message="hallo")
    assert _classify_privacy_risk(payload, [], [], is_live=True) is False


def test_classify_privacy_wetter_with_latlon_triggers():
    """DONNA-40: 'Wie ist das Wetter bei mir?' + live=true + lat/lon → Guard."""
    from app.routes.chat import _classify_privacy_risk
    payload = _make_payload(lat=YOUR_LAT, lon=YOUR_LON, message="Wie ist das Wetter bei mir?")
    assert _classify_privacy_risk(payload, [], [], is_live=True) is True


def test_classify_privacy_no_trigger_when_offline():
    """DONNA-40: is_live=false → KEIN Guard, egal was im Payload."""
    from app.routes.chat import _classify_privacy_risk
    payload = _make_payload(lat=YOUR_LAT, lon=YOUR_LON, message="Navigation nach Hause")
    assert _classify_privacy_risk(payload, [], [], is_live=False) is False


def test_classify_privacy_latlon_none_no_trigger():
    """DONNA-40: Frage 'wo bin ich' ohne lat/lon + kein LTM → KEIN Guard."""
    from app.routes.chat import _classify_privacy_risk
    payload = _make_payload(message="wo bin ich gerade?")
    assert _classify_privacy_risk(payload, [], [], is_live=True) is False


# --- Trigger 2: LTM-PII-basiert ---

def test_classify_privacy_ltm_hit_with_contact_triggers():
    """DONNA-40: LTM-Hit enthält bekannten Kontaktnamen → Guard (via contacts.json mock)."""
    from unittest.mock import patch
    from app.routes.chat import _classify_privacy_risk
    payload = _make_payload(message="schreib Ämi")
    ltm_hits = [{"content": "Ämi ist Mikes Freundin, Kontakt gespeichert"}]
    # Patch _load_contact_names um contacts.json-Abhängigkeit zu umgehen
    with patch("app.routes.chat._load_contact_names", return_value=("Ämi", "Mama", "Papa")):
        result = _classify_privacy_risk(payload, ltm_hits, [], is_live=True)
    assert result is True


def test_classify_privacy_ltm_hit_with_iban_triggers():
    """DONNA-40: LTM-Hit enthält IBAN → Guard."""
    from app.routes.chat import _classify_privacy_risk
    payload = _make_payload(message="irgendwas")
    ltm_hits = [{"content": "Kontonummer DE12 3456 7890 1234 5678 90 bei Sparkasse"}]
    with patch("app.routes.chat._load_contact_names", return_value=()):
        result = _classify_privacy_risk(payload, ltm_hits, [], is_live=True)
    assert result is True


def test_classify_privacy_vector_hit_with_coords_triggers():
    """DONNA-40: Vector-Hit enthält GPS-Koordinaten → Guard."""
    from unittest.mock import patch
    from app.routes.chat import _classify_privacy_risk
    payload = _make_payload(message="wo gehe ich manchmal einkaufen?")
    vector_hits = [{"text": "Lieblingsmarkt bei 49.1234, 10.5678 — Edeka YOUR_HOME_CITY"}]
    with patch("app.routes.chat._load_contact_names", return_value=()):
        result = _classify_privacy_risk(payload, [], vector_hits, is_live=True)
    assert result is True


def test_classify_privacy_generic_ltm_no_pii_no_trigger():
    """DONNA-40: LTM-Hit enthält keine PII → KEIN Guard."""
    from unittest.mock import patch
    from app.routes.chat import _classify_privacy_risk
    payload = _make_payload(message="was mag ich zum frühstück?")
    ltm_hits = [{"content": "Mike mag morgens Müsli mit Beeren"}]
    with patch("app.routes.chat._load_contact_names", return_value=()):
        result = _classify_privacy_risk(payload, ltm_hits, [], is_live=True)
    assert result is False


# --- _contains_pii Unit-Tests ---

def test_contains_pii_gps_match():
    """_contains_pii erkennt GPS-Koordinaten."""
    from app.routes.chat import _contains_pii
    with patch("app.routes.chat._load_contact_names", return_value=()):
        assert _contains_pii("Standort: 49.1234, 10.5678") is True


def test_contains_pii_iban_match():
    """_contains_pii erkennt deutsche IBAN."""
    from app.routes.chat import _contains_pii
    with patch("app.routes.chat._load_contact_names", return_value=()):
        assert _contains_pii("IBAN: DE89370400440532013000") is True


def test_contains_pii_phone_match():
    """_contains_pii erkennt internationale Telefonnummer."""
    from app.routes.chat import _contains_pii
    with patch("app.routes.chat._load_contact_names", return_value=()):
        assert _contains_pii("Ruf mich an: +49 171 12345678") is True


def test_contains_pii_plz_match():
    """_contains_pii erkennt PLZ + Ort."""
    from app.routes.chat import _contains_pii
    with patch("app.routes.chat._load_contact_names", return_value=()):
        assert _contains_pii("Wohnort: YOUR_PLZ YOUR_HOME_CITY") is True


def test_contains_pii_clean_text_no_match():
    """_contains_pii gibt False für allgemeinen Text."""
    from app.routes.chat import _contains_pii
    with patch("app.routes.chat._load_contact_names", return_value=()):
        assert _contains_pii("Das Wetter heute ist sonnig und warm.") is False


# --- Rückwärtskompatibilität: client-basiertes Blocking ---

def test_live_guard_android_client_no_block():
    """DONNA-40: client=android → Guard greift nie (unabhängig von GPS/PII)."""
    # Die client==windows Prüfung liegt im Route-Handler, nicht in _classify_privacy_risk.
    # Sicherstellen dass _classify_privacy_risk selbst client-agnostisch ist.
    from app.routes.chat import _classify_privacy_risk
    payload = _make_payload(lat=YOUR_LAT, lon=YOUR_LON, client="android")
    # classify selbst triggert auf GPS (client-Filter ist außerhalb)
    assert _classify_privacy_risk(payload, [], [], is_live=True) is True
    # → Route-Handler prüft client == "windows" VOR dem classify-Aufruf


# --- DONNA-41: LTM-Behavioral-Rule-Filter (Live-Mode-Verhaltensregeln) ---

def test_live_rule_re_matches_text_only_rules():
    """_LIVE_RULE_RE erkennt typische 'antworte nur per Text wenn live'-Memories."""
    from app.routes.chat import _LIVE_RULE_RE
    positives = [
        "Wenn Mike live ist, antworte nur per Text und nicht per Sprache.",
        "Während des Streams: keine Sprachausgabe, nur Text.",
        "Bei Live: nicht vorlesen.",
        "Live → text-only Modus aktivieren.",
        "Antworte nur in Schriftform wenn er streamt.",
        "Nur per text antworten.",
    ]
    for text in positives:
        assert _LIVE_RULE_RE.search(text) is not None, f"Sollte matchen: {text!r}"


def test_live_rule_re_no_false_positives():
    """_LIVE_RULE_RE löst NICHT bei harmlosen Texten aus."""
    from app.routes.chat import _LIVE_RULE_RE
    negatives = [
        "Mike mag Pizza und Kaffee.",
        "Wetter heute: leicht bewölkt, 12°C.",
        "Hier ist eine Liste mit Einkäufen.",
        "Donna kann auch deutsch sprechen.",
    ]
    for text in negatives:
        assert _LIVE_RULE_RE.search(text) is None, f"Sollte NICHT matchen: {text!r}"


def test_live_ltm_filter_removes_behavioral_rules():
    """Filter-Logik entfernt LTM-Memories mit Live-Verhaltensregeln."""
    from app.routes.chat import _LIVE_RULE_RE, _LIVE_OUTPUT_COORD_RE, _contains_pii
    _LIVE_BLOCKED_CATEGORIES = {"private", "family", "health", "finance", "contact", "address"}
    ltm = [
        {"category": "preference", "content": "Mike mag dunkles UI."},                    # bleibt
        {"category": "rule", "content": "Wenn live, antworte nur per Text."},             # raus (Behavioral)
        {"category": "fact", "content": "Berlin ist die Hauptstadt von Deutschland."},    # bleibt
        {"category": "private", "content": "Geburtstag am 1.1."},                          # raus (Kategorie)
    ]
    with patch("app.routes.chat._load_contact_names", return_value=()):
        filtered = [
            m for m in ltm
            if m.get("category") not in _LIVE_BLOCKED_CATEGORIES
            and not _LIVE_OUTPUT_COORD_RE.search(m.get("content", ""))
            and not _contains_pii(m.get("content", ""))
            and not _LIVE_RULE_RE.search(m.get("content", ""))
        ]
    contents = [m["content"] for m in filtered]
    assert "Mike mag dunkles UI." in contents
    assert "Berlin ist die Hauptstadt von Deutschland." in contents
    assert all("nur per Text" not in c for c in contents)
    assert all("Geburtstag" not in c for c in contents)
    assert len(filtered) == 2
