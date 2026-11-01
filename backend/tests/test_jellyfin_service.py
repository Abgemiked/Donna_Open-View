"""Unit-Tests für JellyfinService (A2.1) — komplett gemockt, KEIN echter Jellyfin-Call.

Fokus (Stufe-3-Härtung):
  * Auth-Header MUSS exakt `Authorization: MediaBrowser Token="<key>"` sein.
  * X-Emby-Token-Header wird NICHT gesendet; ?api_key= taucht NICHT in der URL auf
    (beide geben laut Jellyfin 401 → dürfen nie verwendet werden).
  * Token landet NIE im Log (caplog), auch nicht im Fehlerpfad.
  * fail-safe: Timeout / HTTP 500 / Netzwerkfehler → [] bzw. None, nie eine Exception
    aus dem Request-Pfad (darf den Chat-Request nicht crashen).
  * list_users() + played_items() parsen die Jellyfin-Antwort korrekt.
  * Ohne Token/URL ist der Service deaktiviert (ready()==False) und macht keinen Call.
"""
from __future__ import annotations

import asyncio
import logging
from unittest.mock import patch

import httpx
import pytest

from app.services.jellyfin_service import JellyfinService

# Echte Klasse VOR dem Patchen binden — sonst ruft die Factory die gepatchte
# httpx.AsyncClient rekursiv auf (RecursionError).
_RealAsyncClient = httpx.AsyncClient

_TOKEN = "super-secret-jellyfin-key-4242"
_URL = "http://jellyfin.test:8096"


# ---------------------------------------------------------------------------
# httpx.MockTransport-Harness: fängt den ECHTEN httpx.Request ab (reale Header/URL).
# ---------------------------------------------------------------------------

class _Harness:
    def __init__(self, handler):
        self.requests: list[httpx.Request] = []
        self._handler = handler

    def _wrapped(self, request: httpx.Request) -> httpx.Response:
        self.requests.append(request)
        return self._handler(request)

    def transport(self) -> httpx.MockTransport:
        return httpx.MockTransport(self._wrapped)


def _patch_client(harness: _Harness):
    """Patcht httpx.AsyncClient so, dass JEDER Aufruf einen frischen Client mit
    der MockTransport des Harness liefert (der timeout=-kwarg wird ignoriert)."""
    transport = harness.transport()

    def _factory(*args, **kwargs):
        return _RealAsyncClient(transport=transport)

    return patch("httpx.AsyncClient", side_effect=_factory)


def _service(token: str = _TOKEN, url: str = _URL, **kw) -> JellyfinService:
    return JellyfinService(url=url, token=token, **kw)


# ---------------------------------------------------------------------------
# Auth-Header-Exaktheit
# ---------------------------------------------------------------------------

def test_auth_header_is_exactly_mediabrowser_token():
    harness = _Harness(lambda req: httpx.Response(200, json=[{"Name": "Mike", "Id": "u1"}]))
    svc = _service()
    with _patch_client(harness):
        asyncio.run(svc.list_users())

    assert len(harness.requests) == 1
    req = harness.requests[0]
    assert req.headers.get("Authorization") == f'MediaBrowser Token="{_TOKEN}"'


def test_no_x_emby_token_header():
    harness = _Harness(lambda req: httpx.Response(200, json=[]))
    svc = _service()
    with _patch_client(harness):
        asyncio.run(svc.list_users())

    req = harness.requests[0]
    # X-Emby-Token gibt 401 → darf NIE gesendet werden
    assert "X-Emby-Token" not in req.headers
    assert "x-emby-token" not in {k.lower() for k in req.headers.keys()}


def test_no_api_key_query_param():
    harness = _Harness(lambda req: httpx.Response(200, json=[]))
    svc = _service()
    with _patch_client(harness):
        asyncio.run(svc.list_users())

    req = harness.requests[0]
    # ?api_key= gibt 401 → darf NIE in der URL stehen
    assert "api_key" not in req.url.query.decode().lower()
    assert "api_key" not in str(req.url).lower()
    assert _TOKEN not in str(req.url)  # Token NIE im Klartext in der URL


# ---------------------------------------------------------------------------
# Token-Leak-Schutz im Log  (R5-Fix)
#
# WICHTIG: app/core/logger.py konfiguriert structlog mit PrintLoggerFactory →
# die Logs gehen auf STDOUT, nicht durch stdlib-`logging`. `caplog` allein sieht
# sie daher NIE (Scheinsicherheit). Wir schneiden darum BEIDE Kanäle mit:
#   * capsys        → stdout (fängt den strukturierten structlog-Output)
#   * caplog        → stdlib-logging (fängt einen etwaigen stdlib-Log-Leak)
#   * capture_logs  → structlog-Event-Ebene (deterministisch, cache-unabhängig)
# So wird der Test ROT bei JEDEM der beiden Leak-Mechanismen (stdlib ODER structlog).
# ---------------------------------------------------------------------------

from structlog.testing import capture_logs  # noqa: E402


def _assert_token_absent(capsys, caplog, captured_structlog):
    out = capsys.readouterr().out
    assert _TOKEN not in out, "Token im STDOUT (structlog-Leak!)"
    assert _TOKEN not in caplog.text, "Token im stdlib-Log (caplog-Leak!)"
    joined = "".join(str(e) for e in captured_structlog)
    assert _TOKEN not in joined, "Token im structlog-Event (capture_logs-Leak!)"


def test_token_never_logged_on_success(capsys, caplog):
    harness = _Harness(lambda req: httpx.Response(200, json=[{"Name": "Mike", "Id": "u1"}]))
    svc = _service()
    with caplog.at_level(logging.DEBUG):
        with capture_logs() as caps:
            with _patch_client(harness):
                asyncio.run(svc.list_users())
    _assert_token_absent(capsys, caplog, caps)


def test_token_never_logged_on_error(capsys, caplog):
    harness = _Harness(lambda req: httpx.Response(500, text="boom"))
    svc = _service()
    with caplog.at_level(logging.DEBUG):
        with capture_logs() as caps:
            with _patch_client(harness):
                out = asyncio.run(svc.list_users())
    assert out == []
    _assert_token_absent(capsys, caplog, caps)


# ---------------------------------------------------------------------------
# fail-safe: Fehler dürfen den Request-Pfad nicht crashen
# ---------------------------------------------------------------------------

def test_http_500_returns_empty():
    harness = _Harness(lambda req: httpx.Response(500, text="err"))
    svc = _service()
    with _patch_client(harness):
        assert asyncio.run(svc.list_users()) == []
        assert asyncio.run(svc.played_items("u1")) == []


def test_timeout_returns_empty():
    def _boom(req):
        raise httpx.TimeoutException("timed out", request=req)

    harness = _Harness(_boom)
    svc = _service()
    with _patch_client(harness):
        assert asyncio.run(svc.list_users()) == []
        assert asyncio.run(svc.played_items("u1")) == []


def test_network_error_returns_empty():
    def _boom(req):
        raise httpx.ConnectError("refused", request=req)

    harness = _Harness(_boom)
    svc = _service()
    with _patch_client(harness):
        assert asyncio.run(svc.list_users()) == []


# ---------------------------------------------------------------------------
# Parsing
# ---------------------------------------------------------------------------

def test_list_users_parsing():
    users = [{"Name": "Mike", "Id": "u1"}, {"Name": "Ämi", "Id": "u2"}]
    harness = _Harness(lambda req: httpx.Response(200, json=users))
    svc = _service()
    with _patch_client(harness):
        out = asyncio.run(svc.list_users())
    assert out == users


def test_played_items_parsing_and_query():
    payload = {
        "Items": [
            {"Name": "Inception", "Type": "Movie", "Id": "m1"},
            {"Name": "The Wire", "Type": "Series", "Id": "s1"},
        ],
        "TotalRecordCount": 2,
    }
    harness = _Harness(lambda req: httpx.Response(200, json=payload))
    svc = _service()
    with _patch_client(harness):
        out = asyncio.run(svc.played_items("u1"))

    assert [i["Name"] for i in out] == ["Inception", "The Wire"]
    req = harness.requests[0]
    assert "/Users/u1/Items" in req.url.path
    q = req.url.query.decode()
    # gesehene Titel: Filter IsPlayed + Recursive + Film/Serie
    assert "Filters=IsPlayed" in q
    assert "Recursive=true" in q
    assert "IncludeItemTypes=Movie%2CSeries" in q or "IncludeItemTypes=Movie,Series" in q


# ---------------------------------------------------------------------------
# Deaktiviert ohne Credentials
# ---------------------------------------------------------------------------

def test_disabled_without_token():
    svc = JellyfinService(url=_URL, token="")
    assert svc.ready() is False
    harness = _Harness(lambda req: httpx.Response(200, json=[]))
    with _patch_client(harness):
        assert asyncio.run(svc.list_users()) == []
    assert harness.requests == []  # kein Call ohne Credentials


def test_disabled_without_url():
    svc = JellyfinService(url="", token=_TOKEN)
    assert svc.ready() is False


def test_explicit_enabled_false_overrides():
    svc = JellyfinService(url=_URL, token=_TOKEN, enabled=False)
    assert svc.ready() is False


# ---------------------------------------------------------------------------
# BLOCKER-2: fail-safe gegen 401 / Garbage-Body / falsch geformtes JSON / InvalidURL
# ---------------------------------------------------------------------------

def test_http_401_returns_empty():
    harness = _Harness(lambda req: httpx.Response(401, text="Unauthorized"))
    svc = _service()
    with _patch_client(harness):
        assert asyncio.run(svc.list_users()) == []
        assert asyncio.run(svc.played_items("u1")) == []


def test_garbage_html_body_with_200_returns_empty():
    # 200 OK, aber HTML/Text statt JSON (z.B. falscher Reverse-Proxy) → resp.json()
    # wirft JSONDecodeError (ValueError) → muss gefangen werden, nie crashen.
    harness = _Harness(lambda req: httpx.Response(
        200, text="<html><body>login</body></html>",
        headers={"content-type": "text/html"},
    ))
    svc = _service()
    with _patch_client(harness):
        assert asyncio.run(svc.list_users()) == []
        assert asyncio.run(svc.played_items("u1")) == []


def test_plaintext_body_with_200_returns_empty():
    harness = _Harness(lambda req: httpx.Response(200, text="not json at all"))
    svc = _service()
    with _patch_client(harness):
        assert asyncio.run(svc.list_users()) == []


def test_json_wrong_shape_list_users():
    # list_users erwartet eine Liste — dict/None → []
    for body in ({}, {"Items": "x"}, None):
        harness = _Harness(lambda req, b=body: httpx.Response(200, json=b))
        svc = _service()
        with _patch_client(harness):
            assert asyncio.run(svc.list_users()) == []


def test_json_wrong_shape_played_items():
    # played_items erwartet {"Items": [...]} — Liste/Items-Nicht-Liste/dict-los → []
    for body in ([1, 2], {"Items": "x"}, {}, {"Items": {"a": 1}}):
        harness = _Harness(lambda req, b=body: httpx.Response(200, json=b))
        svc = _service()
        with _patch_client(harness):
            assert asyncio.run(svc.played_items("u1")) == []


def test_invalid_url_returns_none_not_crash():
    # httpx.InvalidURL erbt NICHT von ValueError/HTTPError → muss explizit gefangen
    # sein, sonst crasht der Chat-Request. Garbage-URL provoziert InvalidURL.
    svc = JellyfinService(url="http://[", token=_TOKEN)

    def _raise_invalid(req):  # pragma: no cover - wird i.d.R. nicht erreicht
        raise httpx.InvalidURL("bad url")

    harness = _Harness(_raise_invalid)
    with _patch_client(harness):
        assert asyncio.run(svc.list_users()) == []


def test_timeout_default_is_bounded_budget():
    # BLOCKER-2: Default-Timeout wurde auf das LTM-Budget (4s) gesenkt, nicht 10s.
    svc = JellyfinService(url=_URL, token=_TOKEN)
    assert svc._timeout <= 4.0
