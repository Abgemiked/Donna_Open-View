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

    # --- Mistral AI (ersetzt Gemini als primären Cloud-LLM, EU-Server) ---
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

    # --- TTS läuft im separaten melotts-Container (services/melotts), siehe routes/tts.py ---

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
