"""Unit tests für ClaudeClient — komplett gemockt, KEIN echter API-Call."""
from __future__ import annotations

from types import SimpleNamespace
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from app.services.claude_client import ClaudeClient, ClaudeUnavailable


def _usage(i: int = 11, o: int = 7) -> SimpleNamespace:
    return SimpleNamespace(input_tokens=i, output_tokens=o)


def _message(text: str = "Hallo Mike", usage: SimpleNamespace | None = None) -> SimpleNamespace:
    return SimpleNamespace(
        content=[
            SimpleNamespace(type="thinking", text="ignoriert"),
            SimpleNamespace(type="text", text=text),
        ],
        usage=usage or _usage(),
    )


class _FakeStream:
    """Async-Context-Manager, der sich wie `client.messages.stream(...)` verhält."""

    def __init__(self, chunks: list[str], final, fail_after: Exception | None = None) -> None:
        self._chunks = chunks
        self._final = final
        self._fail_after = fail_after

    async def __aenter__(self):
        return self

    async def __aexit__(self, *exc) -> bool:
        return False

    @property
    def text_stream(self):
        async def _gen():
            for c in self._chunks:
                yield c
            if self._fail_after is not None:
                raise self._fail_after

        return _gen()

    async def get_final_message(self):
        return self._final


def _client_with_mock(**kwargs) -> tuple[ClaudeClient, MagicMock]:
    """ClaudeClient mit ersetztem AsyncAnthropic. Gibt (client, sdk_mock) zurück."""
    sdk = MagicMock()
    sdk.messages.create = AsyncMock()
    sdk.messages.stream = MagicMock()
    with patch("app.services.claude_client.AsyncAnthropic", return_value=sdk) as ctor:
        client = ClaudeClient(api_key=kwargs.pop("api_key", "sk-test"), **kwargs)
        client._ensure_client()  # Konstruktor-Aufruf innerhalb des Patches auslösen
        client._ctor = ctor  # type: ignore[attr-defined]
    return client, sdk


# ---- ready() -----------------------------------------------------------------

def test_ready_true_with_key():
    assert ClaudeClient(api_key="sk-test").ready() is True


@pytest.mark.parametrize("key", [None, "", "   "])
def test_ready_false_without_key(key):
    assert ClaudeClient(api_key=key).ready() is False


async def test_generate_without_key_raises_unavailable():
    client = ClaudeClient(api_key="")
    with pytest.raises(ClaudeUnavailable):
        await client.generate(system="s", prompt="p")


# ---- generate() --------------------------------------------------------------

async def test_generate_returns_text_and_usage():
    client, sdk = _client_with_mock()
    sdk.messages.create.return_value = _message("Hallo Mike", _usage(11, 7))

    text, usage = await client.generate(system="Du bist Donna", prompt="Hi")

    assert text == "Hallo Mike"
    assert usage == {"input_tokens": 11, "output_tokens": 7}
    assert client.last_usage == usage


async def test_generate_passes_system_top_level_and_maps_history():
    client, sdk = _client_with_mock(model="claude-haiku-5-5", max_tokens=512)
    sdk.messages.create.return_value = _message()

    history = [
        {"role": "assistant", "content": "führende Assistant-Nachricht (wird verworfen)"},
        {"role": "user", "content": "Frage 1"},
        {"role": "assistant", "content": "Antwort 1"},
        {"role": "system", "content": "darf nicht als message landen"},
        {"role": "user", "content": ""},
    ]
    await client.generate(system="SYS", prompt="Frage 2", history=history)

    kwargs = sdk.messages.create.call_args.kwargs
    assert kwargs["system"] == "SYS"
    assert kwargs["model"] == "claude-haiku-5-5"
    assert kwargs["max_tokens"] == 512
    assert kwargs["messages"] == [
        {"role": "user", "content": "Frage 1"},
        {"role": "assistant", "content": "Antwort 1"},
        {"role": "user", "content": "Frage 2"},
    ]
    assert all(m["role"] != "system" for m in kwargs["messages"])


async def test_generate_model_and_max_tokens_override():
    client, sdk = _client_with_mock()
    sdk.messages.create.return_value = _message()

    await client.generate(system="", prompt="p", model="claude-sonnet-5-5", max_tokens=4096)

    kwargs = sdk.messages.create.call_args.kwargs
    assert kwargs["model"] == "claude-sonnet-5-5"
    assert kwargs["max_tokens"] == 4096
    assert "system" not in kwargs  # leerer System-Prompt wird nicht gesendet


async def test_generate_sdk_error_raises_claude_unavailable():
    client, sdk = _client_with_mock()
    sdk.messages.create.side_effect = RuntimeError("boom")

    with pytest.raises(ClaudeUnavailable):
        await client.generate(system="s", prompt="p")


async def test_generate_timeout_raises_claude_unavailable():
    client, sdk = _client_with_mock()
    sdk.messages.create.side_effect = TimeoutError("timeout")

    with pytest.raises(ClaudeUnavailable):
        await client.generate(system="s", prompt="p")


# ---- stream() ----------------------------------------------------------------

async def test_stream_yields_chunks_and_sets_last_usage():
    client, sdk = _client_with_mock()
    final = SimpleNamespace(usage=_usage(20, 5))
    sdk.messages.stream.return_value = _FakeStream(["Hal", "lo ", "", "Mike"], final)

    out_usage: dict[str, int] = {}
    chunks = [
        c
        async for c in client.stream(
            system="SYS", prompt="Hi", history=[{"role": "user", "content": "x"}],
            usage_out=out_usage,
        )
    ]

    assert chunks == ["Hal", "lo ", "Mike"]  # leere Chunks werden gefiltert
    assert client.last_usage == {"input_tokens": 20, "output_tokens": 5}
    assert out_usage == {"input_tokens": 20, "output_tokens": 5}
    kwargs = sdk.messages.stream.call_args.kwargs
    assert kwargs["system"] == "SYS"
    assert kwargs["messages"][-1] == {"role": "user", "content": "Hi"}


async def test_stream_sdk_error_raises_claude_unavailable():
    client, sdk = _client_with_mock()
    sdk.messages.stream.side_effect = RuntimeError("connection refused")

    with pytest.raises(ClaudeUnavailable):
        async for _ in client.stream(system="s", prompt="p"):
            pass


async def test_stream_error_mid_stream_raises_after_partial_chunks():
    client, sdk = _client_with_mock()
    sdk.messages.stream.return_value = _FakeStream(
        ["Teil"], SimpleNamespace(usage=_usage()), fail_after=RuntimeError("abgebrochen")
    )

    got: list[str] = []
    with pytest.raises(ClaudeUnavailable):
        async for c in client.stream(system="s", prompt="p"):
            got.append(c)

    assert got == ["Teil"]


async def test_stream_without_key_raises_unavailable():
    client = ClaudeClient(api_key=None)
    with pytest.raises(ClaudeUnavailable):
        async for _ in client.stream(system="s", prompt="p"):
            pass


# ---- SDK-Konstruktion --------------------------------------------------------

def test_sdk_client_built_with_key_and_timeout():
    client, _ = _client_with_mock(api_key="sk-abc", timeout_s=42)
    kwargs = client._ctor.call_args.kwargs  # type: ignore[attr-defined]
    assert kwargs["api_key"] == "sk-abc"
    assert kwargs["timeout"] == 42.0


# ---- Thinking/Effort + stop_reason (R4-Fixes) --------------------------------

async def test_generate_sets_low_effort_output_config():
    client, sdk = _client_with_mock()
    sdk.messages.create.return_value = _message()
    await client.generate(system="s", prompt="p")
    kwargs = sdk.messages.create.call_args.kwargs
    assert kwargs["output_config"] == {"effort": "low"}


async def test_generate_refusal_raises_unavailable():
    client, sdk = _client_with_mock()
    sdk.messages.create.return_value = SimpleNamespace(
        content=[SimpleNamespace(type="text", text="egal")],
        usage=_usage(), stop_reason="refusal",
        stop_details=SimpleNamespace(category="cyber"),
    )
    with pytest.raises(ClaudeUnavailable):
        await client.generate(system="s", prompt="p")


async def test_generate_empty_text_raises_unavailable():
    # Thinking hat das gesamte Budget verbraucht → kein Text-Block
    client, sdk = _client_with_mock()
    sdk.messages.create.return_value = SimpleNamespace(
        content=[SimpleNamespace(type="thinking", text="nur thinking")],
        usage=_usage(), stop_reason="max_tokens",
    )
    with pytest.raises(ClaudeUnavailable):
        await client.generate(system="s", prompt="p")


async def test_stream_refusal_raises_after_no_chunks():
    client, sdk = _client_with_mock()
    final = SimpleNamespace(usage=_usage(), stop_reason="refusal",
                            stop_details=SimpleNamespace(category="bio"))
    sdk.messages.stream.return_value = _FakeStream([], final)
    with pytest.raises(ClaudeUnavailable):
        async for _ in client.stream(system="s", prompt="p"):
            pass


# ---- Web-Suche (tools / Quellen / pause_turn) --------------------------------

from app.services.claude_client import build_web_search_tool  # noqa: E402


def _cited_final(stop: str = "end_turn", searches: int = 1) -> SimpleNamespace:
    """Final-Message mit Suche: server_tool_use, Ergebnisse (Liste), zitierter Text."""
    return SimpleNamespace(
        stop_reason=stop,
        usage=SimpleNamespace(
            input_tokens=100, output_tokens=40,
            server_tool_use=SimpleNamespace(web_search_requests=searches),
        ),
        content=[
            SimpleNamespace(type="server_tool_use", name="web_search", input={"query": "q"}),
            SimpleNamespace(
                type="web_search_tool_result",
                content=[
                    SimpleNamespace(type="web_search_result", url="https://a.example/1", title="A"),
                    SimpleNamespace(type="web_search_result", url="https://b.example/2", title="B"),
                ],
            ),
            SimpleNamespace(
                type="text", text="Antwort",
                citations=[SimpleNamespace(
                    type="web_search_result_location", url="https://b.example/2",
                    title="B Titel", cited_text="...")],
            ),
        ],
    )


def test_build_web_search_tool_basic_direct():
    tool = build_web_search_tool(2)
    assert tool == {
        "type": "web_search_20250305", "name": "web_search",
        "max_uses": 2, "allowed_callers": ["direct"],
    }


async def test_stream_passes_tools_through():
    client, sdk = _client_with_mock()
    sdk.messages.stream.return_value = _FakeStream(["x"], SimpleNamespace(usage=_usage()))
    tools = [build_web_search_tool(2)]

    _ = [c async for c in client.stream(system="s", prompt="p", tools=tools)]

    assert sdk.messages.stream.call_args.kwargs["tools"] == tools


async def test_stream_without_tools_sends_no_tools_key():
    client, sdk = _client_with_mock()
    sdk.messages.stream.return_value = _FakeStream(["x"], SimpleNamespace(usage=_usage()))

    _ = [c async for c in client.stream(system="s", prompt="p")]

    assert "tools" not in sdk.messages.stream.call_args.kwargs


async def test_generate_passes_tools_through():
    client, sdk = _client_with_mock()
    sdk.messages.create.return_value = _message()
    tools = [build_web_search_tool(1)]

    await client.generate(system="s", prompt="p", tools=tools)

    assert sdk.messages.create.call_args.kwargs["tools"] == tools


async def test_stream_collects_sources_and_search_count():
    client, sdk = _client_with_mock()
    sdk.messages.stream.return_value = _FakeStream(["Antwort"], _cited_final())

    usage: dict[str, int] = {}
    sources: list[dict[str, str]] = []
    _ = [c async for c in client.stream(
        system="s", prompt="p", tools=[build_web_search_tool()],
        usage_out=usage, sources_out=sources)]

    # zitierte Quelle hat Vorrang vor der reinen Ergebnisliste
    assert sources == [{"title": "B Titel", "url": "https://b.example/2"}]
    assert usage == {"input_tokens": 100, "output_tokens": 40, "web_search_requests": 1}


async def test_stream_sources_fall_back_to_search_results_when_nothing_cited():
    client, sdk = _client_with_mock()
    final = _cited_final()
    final.content[-1].citations = []
    sdk.messages.stream.return_value = _FakeStream(["Antwort"], final)

    sources: list[dict[str, str]] = []
    _ = [c async for c in client.stream(system="s", prompt="p", sources_out=sources)]

    assert [s["url"] for s in sources] == ["https://a.example/1", "https://b.example/2"]


async def test_stream_search_error_result_is_not_a_source():
    client, sdk = _client_with_mock()
    final = SimpleNamespace(
        stop_reason="end_turn", usage=_usage(),
        content=[SimpleNamespace(
            type="web_search_tool_result",
            content=SimpleNamespace(type="web_search_tool_result_error", error_code="max_uses_exceeded"))],
    )
    sdk.messages.stream.return_value = _FakeStream(["x"], final)

    sources: list[dict[str, str]] = []
    _ = [c async for c in client.stream(system="s", prompt="p", sources_out=sources)]

    assert sources == []


async def test_stream_pause_turn_is_continued_with_assistant_turn():
    client, sdk = _client_with_mock()
    paused = _cited_final(stop="pause_turn", searches=1)
    done = SimpleNamespace(stop_reason="end_turn", usage=_usage(10, 5), content=[])
    sdk.messages.stream.side_effect = [
        _FakeStream(["Teil1 "], paused), _FakeStream(["Teil2"], done),
    ]

    usage: dict[str, int] = {}
    chunks = [c async for c in client.stream(
        system="s", prompt="Frage", tools=[build_web_search_tool()], usage_out=usage)]

    assert chunks == ["Teil1 ", "Teil2"]
    assert sdk.messages.stream.call_count == 2
    second = sdk.messages.stream.call_args_list[1].kwargs["messages"]
    # Turn unveraendert zurueck, KEINE zusaetzliche "Weiter"-User-Message
    assert second[-2] == {"role": "user", "content": "Frage"}
    assert second[-1] == {"role": "assistant", "content": paused.content}
    # Usage ueber beide Teil-Turns summiert
    assert usage == {"input_tokens": 110, "output_tokens": 45, "web_search_requests": 1}


async def test_stream_pause_turn_continuations_are_capped():
    client, sdk = _client_with_mock()
    sdk.messages.stream.side_effect = lambda **kw: _FakeStream(
        ["x"], _cited_final(stop="pause_turn", searches=0))

    chunks = [c async for c in client.stream(
        system="s", prompt="p", tools=[build_web_search_tool()])]

    assert len(chunks) == 4  # 1 Start + 3 Fortsetzungen, dann Abbruch
    assert sdk.messages.stream.call_count == 4


async def test_generate_pause_turn_continues_and_joins_text():
    client, sdk = _client_with_mock()
    first = SimpleNamespace(
        content=[SimpleNamespace(type="text", text="A ")], usage=_usage(5, 2), stop_reason="pause_turn")
    second = SimpleNamespace(
        content=[SimpleNamespace(type="text", text="B")], usage=_usage(6, 3), stop_reason="end_turn")
    sdk.messages.create.side_effect = [first, second]

    text, usage = await client.generate(system="s", prompt="p", tools=[build_web_search_tool()])

    assert text == "A B"
    assert usage == {"input_tokens": 11, "output_tokens": 5}
    assert sdk.messages.create.call_count == 2


# ---- Web-Suche deaktiviert -> Retry ohne Tools ------------------------------------

class _FakeBadRequest(Exception):
    status_code = 400


_WS_DISABLED = _FakeBadRequest(
    "Error code: 400 - {'type': 'error', 'error': {'type': 'invalid_request_error', "
    "'message': 'Web search is not enabled for this organization'}}")


async def test_stream_retries_once_without_tools_when_web_search_disabled():
    client, sdk = _client_with_mock()
    sdk.messages.stream.side_effect = [
        _FakeStream([], None, fail_after=_WS_DISABLED),
        _FakeStream(["Antwort"], SimpleNamespace(usage=_usage(), stop_reason="end_turn", content=[])),
    ]

    chunks = [c async for c in client.stream(
        system="s", prompt="p", tools=[build_web_search_tool()])]

    assert chunks == ["Antwort"]
    assert sdk.messages.stream.call_count == 2
    assert "tools" in sdk.messages.stream.call_args_list[0].kwargs
    assert "tools" not in sdk.messages.stream.call_args_list[1].kwargs


async def test_stream_no_retry_after_chunk_was_yielded():
    client, sdk = _client_with_mock()
    sdk.messages.stream.side_effect = [_FakeStream(["Teil"], None, fail_after=_WS_DISABLED)]

    got: list[str] = []
    with pytest.raises(ClaudeUnavailable):
        async for c in client.stream(system="s", prompt="p", tools=[build_web_search_tool()]):
            got.append(c)

    assert got == ["Teil"]
    assert sdk.messages.stream.call_count == 1


async def test_stream_no_retry_for_other_errors_or_without_tools():
    client, sdk = _client_with_mock()
    sdk.messages.stream.side_effect = [_FakeStream([], None, fail_after=RuntimeError("boom"))]
    with pytest.raises(ClaudeUnavailable):
        _ = [c async for c in client.stream(
            system="s", prompt="p", tools=[build_web_search_tool()])]
    assert sdk.messages.stream.call_count == 1

    client2, sdk2 = _client_with_mock()
    sdk2.messages.stream.side_effect = [_FakeStream([], None, fail_after=_WS_DISABLED)]
    with pytest.raises(ClaudeUnavailable):
        _ = [c async for c in client2.stream(system="s", prompt="p")]
    assert sdk2.messages.stream.call_count == 1


async def test_stream_retry_happens_only_once():
    client, sdk = _client_with_mock()
    sdk.messages.stream.side_effect = [
        _FakeStream([], None, fail_after=_WS_DISABLED),
        _FakeStream([], None, fail_after=_WS_DISABLED),
    ]
    with pytest.raises(ClaudeUnavailable):
        _ = [c async for c in client.stream(
            system="s", prompt="p", tools=[build_web_search_tool()])]
    assert sdk.messages.stream.call_count == 2


async def test_generate_retries_once_without_tools_when_web_search_disabled():
    client, sdk = _client_with_mock()
    sdk.messages.create.side_effect = [_WS_DISABLED, _message("Ohne Suche")]

    text, _usage_ = await client.generate(system="s", prompt="p", tools=[build_web_search_tool()])

    assert text == "Ohne Suche"
    assert sdk.messages.create.call_count == 2
    assert "tools" in sdk.messages.create.call_args_list[0].kwargs
    assert "tools" not in sdk.messages.create.call_args_list[1].kwargs
