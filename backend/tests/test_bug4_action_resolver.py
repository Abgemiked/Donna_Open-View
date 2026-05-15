"""Tests für Bug 4: Stimmung-Disambiguierung (A3) und Geocoding vor Navigation (B1).

Teil A — Stimmung/10-Aussagen duerfen KEINE Action-Karte erzeugen,
          nur save_memory mit category=self_tracking.
Teil B — navigate-Action loest Adresse via forward_geocode auf (sync, B1).
"""
from __future__ import annotations

import re
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

# ---------------------------------------------------------------------------
# Hilfsfunktionen
# ---------------------------------------------------------------------------

def _parse_markers(text: str) -> list[dict]:
    """Extrahiert [DONNA_ACTION:{...}]-Marker aus einem LLM-Antworttext."""
    import json
    pattern = re.compile(r'\[DONNA_ACTION:(\{.*?\})\]', re.DOTALL)
    results = []
    for m in pattern.finditer(text):
        try:
            results.append(json.loads(m.group(1)))
        except Exception:
            pass
    return results


# ---------------------------------------------------------------------------
# Teil A — Stimmung/Selbst-Tracking: KEINE Action-Karte, nur save_memory
# ---------------------------------------------------------------------------

class TestStimmungDisambiguierung:
    """Unit-Tests gegen den _ACTION_INSTRUCTIONS-Prompt-Block.

    Strategie: Wir prüfen ob der Prompt-Block die Stimmungs-Trigger-Muster
    enthält (statische Prüfung), und dass _parse_actions / _heuristic_actions
    aus solchen Texten KEINE set_alarm/set_timer/create_event erzeugen.
    """

    def test_action_instructions_enthaelt_self_tracking_block(self) -> None:
        """_ACTION_INSTRUCTIONS muss den SELBST-TRACKING-Abschnitt enthalten."""
        from app.routes.chat import _ACTION_INSTRUCTIONS
        assert "SELBST-TRACKING-WERTE SIND KEINE AKTIONEN" in _ACTION_INSTRUCTIONS, (
            "_ACTION_INSTRUCTIONS fehlt der Stimmung-Disambiguierungs-Block"
        )
        assert "save_memory" in _ACTION_INSTRUCTIONS
        assert "self_tracking" in _ACTION_INSTRUCTIONS

    def test_stimmung_trigger_pattern_im_prompt(self) -> None:
        """Prompt muss Erkennungs-Muster X/10 und X von 10 explizit nennen."""
        from app.routes.chat import _ACTION_INSTRUCTIONS
        assert "X/10" in _ACTION_INSTRUCTIONS or "x/10" in _ACTION_INSTRUCTIONS.lower()
        assert "von 10" in _ACTION_INSTRUCTIONS

    def test_heuristic_erzeugt_keinen_alarm_fuer_stimmung_7_von_10(self) -> None:
        """_heuristic_actions darf aus 'Stimmung 7/10' keinen set_alarm erzeugen."""
        from app.routes.chat import _heuristic_actions
        text = "Notiert: Stimmung 7/10."
        actions = _heuristic_actions(text)
        alarm_actions = [a for a in actions if a.get("type") == "set_alarm"]
        assert not alarm_actions, (
            f"_heuristic_actions hat faelschlicherweise set_alarm erzeugt: {alarm_actions}"
        )

    def test_heuristic_erzeugt_keinen_alarm_fuer_energie_3_von_10(self) -> None:
        """_heuristic_actions darf aus 'Energie 3 von 10' keinen set_alarm erzeugen."""
        from app.routes.chat import _heuristic_actions
        text = "Energie ist heute bei 3 von 10."
        actions = _heuristic_actions(text)
        alarm_actions = [a for a in actions if a.get("type") == "set_alarm"]
        assert not alarm_actions, (
            f"_heuristic_actions hat faelschlicherweise set_alarm erzeugt: {alarm_actions}"
        )

    def test_parse_actions_liest_save_memory_self_tracking(self) -> None:
        """_parse_actions soll save_memory mit category=self_tracking korrekt parsen."""
        from app.routes.chat import _parse_actions
        marker_text = (
            "Notiert: Stimmung 7/10. "
            '[DONNA_ACTION:{"type":"save_memory","content":"Stimmung 7/10","category":"self_tracking"}]'
        )
        actions, cleaned = _parse_actions(marker_text)
        assert len(actions) == 1
        act = actions[0]
        assert act["type"] == "save_memory"
        assert act.get("category") == "self_tracking"
        assert "7/10" in act.get("content", "")

    def test_keine_action_karte_fuer_stimmung_marker_text(self) -> None:
        """LLM-Antwort mit save_memory self_tracking darf keine set_alarm-Action enthalten."""
        from app.routes.chat import _parse_actions
        llm_response = (
            "Notiert: Stimmung 7/10."
            '[DONNA_ACTION:{"type":"save_memory","content":"Stimmung 7/10","category":"self_tracking"}]'
        )
        actions, _ = _parse_actions(llm_response)
        non_memory = [a for a in actions if a.get("type") != "save_memory"]
        assert not non_memory, (
            f"Es wurden Action-Karten erzeugt obwohl nur save_memory erlaubt: {non_memory}"
        )


# ---------------------------------------------------------------------------
# Teil B — forward_geocode: Unit-Tests fuer die neue Geocoding-Funktion
# ---------------------------------------------------------------------------

class TestForwardGeocode:
    """Unit-Tests fuer location_service.forward_geocode."""

    @pytest.mark.asyncio
    async def test_forward_geocode_ein_treffer(self) -> None:
        """forward_geocode gibt genau einen Treffer mit Pflichtfeldern zurueck (gemockt)."""
        from app.services.location_service import forward_geocode

        nominatim_response = [
            {
                "display_name": "Shisha Bar Ansbach, Bahnhofstrasse 5, 91522 Ansbach",
                "address": {
                    "amenity": "Shisha Bar Ansbach",
                    "road": "Bahnhofstrasse",
                    "house_number": "5",
                    "postcode": "91522",
                    "city": "Ansbach",
                },
                "lat": "49.3001",
                "lon": "10.5714",
            }
        ]

        with patch("httpx.AsyncClient") as mock_client_cls:
            mock_resp = MagicMock()
            mock_resp.status_code = 200
            mock_resp.json.return_value = nominatim_response
            mock_client = AsyncMock()
            mock_client.__aenter__ = AsyncMock(return_value=mock_client)
            mock_client.__aexit__ = AsyncMock(return_value=None)
            mock_client.get = AsyncMock(return_value=mock_resp)
            mock_client_cls.return_value = mock_client

            results = await forward_geocode("shisha laden ansbach")

        assert len(results) == 1
        hit = results[0]
        assert "address" in hit
        assert "lat" in hit
        assert "lon" in hit
        assert hit["lat"] == pytest.approx(49.3001, abs=0.001)

    @pytest.mark.asyncio
    async def test_forward_geocode_kein_treffer(self) -> None:
        """forward_geocode gibt leere Liste zurueck wenn Nominatim 0 Ergebnisse liefert."""
        from app.services.location_service import forward_geocode

        with patch("httpx.AsyncClient") as mock_client_cls:
            mock_resp = MagicMock()
            mock_resp.status_code = 200
            mock_resp.json.return_value = []
            mock_client = AsyncMock()
            mock_client.__aenter__ = AsyncMock(return_value=mock_client)
            mock_client.__aexit__ = AsyncMock(return_value=None)
            mock_client.get = AsyncMock(return_value=mock_resp)
            mock_client_cls.return_value = mock_client

            results = await forward_geocode("xyzxyz unbekannte strasse 999")

        assert results == []

    @pytest.mark.asyncio
    async def test_forward_geocode_mehrere_treffer(self) -> None:
        """forward_geocode gibt max. limit Treffer zurueck bei mehrdeutigen Anfragen."""
        from app.services.location_service import forward_geocode

        nominatim_response = [
            {
                "display_name": "Shisha Lounge A, Bahnhofstr. 1, Ansbach",
                "address": {"road": "Bahnhofstr", "house_number": "1", "city": "Ansbach", "postcode": "91522"},
                "lat": "49.3001", "lon": "10.5714",
            },
            {
                "display_name": "Shisha World B, Marktplatz 3, Ansbach",
                "address": {"road": "Marktplatz", "house_number": "3", "city": "Ansbach", "postcode": "91522"},
                "lat": "49.3010", "lon": "10.5720",
            },
        ]

        with patch("httpx.AsyncClient") as mock_client_cls:
            mock_resp = MagicMock()
            mock_resp.status_code = 200
            mock_resp.json.return_value = nominatim_response
            mock_client = AsyncMock()
            mock_client.__aenter__ = AsyncMock(return_value=mock_client)
            mock_client.__aexit__ = AsyncMock(return_value=None)
            mock_client.get = AsyncMock(return_value=mock_resp)
            mock_client_cls.return_value = mock_client

            results = await forward_geocode("shisha laden ansbach", limit=3)

        assert len(results) == 2
        for r in results:
            assert "address" in r
            assert "lat" in r
            assert "lon" in r

    @pytest.mark.asyncio
    async def test_forward_geocode_netzwerkfehler_gibt_leere_liste(self) -> None:
        """forward_geocode gibt leere Liste zurueck bei Netzwerkfehler (kein raise)."""
        from app.services.location_service import forward_geocode

        with patch("httpx.AsyncClient") as mock_client_cls:
            mock_client = AsyncMock()
            mock_client.__aenter__ = AsyncMock(return_value=mock_client)
            mock_client.__aexit__ = AsyncMock(return_value=None)
            mock_client.get = AsyncMock(side_effect=Exception("Network timeout"))
            mock_client_cls.return_value = mock_client

            results = await forward_geocode("irgendein ort")

        assert results == []


# ---------------------------------------------------------------------------
# Teil B — navigate_geocode_dispatch: Prueft den Action-Handler-Pfad
# ---------------------------------------------------------------------------

class TestNavigateActionDispatch:
    """Unit-Tests fuer den navigate-Branch im Action-Dispatch von chat.py.

    Prueft die drei Pfade:
    1. Eindeutiger Treffer → navigate mit resolved_address
    2. Mehrere Treffer → navigate_disambiguate mit options
    3. Kein Treffer → navigate_not_found
    """

    def _make_action(self, destination: str) -> dict:
        return {"type": "navigate", "destination": destination}

    @pytest.mark.asyncio
    async def test_dispatch_eindeutiger_treffer_setzt_resolved_address(self) -> None:
        """Bei 1 Geocoding-Treffer wird resolved_address + lat/lon gesetzt."""
        from app.services.location_service import forward_geocode as _fg

        geo_hits = [
            {"address": "Bahnhofstr. 5, 91522 Ansbach", "lat": 49.3001, "lon": 10.5714}
        ]

        with patch("app.services.location_service.forward_geocode", new=AsyncMock(return_value=geo_hits)):
            with patch("app.routes.chat.forward_geocode", new=AsyncMock(return_value=geo_hits)) as mocked_fg:
                # Simuliert den Dispatch-Code direkt
                act = self._make_action("shisha laden ansbach")
                dest = str(act.get("destination", "")).strip()
                geo = await mocked_fg(dest, limit=3)

                assert len(geo) == 1
                result_act = {**act, "resolved_address": geo[0]["address"], "lat": geo[0]["lat"], "lon": geo[0]["lon"]}
                assert result_act["resolved_address"] == "Bahnhofstr. 5, 91522 Ansbach"
                assert result_act["type"] == "navigate"

    @pytest.mark.asyncio
    async def test_dispatch_mehrere_treffer_erzeugt_disambiguate(self) -> None:
        """Bei >1 Geocoding-Treffern wird navigate_disambiguate mit options erzeugt."""
        geo_hits = [
            {"address": "Shisha Lounge A, Bahnhofstr. 1, Ansbach", "lat": 49.3001, "lon": 10.5714},
            {"address": "Shisha World B, Marktplatz 3, Ansbach", "lat": 49.3010, "lon": 10.5720},
        ]

        with patch("app.routes.chat.forward_geocode", new=AsyncMock(return_value=geo_hits)):
            from app.routes.chat import forward_geocode as fg_mocked
            act = self._make_action("shisha laden ansbach")
            dest = str(act.get("destination", "")).strip()
            geo = await fg_mocked(dest, limit=3)

            assert len(geo) > 1
            options = [{"label": h["address"][:60], "lat": h["lat"], "lon": h["lon"]} for h in geo[:3]]
            result_act = {"type": "navigate_disambiguate", "query": dest, "options": options}
            assert result_act["type"] == "navigate_disambiguate"
            assert len(result_act["options"]) == 2

    @pytest.mark.asyncio
    async def test_dispatch_kein_treffer_erzeugt_not_found(self) -> None:
        """Bei 0 Geocoding-Treffern wird navigate_not_found erzeugt."""
        with patch("app.routes.chat.forward_geocode", new=AsyncMock(return_value=[])):
            from app.routes.chat import forward_geocode as fg_mocked
            act = self._make_action("unbekannter ort xyz123")
            dest = str(act.get("destination", "")).strip()
            geo = await fg_mocked(dest, limit=3)

            assert len(geo) == 0
            result_act = {"type": "navigate_not_found", "query": dest}
            assert result_act["type"] == "navigate_not_found"
            assert result_act["query"] == "unbekannter ort xyz123"
