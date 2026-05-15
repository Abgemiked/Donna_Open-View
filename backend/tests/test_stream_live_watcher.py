"""Unit-Tests für DONNA-112: stream_live_watcher.py

Testet State-Transition-Logik und verhindert Doppel-Send.
"""
from __future__ import annotations

import pytest
from unittest.mock import AsyncMock, MagicMock, patch


# ── Fixtures ──────────────────────────────────────────────────────────────────

def make_channel_mock():
    """Erstellt ein Mock-Channel-Objekt mit send()-Methode."""
    channel = MagicMock()
    channel.send = AsyncMock()
    return channel


def make_bot_service_mock(channel=None):
    """Erstellt einen Mock-TwitchBotService der get_channel() zurückgibt."""
    svc = MagicMock()
    svc.get_channel = MagicMock(return_value=channel)
    return svc


def make_stream_data(game_name: str = "Hearthstone") -> dict:
    """Fake Helix-Stream-Daten."""
    return {
        "id": "123456",
        "user_login": "your-twitch-channel",
        "game_name": game_name,
        "title": "Test Stream",
        "viewer_count": 42,
    }


# ── Tests ─────────────────────────────────────────────────────────────────────

@pytest.mark.asyncio
async def test_offline_to_live_sends_exactly_once():
    """offline→live: channel.send() genau 1x aufgerufen."""
    import app.jobs.stream_live_watcher as watcher
    watcher.reset_state_for_testing()

    channel = make_channel_mock()
    bot_svc = make_bot_service_mock(channel)
    stream_data = make_stream_data("Minecraft")

    with (
        patch.object(watcher, "DONNA_TWITCH_PROACTIVE_ENABLED", True),
        patch(
            "app.jobs.stream_live_watcher._fetch_live_status",
            AsyncMock(return_value=None),  # Erster Lauf: offline → State init
        ) as mock_fetch,
    ):
        # Erster Lauf: offline (State-Init, kein Send)
        await watcher.check_and_notify(
            twitch_bot_service=bot_svc,
            gemini_client=None,
            broadcaster_login="your-twitch-channel",
            client_id="test-client-id",
            access_token="test-token",
        )
        assert channel.send.call_count == 0

        # Zweiter Lauf: live (offline→live Wechsel)
        mock_fetch.return_value = stream_data
        await watcher.check_and_notify(
            twitch_bot_service=bot_svc,
            gemini_client=None,
            broadcaster_login="your-twitch-channel",
            client_id="test-client-id",
            access_token="test-token",
        )
        assert channel.send.call_count == 1
        sent_msg: str = channel.send.call_args[0][0]
        assert len(sent_msg) > 0


@pytest.mark.asyncio
async def test_live_to_live_no_double_send():
    """live→live: kein Doppel-Send wenn Zustand unverändert."""
    import app.jobs.stream_live_watcher as watcher
    watcher.reset_state_for_testing()

    channel = make_channel_mock()
    bot_svc = make_bot_service_mock(channel)
    stream_data = make_stream_data("Hearthstone")

    with (
        patch.object(watcher, "DONNA_TWITCH_PROACTIVE_ENABLED", True),
        patch(
            "app.jobs.stream_live_watcher._fetch_live_status",
            AsyncMock(return_value=stream_data),
        ),
    ):
        # Erster Lauf: live (State-Init)
        await watcher.check_and_notify(
            twitch_bot_service=bot_svc,
            gemini_client=None,
            broadcaster_login="your-twitch-channel",
            client_id="test-client-id",
            access_token="test-token",
        )
        assert channel.send.call_count == 0  # Erster Lauf immer kein Send

        # Zweiter Lauf: live (kein Wechsel → kein Send)
        await watcher.check_and_notify(
            twitch_bot_service=bot_svc,
            gemini_client=None,
            broadcaster_login="your-twitch-channel",
            client_id="test-client-id",
            access_token="test-token",
        )
        assert channel.send.call_count == 0

        # Dritter Lauf: immer noch live (kein Wechsel)
        await watcher.check_and_notify(
            twitch_bot_service=bot_svc,
            gemini_client=None,
            broadcaster_login="your-twitch-channel",
            client_id="test-client-id",
            access_token="test-token",
        )
        assert channel.send.call_count == 0


@pytest.mark.asyncio
async def test_live_to_offline_sends_farewell():
    """live→offline: channel.send() genau 1x mit Verabschiedung."""
    import app.jobs.stream_live_watcher as watcher
    watcher.reset_state_for_testing()

    channel = make_channel_mock()
    bot_svc = make_bot_service_mock(channel)
    stream_data = make_stream_data("Valorant")

    with (
        patch.object(watcher, "DONNA_TWITCH_PROACTIVE_ENABLED", True),
        patch(
            "app.jobs.stream_live_watcher._fetch_live_status",
            AsyncMock(return_value=stream_data),  # Erster Lauf: live → State init
        ) as mock_fetch,
    ):
        # Erster Lauf: live (State-Init, kein Send)
        await watcher.check_and_notify(
            twitch_bot_service=bot_svc,
            gemini_client=None,
            broadcaster_login="your-twitch-channel",
            client_id="test-client-id",
            access_token="test-token",
        )
        assert channel.send.call_count == 0

        # Zweiter Lauf: offline (live→offline Wechsel)
        mock_fetch.return_value = None
        await watcher.check_and_notify(
            twitch_bot_service=bot_svc,
            gemini_client=None,
            broadcaster_login="your-twitch-channel",
            client_id="test-client-id",
            access_token="test-token",
        )
        assert channel.send.call_count == 1
        sent_msg: str = channel.send.call_args[0][0]
        assert len(sent_msg) > 0


@pytest.mark.asyncio
async def test_feature_flag_off_no_send():
    """Feature-Flag DONNA_TWITCH_PROACTIVE=false: kein Send, kein Fehler."""
    import app.jobs.stream_live_watcher as watcher
    watcher.reset_state_for_testing()

    channel = make_channel_mock()
    bot_svc = make_bot_service_mock(channel)

    with patch.object(watcher, "DONNA_TWITCH_PROACTIVE_ENABLED", False):
        await watcher.check_and_notify(
            twitch_bot_service=bot_svc,
            gemini_client=None,
            broadcaster_login="your-twitch-channel",
            client_id="test-client-id",
            access_token="test-token",
        )
        assert channel.send.call_count == 0


@pytest.mark.asyncio
async def test_no_channel_no_crash():
    """Kein Crash wenn get_channel() None zurückgibt."""
    import app.jobs.stream_live_watcher as watcher
    watcher.reset_state_for_testing()

    bot_svc = make_bot_service_mock(channel=None)  # Kein Channel
    stream_data = make_stream_data("Hearthstone")

    with (
        patch.object(watcher, "DONNA_TWITCH_PROACTIVE_ENABLED", True),
        patch(
            "app.jobs.stream_live_watcher._fetch_live_status",
            AsyncMock(side_effect=[None, stream_data]),  # offline→live
        ),
    ):
        # Erster Lauf: State-Init
        await watcher.check_and_notify(
            twitch_bot_service=bot_svc,
            gemini_client=None,
            broadcaster_login="your-twitch-channel",
            client_id="test-client-id",
            access_token="test-token",
        )
        # Zweiter Lauf: Wechsel erkannt, aber kein Channel → kein Crash
        await watcher.check_and_notify(
            twitch_bot_service=bot_svc,
            gemini_client=None,
            broadcaster_login="your-twitch-channel",
            client_id="test-client-id",
            access_token="test-token",
        )
        # Kein Exception → Test bestanden
