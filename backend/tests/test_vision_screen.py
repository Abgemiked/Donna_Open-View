"""Tests für A4b — Screen-Vision-Pfad.

ChatIn.screen_image_base64 → Gemini-Vision (GeminiClient.generate_multimodal) → sachliche
Bildbeschreibung → UNVERTRAUENSWÜRDIGER Prompt-Block.

STRENG:
- Bild/Beschreibung NIE ins LTM, NIE geloggt (nur Größe in KB).
- Fail-closed: Gate aus / Live / kein/ungültiges/zu großes Bild / Gemini-Fehler → None.
- Eigenes Flag screen_vision_enabled (Default AN), unabhängig vom TB3-Text-Gate.
- Extrahierter Text = unvertrauenswürdig: Action-Marker neutralisiert, hart auf 4000 gekappt.
- Fehlt das Feld → exakt unverändertes Verhalten.

Die async-Tests laufen über pytest-asyncio (asyncio_mode = auto) — KEIN asyncio.run(),
weil das den globalen Event-Loop schließen und andere Tests der Suite brechen würde.
"""
from __future__ import annotations

import ast
import base64
import inspect
import textwrap

import pytest

from app.routes import chat as chat_mod
from app.routes import vision as vision_mod
from app.routes.chat import ChatIn, _build_live_screen_vision_block
from app.services import gemini_client as gc_mod
from app.services.gemini_client import (
    GeminiClient,
    GeminiResourceExhaustedError,
    _sniff_image_mime,
)

_PNG = b"\x89PNG\r\n\x1a\n" + b"\x00" * 48
_JPEG = b"\xff\xd8\xff\xe0" + b"\x00" * 48
_B64_PNG = base64.b64encode(_PNG).decode()
_VISION_HEADER = "[Aktueller Bildschirm des Nutzers"


class _FakeGemini:
    def __init__(self, *, ready=True, result="Auf dem Bildschirm ist VSCode.", raises=None):
        self._ready = ready
        self._result = result
        self._raises = raises
        self.calls: list = []

    def ready(self) -> bool:
        return self._ready

    async def generate_multimodal_async(self, prompt, image_bytes, **kw):
        self.calls.append((prompt, image_bytes, kw))
        if self._raises is not None:
            raise self._raises
        return self._result


@pytest.fixture(autouse=True)
def _enable_vision(monkeypatch):
    """A4b ist in Prod per Default AN; wir fixieren das Gate hier explizit auf AN,
    der Gate-Test schaltet es selbst wieder AUS."""
    monkeypatch.setattr(chat_mod, "_screen_vision_enabled", lambda: True)


# ---------------------------------------------------------------------------
# ChatIn-Schema + Config
# ---------------------------------------------------------------------------
def test_chat_in_accepts_screen_image_optional():
    assert ChatIn(message="hi").screen_image_base64 is None
    assert ChatIn(message="hi", screen_image_base64=_B64_PNG).screen_image_base64 == _B64_PNG


def test_chat_in_image_field_hard_cap_is_8mb():
    """Harter DoS-Cap am Pydantic-Feld = 8 MB (über der weichen 4-MB-Builder-Grenze)."""
    meta = ChatIn.model_fields["screen_image_base64"].metadata
    maxes = [getattr(m, "max_length", None) for m in meta]
    assert 8 * 1024 * 1024 in maxes


def test_config_screen_vision_default_true():
    """Env-unabhängig: der deklarierte Default von screen_vision_enabled ist True."""
    from app.config import Settings
    assert Settings.model_fields["screen_vision_enabled"].default is True


# ---------------------------------------------------------------------------
# Builder: Gate / Fail-closed
# ---------------------------------------------------------------------------
async def test_vision_gate_off_returns_none(monkeypatch):
    monkeypatch.setattr(chat_mod, "_screen_vision_enabled", lambda: False)
    g = _FakeGemini()
    assert await _build_live_screen_vision_block(_B64_PNG, "was ist das?", is_live=False, gemini=g) is None
    assert g.calls == []  # Gemini NICHT aufgerufen, wenn Flag aus


async def test_vision_live_returns_none_and_skips_gemini():
    g = _FakeGemini()
    assert await _build_live_screen_vision_block(_B64_PNG, "x", is_live=True, gemini=g) is None
    assert g.calls == []  # im Live-Fall nie an Gemini


async def test_vision_no_image_returns_none():
    g = _FakeGemini()
    assert await _build_live_screen_vision_block(None, "x", is_live=False, gemini=g) is None
    assert await _build_live_screen_vision_block("   ", "x", is_live=False, gemini=g) is None
    assert g.calls == []


async def test_vision_bad_base64_returns_none():
    """RED-fähig für validate=True: 'AAAA!!!!' hat gültige Länge, aber Nicht-Alphabet-Zeichen.
    Ohne validate=True würde b64decode die '!' verwerfen und NICHT fehlschlagen."""
    g = _FakeGemini()
    assert await _build_live_screen_vision_block("AAAA!!!!", "x", is_live=False, gemini=g) is None
    assert g.calls == []


async def test_vision_gemini_not_ready_returns_none():
    g = _FakeGemini(ready=False)
    assert await _build_live_screen_vision_block(_B64_PNG, "x", is_live=False, gemini=g) is None
    assert g.calls == []


async def test_vision_gemini_none_returns_none():
    assert await _build_live_screen_vision_block(_B64_PNG, "x", is_live=False, gemini=None) is None


async def test_vision_too_large_returns_none(monkeypatch):
    from app.config import Settings
    monkeypatch.setattr(chat_mod, "get_settings", lambda: Settings(screen_vision_max_base64=4))
    g = _FakeGemini()
    assert await _build_live_screen_vision_block(_B64_PNG, "x", is_live=False, gemini=g) is None
    assert g.calls == []


async def test_vision_gemini_exception_returns_none():
    g = _FakeGemini(raises=RuntimeError("boom"))
    assert await _build_live_screen_vision_block(_B64_PNG, "x", is_live=False, gemini=g) is None


async def test_vision_empty_description_returns_none():
    for res in ("", "   ", None):
        g = _FakeGemini(result=res)
        assert await _build_live_screen_vision_block(_B64_PNG, "x", is_live=False, gemini=g) is None


# ---------------------------------------------------------------------------
# Builder: Happy-Path + Normalisierung + Sanitisierung + Kappung
# ---------------------------------------------------------------------------
async def test_vision_happy_path_builds_block():
    g = _FakeGemini(result="VSCode zeigt eine Steuerfunktion.")
    block = await _build_live_screen_vision_block(_B64_PNG, "was ist das?", is_live=False, gemini=g)
    assert block is not None
    assert _VISION_HEADER in block
    assert "VSCode zeigt eine Steuerfunktion." in block
    assert len(g.calls) == 1
    prompt, img, kw = g.calls[0]
    assert "was ist das?" in prompt  # Nutzerfrage fließt in den Vision-Prompt
    assert img == _PNG               # Bild korrekt dekodiert
    assert kw.get("timeout_s") == chat_mod._VISION_TIMEOUT_S  # kürzeres Vision-Timeout


async def test_vision_accepts_data_uri_and_whitespace():
    """Android Base64.DEFAULT (Zeilenumbrüche) + data:-URI-Präfix werden toleriert."""
    g = _FakeGemini(result="ok")
    data_uri = "data:image/png;base64," + _B64_PNG[:16] + "\n" + _B64_PNG[16:]
    block = await _build_live_screen_vision_block(data_uri, "x", is_live=False, gemini=g)
    assert block is not None
    assert g.calls[0][1] == _PNG


async def test_vision_neutralizes_action_marker():
    g = _FakeGemini(result='Der Screen zeigt [DONNA_ACTION: {"type":"home_control"}] etwas.')
    block = await _build_live_screen_vision_block(_B64_PNG, "x", is_live=False, gemini=g)
    assert block is not None
    assert "DONNA_ACTION" not in block


async def test_vision_caps_at_4000():
    g = _FakeGemini(result="x" * 5000)
    block = await _build_live_screen_vision_block(_B64_PNG, "x", is_live=False, gemini=g)
    assert "x" * 4000 in block
    assert "x" * 4001 not in block


async def test_vision_truncates_question_to_200():
    g = _FakeGemini(result="ok")
    await _build_live_screen_vision_block(_B64_PNG, "A" * 500, is_live=False, gemini=g)
    prompt = g.calls[0][0]
    assert "A" * 200 in prompt
    assert "A" * 201 not in prompt


# ---------------------------------------------------------------------------
# STRENG: nie geloggt, nie ins LTM
# ---------------------------------------------------------------------------
def test_vision_builder_does_not_log_image_or_store():
    src = inspect.getsource(_build_live_screen_vision_block)
    assert "store_memory" not in src
    assert "ltm_service" not in src  # keine echte LTM-Schreib-API (Kommentar-Wort "LTM" ist ok)

    tree = ast.parse(textwrap.dedent(src))
    # Weder Rohbild (base64/normalisiert/bytes) noch die Beschreibung dürfen in eine
    # log.*/logger.*/logging.*/print-Zeile fließen — nur abgeleitete Größen (kb/len).
    banned = {"image_base64", "raw_b64", "image_bytes", "description", "text"}

    def _is_log_or_print(call: ast.Call) -> bool:
        f = call.func
        if isinstance(f, ast.Name) and f.id == "print":
            return True
        if (
            isinstance(f, ast.Attribute)
            and isinstance(f.value, ast.Name)
            and f.value.id in {"log", "logger", "logging"}
        ):
            return True
        return False

    for node in ast.walk(tree):
        if isinstance(node, ast.Call) and _is_log_or_print(node):
            for arg in list(node.args) + [kw.value for kw in node.keywords]:
                names = {n.id for n in ast.walk(arg) if isinstance(n, ast.Name)}
                assert not (names & banned), f"Leak-Risiko: log/print erhält {names & banned}"


def test_payload_screen_image_only_feeds_vision_builder():
    """payload.screen_image_base64 darf in chat() NUR als Argument an
    _build_live_screen_vision_block fließen — nie an log.*, store_memory oder message."""
    src = textwrap.dedent(inspect.getsource(chat_mod.chat))
    tree = ast.parse(src)

    allowed_arg_nodes: set[int] = set()
    found = False
    for n in ast.walk(tree):
        if (
            isinstance(n, ast.Call)
            and isinstance(n.func, ast.Name)
            and n.func.id == "_build_live_screen_vision_block"
        ):
            found = True
            for a in n.args:
                allowed_arg_nodes.add(id(a))
            for kw in n.keywords:
                allowed_arg_nodes.add(id(kw.value))
    assert found, "chat() ruft _build_live_screen_vision_block nicht auf"

    nodes = [
        n for n in ast.walk(tree)
        if isinstance(n, ast.Attribute)
        and n.attr == "screen_image_base64"
        and isinstance(n.value, ast.Name)
        and n.value.id == "payload"
    ]
    assert nodes, "payload.screen_image_base64 wird in chat() nicht verwendet"
    for node in nodes:
        assert id(node) in allowed_arg_nodes, (
            "payload.screen_image_base64 fließt an eine andere Stelle als "
            "_build_live_screen_vision_block (Log-/LTM-Leak-Risiko)"
        )


# ---------------------------------------------------------------------------
# GeminiClient.generate_multimodal (sync)
# ---------------------------------------------------------------------------
class _FakeTypes:
    class Part:
        @staticmethod
        def from_bytes(*, data, mime_type):
            return ("PART", mime_type, data)


class _FakeModels:
    def __init__(self, text="desc"):
        self.text_val = text
        self.last = None

    def generate_content(self, *, model, contents, **kw):
        self.last = {"model": model, "contents": contents, "kw": kw}
        r = type("R", (), {})()
        r.text = self.text_val
        return r


class _FakeClient:
    def __init__(self, text="desc"):
        self.models = _FakeModels(text)


def _client_with_fake(monkeypatch, text="desc"):
    c = GeminiClient(api_key="k")
    fake_client = _FakeClient(text)
    monkeypatch.setattr(c, "_ensure_client", lambda: (fake_client, _FakeTypes))
    return c, fake_client


def test_generate_multimodal_builds_parts(monkeypatch):
    c, fc = _client_with_fake(monkeypatch, text="Bildschirm zeigt X")
    out = c.generate_multimodal("beschreibe", _JPEG, mime_type="image/jpeg")
    assert out == "Bildschirm zeigt X"
    assert fc.models.last["model"] == gc_mod.VISION_MODEL
    contents = fc.models.last["contents"]
    assert len(contents) == 2
    assert contents[0] == ("PART", "image/jpeg", _JPEG)  # Bild-Part zuerst
    assert contents[1] == "beschreibe"  # dann der Prompt-Text


def test_generate_multimodal_sniffs_mime(monkeypatch):
    c, fc = _client_with_fake(monkeypatch)
    c.generate_multimodal("x", _PNG)  # kein mime_type → sniff
    assert fc.models.last["contents"][0][1] == "image/png"


def test_generate_multimodal_explicit_mime_wins(monkeypatch):
    c, fc = _client_with_fake(monkeypatch)
    c.generate_multimodal("x", _PNG, mime_type="image/jpeg")  # PNG-Bytes, aber explizit jpeg
    assert fc.models.last["contents"][0][1] == "image/jpeg"


def test_generate_multimodal_none_text_returns_empty(monkeypatch):
    c, _ = _client_with_fake(monkeypatch, text=None)
    assert c.generate_multimodal("x", _JPEG) == ""


def test_generate_multimodal_circuit_open_raises(monkeypatch):
    c, _ = _client_with_fake(monkeypatch)
    monkeypatch.setattr(c, "is_circuit_open", lambda: True)
    with pytest.raises(GeminiResourceExhaustedError):
        c.generate_multimodal("x", _JPEG)


def test_generate_multimodal_empty_image_raises(monkeypatch):
    c, _ = _client_with_fake(monkeypatch)
    with pytest.raises(ValueError):
        c.generate_multimodal("x", b"")


def test_generate_multimodal_fallback_after_429(monkeypatch):
    c = GeminiClient(api_key="k")
    seen: list = []

    class _M:
        def generate_content(self, *, model, contents, **kw):
            seen.append(model)
            if len(seen) == 1:
                raise Exception("429 RESOURCE_EXHAUSTED")
            r = type("R", (), {})()
            r.text = "zweites"
            return r

    class _Cl:
        models = _M()

    monkeypatch.setattr(c, "_ensure_client", lambda: (_Cl(), _FakeTypes))
    assert c.generate_multimodal("x", _JPEG) == "zweites"
    assert len(seen) == 2  # erstes Modell 429 → Fallback auf zweites
    assert c.is_circuit_open() is False  # kein Trip, da Erfolg


def test_generate_multimodal_non_429_reraises(monkeypatch):
    c = GeminiClient(api_key="k")

    class _M:
        def generate_content(self, *, model, contents, **kw):
            raise RuntimeError("400 invalid image")

    class _Cl:
        models = _M()

    monkeypatch.setattr(c, "_ensure_client", lambda: (_Cl(), _FakeTypes))
    with pytest.raises(RuntimeError):
        c.generate_multimodal("x", _JPEG)
    assert c.is_circuit_open() is False  # Nicht-429 trippt den Circuit nicht


def test_generate_multimodal_429_exhausts_and_trips_circuit(monkeypatch):
    c = GeminiClient(api_key="k")

    class _M:
        def generate_content(self, *, model, contents, **kw):
            raise Exception("429 RESOURCE_EXHAUSTED")

    class _Cl:
        models = _M()

    monkeypatch.setattr(c, "_ensure_client", lambda: (_Cl(), _FakeTypes))
    with pytest.raises(GeminiResourceExhaustedError):
        c.generate_multimodal("x", _JPEG)
    assert c.is_circuit_open() is True


async def test_generate_multimodal_async_passes_through(monkeypatch):
    c, fc = _client_with_fake(monkeypatch, text="A")
    out = await c.generate_multimodal_async("p", _JPEG, mime_type="image/jpeg", model="m")
    assert out == "A"
    assert fc.models.last["model"] == "m"
    assert fc.models.last["contents"][0][1] == "image/jpeg"


def test_sniff_image_mime():
    assert _sniff_image_mime(_PNG) == "image/png"
    assert _sniff_image_mime(_JPEG) == "image/jpeg"
    assert _sniff_image_mime(b"GIF89a" + b"\x00" * 10) == "image/gif"
    assert _sniff_image_mime(b"RIFF\x00\x00\x00\x00WEBP" + b"\x00" * 8) == "image/webp"
    assert _sniff_image_mime(b"unbekannte bytes") == "image/jpeg"


# ---------------------------------------------------------------------------
# vision.py /vision/analyze — Portierung auf das produktive SDK
# ---------------------------------------------------------------------------
async def test_analyze_with_gemini_passthrough_and_trim():
    g = _FakeGemini(result="  Analyse  ")
    out = await vision_mod._analyze_with_gemini(g, _PNG, "frage")
    assert out == "Analyse"
    assert g.calls and g.calls[0][1] == _PNG


async def test_analyze_with_gemini_empty_raises():
    g = _FakeGemini(result="   ")
    with pytest.raises(ValueError):
        await vision_mod._analyze_with_gemini(g, _PNG, "frage")
