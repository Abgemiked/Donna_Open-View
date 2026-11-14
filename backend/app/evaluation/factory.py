"""Dedicated worker only: bind the real chat endpoint without production lifespan."""
from pathlib import Path
import re


def synthetic_persona(text):
    """Explicit test-profile delta: preserve behavior, replace personal facts.

    This is not an evaluation of the deployed user's fixed persona. The real
    prompt assembler remains in use with a documented synthetic persona.
    """
    text = re.sub(r"# KONTEXT-AWARENESS\n.*?(?=\n# |\Z)",
                  "# KONTEXT-AWARENESS\nDie Testperson Nova ist erfunden. Nur synthetische Kontextfakten verwenden.\n", text, flags=re.S)
    for original, replacement in (("example.com", "synthetic.invalid"), ("your-twitch-channel", "synthetic_user"),
                                  ("Mikes", "Novas"), ("Mike's", "Novas"), ("Mike", "Nova"),
                                  ("Ämi", "Testkontakt"), ("Aemi", "Testkontakt"), ("Amy", "Testkontakt")):
        text = text.replace(original, replacement)
    if "Operations Director" in text or "@abgemiked" in text:
        raise RuntimeError("non_synthetic_persona")
    return text


def synthetic_payload(value):
    if isinstance(value, str):
        return synthetic_persona(value)
    if isinstance(value, list):
        return [synthetic_payload(item) for item in value]
    if isinstance(value, dict):
        return {key: synthetic_payload(item) for key, item in value.items()}
    return value


async def create_eval_app(root, claude, seeds=(), lessons=(), model="claude-test", calendar_timeout=False):
    from app import config
    from app.config import Settings

    class LiteralSettings(Settings):
        @classmethod
        def settings_customise_sources(cls, settings_cls, init_settings, env_settings, dotenv_settings, file_secret_settings):
            return (init_settings,)

    settings = LiteralSettings(_env_file=None, app_env="evaluation", admin_token="synthetic-eval-only",
        anthropic_api_key="synthetic-worker-no-key", anthropic_model_fast=model, anthropic_model_deep=model,
        anthropic_max_tokens=768, anthropic_max_tokens_deep=768, scheduler_enabled=False,
        web_search_enabled=False, screen_context_enabled=False, screen_vision_enabled=False,
        ha_enabled=False, mem0_enabled=False)
    config.get_settings = lambda: settings
    from app.routes import chat
    from app.core.auth import require_admin
    from app.core import service_state
    from app.services.stm_service import STMService
    from app.services.smart_router import SmartRouter
    from app.evaluation.stores import SyntheticMemory, SyntheticVector, ToolStubs
    from fastapi import FastAPI, HTTPException

    chat.get_settings = lambda: settings
    for name in ("SYSTEM_PROMPT", "SYSTEM_PROMPT_WITH_SEARCH", "SYSTEM_PROMPT_GUEST"):
        setattr(chat, name, synthetic_persona(getattr(chat, name)))
    service_state.DONNA_TWITCH_ENABLED = False
    service_state.scheduler = None
    async def offline_status(*args, **kwargs): return {"error": "twitch_disabled"}
    async def offline_live(*args, **kwargs): return False
    chat._twitch_helix.get_stream_status = offline_status
    async def forbidden_twitch(*args, **kwargs): raise RuntimeError("twitch_disabled")
    chat._twitch_helix.set_stream_title = forbidden_twitch
    chat._twitch_helix.set_stream_game = forbidden_twitch
    chat._twitch_live_check.is_broadcaster_live = offline_live
    chat._schedule_service.get_schedule_for_prompt = lambda: None
    chat.contact_pref_service.contact_pref_block_for = lambda *a, **k: None
    chat.contact_pref_service.record_contact = lambda *a, **k: None
    chat._GRAPHITI_CHAT_HOOK_ENABLED = False

    root = Path(root).resolve()
    memory = SyntheticMemory(root / "ltm")
    for content in seeds: memory.store_memory("synthetic", content)
    memory.replace_revision(list(lessons))
    stm = STMService(db_path=str(root / "stm.sqlite"))
    await stm.init()
    tools = ToolStubs(calendar_timeout=calendar_timeout)
    app = FastAPI()
    app.state.settings = settings
    app.state.claude = claude
    app.state.gemini = None
    app.state.mistral = None
    app.state.local_llm = None
    app.state.vector = SyntheticVector(memory)
    app.state.smart_router = SmartRouter()
    app.state.stm = stm
    app.state.ltm = memory
    app.state.ha = tools
    app.state.calendar = tools
    app.state.eval_tools = tools
    app.state.eval_profile = {"version": 1, "persona": "synthetic-nova", "prompt_delta": "replace-fixed-personal-context-and-example-names"}
    app.include_router(chat.router)
    app.dependency_overrides[require_admin] = lambda: "synthetic"
    @app.middleware("http")
    async def endpoint_guard(request, call_next):
        if request.url.path != "/chat":
            from fastapi.responses import JSONResponse
            return JSONResponse({"error": "eval_endpoint_denied"}, status_code=403)
        return await call_next(request)
    return app
