"""Application configuration loaded from environment (.env)."""
from __future__ import annotations

from functools import lru_cache
from pathlib import Path
from typing import Optional

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """Runtime configuration for the Assistent backend.

    All values can be overridden via environment variables or .env file.
    """

    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        case_sensitive=False,
        extra="ignore",
    )

    # --- Core ---
    app_name: str = "assistent"
    app_env: str = Field(default="production")
    log_level: str = Field(default="INFO")

    # --- Secrets / API ---
    gemini_api_key: Optional[str] = Field(default=None)
    admin_token: Optional[str] = Field(default=None)

    # --- TOTP Pairing (DONNA-103) ---
    # Generate with: python3 -c "import pyotp; print(pyotp.random_base32())"
    donna_totp_secret: Optional[str] = Field(default=None)

    # --- Google Calendar OAuth2 (DONNA-107) ---
    # Credentials aus Google Cloud Console; Refresh-Token nach einmaliger Auth.
    # Token NIEMALS committen — ausschließlich in .env auf dem Server speichern.
    # Scope: calendar.readonly (DSGVO-Gutachten Auflage 2).
    google_client_id: Optional[str] = Field(default=None)
    google_client_secret: Optional[str] = Field(default=None)
    google_refresh_token: Optional[str] = Field(default=None)

    # --- Anthropic Claude (primäre Cloud-LLM-Stufe; Mistral = Notfall-Fallback) ---
    # ANTHROPIC_API_KEY NIEMALS committen — ausschließlich in .env auf dem Server.
    anthropic_api_key: str = Field(default="")
    anthropic_model_fast: str = Field(default="claude-haiku-5-5")
    anthropic_model_deep: str = Field(default="claude-sonnet-5-5")
    anthropic_timeout_s: int = Field(default=60)
    # Thinking ist bei 5.5-Modellen default an und zaehlt gegen max_tokens → großzügig
    anthropic_max_tokens: int = Field(default=2048)
    anthropic_max_tokens_deep: int = Field(default=8192)

    # --- Claude Web-Suche (Server-Tool, $10 / 1000 Suchen) ---
    # Claude entscheidet selbst ob gesucht wird; max_uses deckelt Kosten + Latenz pro Request.
    web_search_enabled: bool = Field(default=True)
    web_search_max_uses: int = Field(default=2)

    # --- Home Assistant (Smart-Home + Tesla-Steuerung) ---
    # HA_TOKEN NIEMALS committen/loggen — ausschließlich in .env auf dem Server.
    # URL = Tailscale-IP des Raspberry Pi 5 (HA-Host). Steuerung hart gegen
    # data/ha_entities.yaml allowlist/denylist (services/homeassistant_service.py).
    ha_enabled: bool = Field(default=False)
    ha_url: Optional[str] = Field(default=None)
    ha_token: Optional[str] = Field(default=None)
    ha_timeout_s: int = Field(default=15)
    ha_allowlist_path: str = Field(
        default="/app/app/data/ha_entities.yaml",
        description="Pfad zur Allow-/Deny-Liste für HA-Service-Calls (im Container).",
    )

    # --- Jellyfin (Medien-Kontext, A2) ---
    # JELLYFIN_TOKEN NIEMALS committen/loggen — ausschließlich in .env auf dem Server.
    # Auth-Header ist fix `MediaBrowser Token="<key>"` (X-Emby-Token/?api_key= geben 401).
    # jellyfin_enabled-Default (None) wird im JellyfinService aus dem Vorhandensein von
    # URL+Token abgeleitet (fail-closed); True/False erzwingt. main.py reicht den Wert nur durch.
    jellyfin_url: Optional[str] = Field(default=None)
    jellyfin_token: Optional[str] = Field(default=None)
    jellyfin_enabled: Optional[bool] = Field(
        default=None,
        description="None = auto (aus URL+Token ableiten); True/False erzwingt.",
    )
    jellyfin_timeout_s: float = Field(default=10.0)

    # --- Mistral AI (nicht mehr im Antwortpfad seit 2026-10-30; Client-Code bleibt) ---
    mistral_api_key: Optional[str] = Field(default=None)
    mistral_model: str = Field(default="mistral-small-latest")

    # --- Cerebras Cloud (DONNA-81: deaktiviert — kein AVV, DSGVO-Risiko US-Provider) ---
    # Felder bleiben für zukünftige Reaktivierung nach AVV-Abschluss
    cerebras_api_key: Optional[str] = Field(default=None)
    cerebras_model: str = Field(default="llama-3.3-70b")

    # --- Gemini model (Fallback / Legacy) ---
    # gemini-2.5-flash-lite: funktioniert mit EU-Key, höheres Quota als 2.5-flash  ✅ DEFAULT
    # gemini-2.5-flash: 20 req/Tag Free-Tier-Limit (schnell erschöpft)
    # gemini-2.0-flash / 2.0-flash-lite: limit:0 (kein Free-Tier in EU fuer diesen Key)
    # gemini-1.5-*: nicht mehr in v1beta API
    gemini_model: str = Field(default="gemini-2.5-flash-lite")

    # --- Paths ---
    vault_path: str = Field(default="/vault")
    chroma_path: str = Field(default="/data/chroma")

    # --- Notifications ---
    ntfy_topic: str = Field(default="donna-alerts")
    ntfy_url: str = Field(default="https://ntfy.your-donna-instance.example.com")

    # --- mem0 Vektor-Gedächtnis (Phase 1b: Claude + lokales Embedding, kein Ollama) ---
    # Aktiv nur bei DONNA_MEM0=true. Werden in services/ltm_service.py direkt aus der
    # ENV gelesen (gleiche Namen, gleiche Defaults). LLM nutzt ANTHROPIC_API_KEY.
    # ACHTUNG: Embedding-Modell-Wechsel macht bestehende Qdrant-Vektoren unbrauchbar
    # → scripts/reindex_mem0.py ausführen. Dim muss zur Collection brain_ltm passen (768).
    mem0_llm_model: str = Field(default="claude-haiku-5-5")
    mem0_embed_model: str = Field(default="jinaai/jina-embeddings-v2-base-de")
    mem0_embed_dims: int = Field(default=768)
    # Recall-Schwelle: mem0 2.x (Hybrid-Score) liefert niedrigere Scores als v1.
    # Per ENV MEM0_MIN_SCORE tunbar OHNE Image-Rebuild/Code-Änderung — die Änderung wird
    # aber erst nach `docker compose up -d --force-recreate api` wirksam (ENV wird beim
    # Container-Start gelesen; ein reines `restart` übernimmt geänderte .env-Werte nicht).
    # Nach Re-Index mit echten Queries nachjustieren (Jina-Embeddings: gute Treffer grob 0.26–0.38).
    mem0_min_score: float = Field(default=0.30)
    # Anzahl der Recall-Treffer pro Chat-Frage. Höher = mehr Kontext bei breiten
    # Fragen (z.B. "liste meine Projekte"), etwas mehr Input-Tokens. Live per ENV.
    mem0_top_k: int = Field(default=8)
    # A5 (#7): Relevanz-Schwelle für den LTM-Kontinuitäts-Block (thread_state) beim Chatstart.
    # Liegt am OBEREN Rand des dokumentierten Jina-Gut-Treffer-Bands (0.26–0.38) und bleibt
    # über mem0_min_score (0.30): Donna soll nur bei klar relevantem früheren Stand anknüpfen.
    # 0.45 (vorher) lag ÜBER dem Band → Feature feuerte praktisch nie (Under-Trigger); der
    # eigentliche Schutz gegen Fehl-Surfacing ist der Prompt selbst (Angebot + Vorrang-Regel
    # "nur bei eindeutigem Anschluss, sonst ignorieren"), daher ist 0.38 sicher.
    # HINWEIS: grob/ungemessen kalibriert — mit Produktionsdaten via ENV LTM_CONTINUITY_MIN_SCORE
    # nachziehen (greift nach Container-Neustart).
    ltm_continuity_min_score: float = Field(default=0.38)
    # Failure-Cooldown nach fehlgeschlagenem mem0-Init (Sekunden; 0 = aus). Wird in
    # services/ltm_service.py direkt aus der ENV MEM0_INIT_COOLDOWN_S gelesen.
    mem0_init_cooldown_s: float = Field(default=300.0)
    fastembed_cache_path: str = Field(
        default="/data/chroma/fastembed_cache",
        description="Persistenter Cache für das fastembed-ONNX-Modell (~0.64 GB).",
    )

    # --- Local LLM (Ollama) ---
    ollama_url: str = Field(default="http://ollama:11434")
    local_llm_model: str = Field(default="qwen2.5:7b")
    local_llm_timeout_s: int = Field(default=60)

    # --- Gemini Timeout ---
    gemini_timeout_s: int = Field(default=45, description="Timeout für Gemini API Calls in Sekunden")

    # --- Scheduler / Ops ---
    scheduler_enabled: bool = Field(default=True)
    consolidation_cron_day_of_week: str = Field(default="sun")
    consolidation_cron_hour: int = Field(default=2)
    consolidation_similarity_threshold: float = Field(default=0.82)

    ram_monitor_interval_min: int = Field(default=5)
    ram_alert_threshold_mb: int = Field(default=14000)

    # --- CORS ---
    cors_allow_origins: str = Field(default="*")

    # --- STM (Short-Term Memory, Phase 5) ---
    stm_db_path: str = Field(
        default="/data/appdata/stm.db",
        description="Filesystem path for the SQLite STM database (inside container).",
    )

    # --- LTM (Long-Term Memory, Phase 6) ---
    ltm_db_path: str = Field(
        default="/data/chroma/ltm",
        description="Filesystem path for ChromaDB LTM store (persistent volume mount).",
    )

    # --- Stream-Memory (Twitch-Wissen, getrennt von persönlichem Brain) ---
    stream_stm_db_path: str = Field(
        default="/data/appdata/stream_stm.db",
        description="SQLite DB für kurzfristigen Stream-Kontext (Viewer-Sessions).",
    )
    stream_ltm_db_path: str = Field(
        default="/data/chroma/stream_ltm",
        description="ChromaDB für langfristiges Stream-Wissen (von brain-ingest, persistent volume mount).",
    )

    # --- Mood-Detection (DONNA-7) ---
    mood_db_path: str = Field(
        default="/data/appdata/mood.db",
        description="SQLite DB path for mood_log table.",
    )

    # --- Consistency-Tracking (DONNA-7) ---
    consistency_db_path: str = Field(
        default="/data/appdata/consistency.db",
        description="SQLite DB path for usage_log table.",
    )

    # --- Clustering (DONNA-8) ---
    clustering_min_cluster_size: int = Field(
        default=3,
        description="Minimale Cluster-Größe für HDBSCAN.",
    )
    clustering_cron_hour: int = Field(
        default=2,
        description="Stunde (UTC) für den nächtlichen Clustering-Lauf.",
    )

    # --- Feedback (👍/👎 Ratings) ---
    feedback_db_path: str = Field(
        default="/data/appdata/feedback.db",
        description="SQLite DB path für feedback_log.",
    )

    # --- Twitch-Bot ---
    twitch_bot_token: str | None = Field(default=None, description="OAuth Token oauth:xxxx")
    twitch_client_id: str | None = Field(default=None, description="Twitch App Client-ID für Helix API")
    twitch_client_secret: str | None = Field(default=None, description="Twitch App Client-Secret für App-Token-Refresh")
    twitch_broadcaster_login: str = Field(default="your-twitch-channel", description="Twitch-Login des Streamers (für Live-Check)")
    twitch_broadcaster_id: str | None = Field(default=None, description="Twitch User-ID des Streamers (für PATCH /helix/channels — DONNA-16)")
    twitch_channel: str | None = Field(default=None, description="Kanal-Name ohne #")
    twitch_bot_name: str | None = Field(default=None, description="Bot-Account-Name")
    twitch_bot_enabled: bool = Field(default=False)
    twitch_rate_limit_sec: int = Field(default=30)
    # --- Twitch EventSub Webhook (DONNA-205) ---
    # HMAC-SHA256-Secret für Signatur-Validierung eingehender EventSub-Requests.
    # Pflicht für /chat/webhook/twitch — ohne Secret: Endpoint liefert 503.
    # Muss identisch sein mit dem 'secret' der EventSub-Subscription bei Twitch.
    twitch_webhook_secret: str | None = Field(
        default=None,
        description="HMAC-Secret für Twitch-EventSub-Signatur-Validierung (DONNA-205).",
    )

    # --- Twitch EventSub WebSocket (DONNA-266) ---
    # Separate App-Registration (nicht die twitchio-App) — channel:bot muss
    # vom Broadcaster autorisiert worden sein, user:bot von donna_bot.
    twitch_eventsub_client_id: str | None = Field(
        default=None,
        description="Client-ID der Twitch-App für EventSub WebSocket (DONNA-266).",
    )
    twitch_eventsub_client_secret: str | None = Field(
        default=None,
        description="Client-Secret der Twitch-App (Reserve, aktuell ungenutzt — DONNA-266).",
    )
    twitch_bot_user_token: str | None = Field(
        default=None,
        description="donna_bot User Access Token (user:read:chat + user:bot) für EventSub-Subscription (DONNA-266).",
    )
    twitch_bot_user_id: str | None = Field(
        default=None,
        description="Twitch User-ID des Bot-Accounts (donna_bot = 1487744717) für EventSub-Subscription.",
    )
    twitch_bot_refresh_token: str | None = Field(
        default=None,
        description="donna_bot Refresh-Token (Authorization Code Flow) für automatischen Token-Refresh (DONNA-267).",
    )

    # --- Redis (Twitch Chat Pub/Sub, DONNA-201) ---
    # Redis ist `abgemiked_redis` (chat-tool/Steuer-Tool-Stack, Netzwerk abgemiked_data,
    # Alias `redis`). assistent-api ist via abgemiked_net in diesem Netzwerk.
    # Passwort-geschützt → REDIS_URL MUSS in .env gesetzt sein:
    #   REDIS_URL=redis://:<REDIS_PASSWORD>@redis:6379/2
    redis_url: str = Field(
        default="redis://redis:6379/2",
        description="Redis-URL für Twitch-Chat-Pub/Sub (DB 2, Channels twitch:msg/twitch:event). Passwort via .env.",
    )
    redis_enabled: bool = Field(
        default=True,
        description="Redis-Subscriber aktivieren (DONNA-201).",
    )

    # --- Tracking (GPS + App-Aktivität, DONNA-Phase 7) ---
    tracking_db_path: str = Field(
        default="/data/appdata/tracking.db",
        description="SQLite DB path für tracking_events.",
    )

    # --- TTS: Edge-TTS (kostenlos) + ElevenLabs (primäre Cloud-Stimme) ---
    # ElevenLabs ist die primäre Cloud-Stimme; Edge-TTS ist automatischer Fallback
    # (bei fehlendem Key, 401/403/429, Quota oder Netzfehler). Siehe routes/tts.py.
    # ELEVENLABS_API_KEY NIEMALS committen/loggen — ausschließlich in .env auf dem Server.
    # Der Key ist serverseitig auf IPv4 YOUR_SERVER_IP beschränkt → httpx erzwingt
    # local_address=YOUR_SERVER_IP (IPv6 würde abgelehnt).
    elevenlabs_api_key: Optional[str] = Field(default=None)
    # Erlaubte Stimmen (genau diese zwei): "Eva" (Favorit) + "Elisabeth" (Alternative).
    elevenlabs_voice_id: str = Field(default="R24r0IV80Iw9eyByq1ps")  # Eva
    elevenlabs_voice_id_fallback: str = Field(default="3EKymYQPdS1Q9fxZBuq8")  # Elisabeth
    # Persistenter TTS-Settings-Store (voice_id/model_id). /data/appdata ist der
    # persistente Bind-Mount (/data selbst ist flüchtig!).
    tts_settings_path: str = Field(default="/data/appdata/tts_settings.json")

    # --- TB3 screen_context (Request-Feld ChatIn.screen_context) ---
    # Sicherheits-Gate (Fix-Runde A #7): standardmäßig AUS. screen_context wird erst
    # NACH der Injection-Härtung (Fix-Runde B, vor dem APK) aktiviert. Bis dahin ist
    # das Feld in Prod nachweislich inert (_build_live_screen_context_block → None).
    screen_context_enabled: bool = Field(default=False)

    # --- A4b screen vision (Request-Feld ChatIn.screen_image_base64) ---
    # Live-Bubble: EIN Screen-Frame on-demand → Gemini-Vision → sachliche Bildbeschreibung,
    # die als UNVERTRAUENSWÜRDIGER Kontext-Block in den Prompt fließt (nie ins LTM, nie
    # geloggt, fail-closed im Live-/Stream-Fall). Default AN — Kernfeature dieses APKs; das
    # Bild wird vom Client nur gesendet, wenn der Nutzer sich auf den Bildschirm bezieht.
    # Env-Override SCREEN_VISION_ENABLED=false schaltet den Pfad komplett inert.
    screen_vision_enabled: bool = Field(default=True)
    # Max. Base64-Länge des Screen-Frames (4 MB Base64 ≈ 3 MB Bild).
    screen_vision_max_base64: int = Field(default=4 * 1024 * 1024)

    # --- Voice-Auth Hardening (Phase 3) ---
    # All values configurable via environment — defaults are security-conservative.
    voice_auth_rate_limit: int = Field(
        default=5,
        description="Max voice-auth attempts per IP within the sliding window.",
    )
    voice_auth_cooldown_min: int = Field(
        default=15,
        description="Cooldown duration in minutes after rate limit is exceeded.",
    )
    voice_auth_nonce_ttl_sec: int = Field(
        default=30,
        description="How long a submitted nonce stays valid (replay window).",
    )
    voice_auth_challenge_ttl_sec: int = Field(
        default=60,
        description="How long a liveness challenge remains valid before expiry.",
    )
    voice_auth_timestamp_skew_sec: int = Field(
        default=30,
        description="Maximum allowed difference between request timestamp and server time.",
    )

    def cors_origins_list(self) -> list[str]:
        raw = (self.cors_allow_origins or "").strip()
        if not raw or raw == "*":
            return ["*"]
        return [o.strip() for o in raw.split(",") if o.strip()]

    def vault_dir(self) -> Path:
        return Path(self.vault_path)

    def chroma_dir(self) -> Path:
        return Path(self.chroma_path)


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    """Cached settings accessor."""
    return Settings()
