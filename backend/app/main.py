"""FastAPI entrypoint for the Assistent backend."""
from __future__ import annotations

import asyncio
import multiprocessing as _mp
from contextlib import asynccontextmanager
from datetime import datetime, timezone

# DONNA-STT-Fix: Whisper-Worker läuft als separater OS-Process. Auf Linux ist
# der Default 'fork', was bei ctranslate2 (C++-Threads bereits initialisiert
# im Parent) zu Deadlocks/CUDA-Problemen führen kann. 'spawn' startet einen
# frischen Python-Interpreter — sauber und plattform-konsistent.
# WICHTIG: muss VOR jedem anderen multiprocessing-Import gesetzt werden.
try:
    _mp.set_start_method("spawn", force=False)
except RuntimeError:
    # Methode wurde bereits gesetzt (z. B. in Tests) — ignorieren.
    pass

from apscheduler.schedulers.asyncio import AsyncIOScheduler
from apscheduler.triggers.cron import CronTrigger
from apscheduler.triggers.interval import IntervalTrigger
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from slowapi import Limiter, _rate_limit_exceeded_handler
from slowapi.util import get_remote_address
from slowapi.errors import RateLimitExceeded
from app.config import get_settings
from app.core.logger import configure_logging, get_logger
from app.jobs.consolidation import run_consolidation
from app.jobs.ram_monitor import RamAlertState, check_ram
from app.routes import chat as chat_routes
from app.routes import health as health_routes
from app.routes import vault as vault_routes
from app.routes import stm as stm_routes
from app.routes import ltm as ltm_routes
from app.routes import voice_auth as voice_auth_routes
from app.routes import stats as stats_routes
from app.routes import clustering as clustering_routes
from app.routes import tts as tts_routes
from app.routes import briefing as briefing_routes
from app.routes import mood as mood_routes
from app.routes import consistency as consistency_routes
from app.routes import tracking as tracking_routes
from app.routes import tracking_places as tracking_places_routes
from app.routes import feedback as feedback_routes
from app.routes import twitch as twitch_routes
from app.routes import speech as speech_routes
from app.routes import wake_word as wake_word_routes
from app.routes import setup as setup_routes
from app.routes import presence as presence_routes
from app.routes import notify as notify_routes
from app.routes import calendar as calendar_routes
from app.routes import health_data as health_data_routes
from app.routes import notifications as notifications_routes
from app.routes import smarthome as smarthome_routes
from app.routes import admin_test as admin_test_routes
from app.routes import vision as vision_routes
from app.routes import ideas as ideas_routes
from app.routes import projekte as projekte_routes
from app.routes import admin_service as admin_service_routes
import app.core.service_state as service_state
from app.services.calendar_service import CalendarService
from app.services.verwaltung_db import VerwaltungDbService
from app.services.graphiti_service import GraphitiService
from app.services.idea_service import IdeaService
from app.services.tracking_service import TrackingService
from app.services.places_service import PlacesService
from app.services.feedback_service import FeedbackService
from app.services.twitch_bot_service import TwitchBotService
from app.services.redis_subscriber import RedisSubscriber
from app.services.clustering_service import ClusteringService
from app.services.gemini_client import GeminiClient
from app.services.mistral_client import MistralClient
from app.services.local_llm_client import LocalLLMClient
from app.services.pattern_service import PatternService
from app.services.smart_router import SmartRouter
from app.services.stm_service import STMService
from app.services.ltm_service import LTMService
from app.services.vault_service import VaultService
from app.services.vector_store import VectorStore
from app.services.voice_auth_service import VoiceAuthService
from app.services.mood_service import MoodService
from app.services.consistency_service import ConsistencyService
from app.services.presence_service import PresenceService
from app.jobs.ltm_curation import run_curation
from app.jobs.stm_to_ltm import run_stm_to_ltm
from app.jobs.stream_stm_to_ltm import run_stream_stm_to_ltm
from app.jobs.stream_ltm_to_personal import run_stream_ltm_to_personal
from app.jobs.event_proactive import (
    DONNA_EVENT_PROACTIVITY_ENABLED,
    schedule_event_proactive_jobs,
)
from app.jobs.stream_live_watcher import (
    DONNA_TWITCH_PROACTIVE_ENABLED,
    check_and_notify as _stream_live_check,
)

# Cleanup interval for nonce/challenge stores (seconds)
_NONCE_CLEANUP_INTERVAL_SEC = 10


@asynccontextmanager
async def lifespan(app: FastAPI):
    """Initialise services once per process; tear down on shutdown."""
    settings = get_settings()
    configure_logging(settings.log_level)
    log = get_logger("main")

    # --- Vault ---
    vault = VaultService(settings.vault_dir())
    try:
        vault.ensure_structure()
        log.info("vault_ready", path=str(settings.vault_dir()))
    except Exception as e:  # noqa: BLE001
        log.error("vault_init_failed", error=str(e))

    # --- Vector store ---
    vector = VectorStore(settings.chroma_dir())
    vector.ready()

    # --- Gemini (Fallback / Web-Search-Grounding) ---
    gemini = GeminiClient(api_key=settings.gemini_api_key, model=settings.gemini_model)

    # --- Mistral (primärer Cloud-LLM, ersetzt Gemini) ---
    mistral = MistralClient(api_key=settings.mistral_api_key, model=settings.mistral_model)
    log.info("mistral_init", model=settings.mistral_model, ready=mistral.ready())

    # --- Local LLM + Smart Router (Phase 2) ---
    local_llm = LocalLLMClient(
        base_url=settings.ollama_url,
        model=settings.local_llm_model,
        timeout_s=settings.local_llm_timeout_s,
    )
    smart_router = SmartRouter()

    # --- STM Service (Phase 5) ---
    stm = STMService(db_path=settings.stm_db_path)
    await stm.init()
    await stm.cleanup_old_messages()  # remove stale entries from previous runs

    # --- DONNA-42 C: STM → Obsidian /vault/stm Sync ---
    from app.services.stm_obsidian_sync import StmObsidianSync
    stm_vault_sync = StmObsidianSync(
        stm_db_path=settings.stm_db_path,
        vault_root=str(settings.vault_dir()),
    )
    # Starte Hintergrund-Loop: erster Sync beim Start (Backfill aller bisherigen
    # Messages), danach alle 5 Min ein Increment-Sync.
    stm_vault_sync.start_background_loop(interval_sec=300.0)

    # --- LTM Service (Phase 6) — persönliches Brain ---
    ltm = LTMService(db_path=settings.ltm_db_path)

    # --- Stream-Memory (getrennt vom persönlichen Brain) ---
    from app.services.ltm_service import _STREAM_COLLECTION_NAME as _SCOL
    stream_stm = STMService(db_path=settings.stream_stm_db_path)
    await stream_stm.init()
    await stream_stm.cleanup_old_messages()
    stream_ltm = LTMService(
        db_path=settings.stream_ltm_db_path,
        collection_name=_SCOL,
    )

    # --- Mood-Detection Service (DONNA-7) ---
    mood = MoodService(db_path=settings.mood_db_path)

    # --- Consistency-Tracking Service (DONNA-7) ---
    consistency = ConsistencyService(db_path=settings.consistency_db_path)

    # --- Activity & GPS Tracking Service (DONNA-Phase7) ---
    tracking = TrackingService(db_path=settings.tracking_db_path)
    await asyncio.get_event_loop().run_in_executor(None, tracking.cleanup_old_events)

    # --- Google Calendar Service (DONNA-107) ---
    # Kalender-PII nur In-Memory, keine LTM-Persistenz. (Art. 5(2) DSGVO)
    # Graceful Fallback: kein Crash wenn Credentials fehlen — Service bleibt deaktiviert.
    calendar_svc = CalendarService(
        client_id=settings.google_client_id,
        client_secret=settings.google_client_secret,
        refresh_token=settings.google_refresh_token,
    )

    # --- Graphiti / Knowledge Graph (DONNA-111) ---
    # Lazy async-init — kein Crash wenn Neo4j noch nicht hochgefahren.
    # Feature-Flag DONNA_GRAPHITI=false (default) — aktivieren erst nach DONNA-110-Stabilität.
    graphiti_svc = GraphitiService()

    # --- VerwaltungDbService (STE-217) — Read-Only-Zugriff auf verwaltung.projects ---
    verwaltung_db = VerwaltungDbService()

    # --- IdeaService (DONNA-115) — strukturierte Ideen-Erfassung ---
    try:
        idea_svc = IdeaService(
            ltm=ltm,
            vault_path=settings.vault_path,
            graphiti_svc=graphiti_svc,
        )
        log.info("idea_service_ready", vault_path=settings.vault_path)
    except Exception as _idea_e:  # noqa: BLE001
        log.warning("idea_service_init_failed", error=str(_idea_e))
        idea_svc = None

    # --- Presence Service (DONNA-96/97) ---
    presence_svc = PresenceService(tracking, str(settings.vault_dir()))

    # --- Places Service (GPS-Gewohnheiten) ---
    places = PlacesService(tracking_db_path=settings.tracking_db_path)

    # --- Feedback Service (👍/👎) ---
    feedback = FeedbackService(db_path=settings.feedback_db_path)

    # --- Proaktivitäts-Feedback-Loop (DONNA-7) ---
    from app.services.proactivity_service import ProactivityService
    proactivity = ProactivityService(feedback_svc=feedback)

    # --- Twitch-Bot ---
    twitch_bot: TwitchBotService | None = None
    if settings.twitch_bot_enabled and settings.twitch_bot_token and settings.twitch_channel:
        twitch_bot = TwitchBotService(
            token=settings.twitch_bot_token,
            channel=settings.twitch_channel,
            bot_name=settings.twitch_bot_name or "DonnaBot",
            donna_api_url="http://localhost:8000",
            donna_api_token=settings.admin_token or "",
            rate_limit_sec=settings.twitch_rate_limit_sec,
            stream_stm=stream_stm,  # DONNA-42 B+: Per-User-Verlauf in twitch-stm speichern
        )
        await twitch_bot.start()

    # --- Vault-Unterverzeichnisse sicherstellen (DONNA-8) ---
    from pathlib import Path as _Path
    _Path(settings.vault_path, "profile").mkdir(parents=True, exist_ok=True)
    _Path(settings.vault_path, "inbox").mkdir(parents=True, exist_ok=True)
    _Path(settings.vault_path, "ideas").mkdir(parents=True, exist_ok=True)

    # --- Clustering + Pattern Service (DONNA-8) ---
    clustering_svc = ClusteringService(
        ltm_service=ltm,
        vault_path=settings.vault_path,
        status_db_path=settings.stm_db_path,
        min_cluster_size=settings.clustering_min_cluster_size,
    )
    pattern_svc = PatternService(
        db_path=settings.stm_db_path,
        vault_path=settings.vault_path,
    )

    # --- Voice-Auth Service (Phase 3) ---
    voice_auth = VoiceAuthService(
        rate_limit=settings.voice_auth_rate_limit,
        window_sec=60,  # 1-minute sliding window (fixed, not configurable)
        cooldown_sec=settings.voice_auth_cooldown_min * 60,
        nonce_ttl_sec=settings.voice_auth_nonce_ttl_sec,
        challenge_ttl_sec=settings.voice_auth_challenge_ttl_sec,
        timestamp_skew_sec=settings.voice_auth_timestamp_skew_sec,
    )

    # --- Scheduler ---
    scheduler: AsyncIOScheduler | None = None
    ram_state = RamAlertState()
    if settings.scheduler_enabled:
        scheduler = AsyncIOScheduler(timezone="UTC")
        service_state.scheduler = scheduler  # DONNA-199: für Admin-Toggle-Routen

        async def _ram_job():
            await check_ram(
                threshold_mb=settings.ram_alert_threshold_mb,
                ntfy_topic=settings.ntfy_topic,
                ntfy_url=settings.ntfy_url,
                state=ram_state,
            )

        def _consolidation_job():
            try:
                run_consolidation(
                    vault=vault,
                    gemini=gemini,
                    threshold=settings.consolidation_similarity_threshold,
                )
            except Exception as e:  # noqa: BLE001
                log.error("consolidation_job_failed", error=str(e))

        scheduler.add_job(
            _ram_job,
            trigger=IntervalTrigger(minutes=settings.ram_monitor_interval_min),
            id="ram_monitor",
            replace_existing=True,
            max_instances=1,
            coalesce=True,
        )
        scheduler.add_job(
            _consolidation_job,
            trigger=CronTrigger(
                day_of_week=settings.consolidation_cron_day_of_week,
                hour=settings.consolidation_cron_hour,
                minute=0,
            ),
            id="consolidation",
            replace_existing=True,
            max_instances=1,
            coalesce=True,
        )

        # --- Nightly Clustering (DONNA-8): täglich 02:00 UTC ---
        async def _clustering_job() -> None:
            try:
                result = await clustering_svc.run_nightly_clustering(dry_run=False)
                patterns = pattern_svc.detect_patterns(days=30)
                pattern_svc.write_patterns_md(patterns, dry_run=False)
                log.info(
                    "clustering_job_done",
                    entry_count=result.get("entry_count", 0),
                    cluster_count=result.get("cluster_count", 0),
                    noise_count=result.get("noise_count", 0),
                )
            except Exception as e:  # noqa: BLE001
                log.error("clustering_job_failed", error=str(e))

        scheduler.add_job(
            _clustering_job,
            trigger=CronTrigger(hour=settings.clustering_cron_hour, minute=0),
            id="nightly_clustering",
            replace_existing=True,
            max_instances=1,
            coalesce=True,
        )

        # --- LTM-Curation: quartalsweise (1. Jan/Apr/Jul/Okt, 03:00 UTC) ---
        async def _ltm_curation_job() -> None:
            try:
                await run_curation(ltm, dry_run=False)
            except Exception as e:  # noqa: BLE001
                log.error("ltm_curation_job_failed", error=str(e))

        scheduler.add_job(
            _ltm_curation_job,
            trigger=CronTrigger(month="1,4,7,10", day=1, hour=3, minute=0),
            id="ltm_curation",
            replace_existing=True,
            max_instances=1,
            coalesce=True,
        )
        # --- DONNA-14: STM→LTM Promotion (täglich 01:00 UTC) ---
        async def _stm_to_ltm_job() -> None:
            try:
                result = await run_stm_to_ltm(stm, ltm, mistral, dry_run=False)
                log.info("stm_to_ltm_job_done", **result)
            except Exception as e:  # noqa: BLE001
                log.error("stm_to_ltm_job_failed", error=str(e))

        scheduler.add_job(
            _stm_to_ltm_job,
            trigger=CronTrigger(hour=1, minute=0),
            id="stm_to_ltm",
            replace_existing=True,
            max_instances=1,
            coalesce=True,
        )

        # --- DONNA-101: stream_stm → stream_ltm (täglich 03:00 UTC) ---
        async def _stream_stm_to_ltm_job() -> None:
            try:
                result = await run_stream_stm_to_ltm(stream_stm, stream_ltm, local_llm)
                log.info("stream_stm_to_ltm_job_done", **result)
            except Exception as e:  # noqa: BLE001
                log.error("stream_stm_to_ltm_job_failed", error=str(e))

        scheduler.add_job(
            _stream_stm_to_ltm_job,
            trigger=CronTrigger(hour=3, minute=0),
            id="stream_stm_to_ltm",
            replace_existing=True,
            max_instances=1,
            coalesce=True,
        )

        # --- DONNA-102: stream_ltm → personal_ltm (wöchentlich Mo 04:00 UTC) ---
        async def _stream_ltm_to_personal_job() -> None:
            try:
                result = await run_stream_ltm_to_personal(stream_ltm, ltm, local_llm)
                log.info("stream_ltm_to_personal_job_done", **result)
            except Exception as e:  # noqa: BLE001
                log.error("stream_ltm_to_personal_job_failed", error=str(e))

        scheduler.add_job(
            _stream_ltm_to_personal_job,
            trigger=CronTrigger(day_of_week="mon", hour=4, minute=0),
            id="stream_ltm_to_personal",
            replace_existing=True,
            max_instances=1,
            coalesce=True,
        )

        # --- DONNA-97: Presence MD (alle 5 Min) ---
        def _presence_job() -> None:
            try:
                presence_svc.write_presence_md()
            except Exception as e:  # noqa: BLE001
                log.error("presence_job_failed", error=str(e))

        scheduler.add_job(
            _presence_job,
            trigger=IntervalTrigger(minutes=5),
            id="presence_md",
            replace_existing=True,
            max_instances=1,
            coalesce=True,
        )

        scheduler.start()

        # --- DONNA-112: Stream-Live-Watcher (alle 2 Min, optional) ---
        if DONNA_TWITCH_PROACTIVE_ENABLED:
            async def _stream_live_watcher_job() -> None:
                try:
                    await _stream_live_check(
                        twitch_bot_service=twitch_bot,
                        gemini_client=gemini,
                        broadcaster_login=settings.twitch_broadcaster_login,
                        client_id=settings.twitch_client_id,
                        access_token=settings.twitch_bot_token,
                    )
                except Exception as e:  # noqa: BLE001
                    log.error("stream_live_watcher_job_failed", error=str(e))

            scheduler.add_job(
                _stream_live_watcher_job,
                trigger=IntervalTrigger(minutes=2),
                id="stream_live_watcher",
                replace_existing=True,
                max_instances=1,
                coalesce=True,
            )
            log.info("stream_live_watcher_enabled", interval_min=2, broadcaster=settings.twitch_broadcaster_login)
        else:
            log.info("stream_live_watcher_disabled", reason="DONNA_TWITCH_PROACTIVE not set or false")

        # --- DONNA-113: Morgen-Briefing (täglich 10:00 UTC) ---
        async def _morning_brief_job() -> None:
            try:
                await proactivity.morning_brief(
                    calendar_svc=calendar_svc,
                    ltm_svc=ltm,
                    gemini_client=gemini,
                    ntfy_url=settings.ntfy_url,
                    ntfy_topic=settings.ntfy_topic,
                )
            except Exception as e:  # noqa: BLE001
                log.error("morning_brief_job_failed", error=str(e))

        scheduler.add_job(
            _morning_brief_job,
            trigger=CronTrigger(hour=7, minute=0, timezone="UTC"),
            id="morning_brief",
            replace_existing=True,
            max_instances=1,
            coalesce=True,
            misfire_grace_time=3600,
        )

        # --- DONNA-113: Abend-Check-in (täglich 16:00 UTC) ---
        async def _evening_checkin_job() -> None:
            try:
                await proactivity.evening_checkin(
                    ltm_svc=ltm,
                    gemini_client=gemini,
                    ntfy_url=settings.ntfy_url,
                    ntfy_topic=settings.ntfy_topic,
                )
            except Exception as e:  # noqa: BLE001
                log.error("evening_checkin_job_failed", error=str(e))

        scheduler.add_job(
            _evening_checkin_job,
            trigger=CronTrigger(hour=16, minute=0, timezone="UTC"),
            id="evening_checkin",
            replace_existing=True,
            max_instances=1,
            coalesce=True,
            misfire_grace_time=3600,
        )
        log.info("proactivity_push_jobs_registered", morning_utc="07:00", evening_utc="16:00")

        # --- Startup Catch-Up: verpasste Jobs nachholen (DONNA-Bugfix) ---
        _now = datetime.now(timezone.utc)
        _today_morning = _now.replace(hour=7, minute=0, second=0, microsecond=0)
        _today_evening = _now.replace(hour=16, minute=0, second=0, microsecond=0)

        if _today_morning < _now < _today_morning.replace(hour=13):
            log.info("startup_catchup_triggered", job="morning_brief", now_utc=_now.isoformat())
            asyncio.create_task(_morning_brief_job())
        else:
            log.info("startup_catchup_skipped", job="morning_brief", now_utc=_now.isoformat())

        if _today_evening < _now < _today_evening.replace(hour=21):
            log.info("startup_catchup_triggered", job="evening_checkin", now_utc=_now.isoformat())
            asyncio.create_task(_evening_checkin_job())
        else:
            log.info("startup_catchup_skipped", job="evening_checkin", now_utc=_now.isoformat())

        # --- DONNA-109: Event-getriggerte Proaktivität (optional) ---
        if DONNA_EVENT_PROACTIVITY_ENABLED:
            schedule_event_proactive_jobs(
                scheduler,
                app.state,
                ntfy_url=settings.ntfy_url,
                ntfy_topic=settings.ntfy_topic,
            )
            log.info("event_proactivity_enabled", stream_window_min=30, calendar_window_min=15)
        else:
            log.info("event_proactivity_disabled", reason="DONNA_EVENT_PROACTIVITY not set or false")

        log.info("scheduler_started", jobs=["ram_monitor", "consolidation", "nightly_clustering", "ltm_curation", "stm_to_ltm", "stream_stm_to_ltm", "stream_ltm_to_personal", "presence_md"])

    if not settings.admin_token:
        log.warning(
            "admin_token_missing",
            detail="ADMIN_TOKEN not set — authenticated endpoints return 503.",
        )

    # --- TOTP Pairing (DONNA-103) ---
    if settings.donna_totp_secret:
        app.state.totp_secret = settings.donna_totp_secret
        log.info("totp_configured")
    else:
        app.state.totp_secret = None
        log.warning(
            "totp_secret_missing",
            detail="DONNA_TOTP_SECRET not set — /setup/pair returns 503.",
        )
    app.state.used_totp_codes: dict[str, float] = {}
    app.state.totp_rate_limits: dict[str, list[float]] = {}

    app.state.settings = settings
    app.state.vault = vault
    app.state.vector = vector
    app.state.gemini = gemini
    app.state.mistral = mistral
    # DONNA-81: Cerebras deaktiviert — kein AVV, DSGVO-Risiko (US-Provider + LTM-PII)
    app.state.local_llm = local_llm
    app.state.smart_router = smart_router
    app.state.scheduler = scheduler
    app.state.ram_state = ram_state
    app.state.stm = stm
    app.state.stm_vault_sync = stm_vault_sync
    app.state.ltm = ltm
    app.state.stream_stm = stream_stm
    app.state.stream_ltm = stream_ltm
    app.state.mood = mood
    app.state.consistency = consistency
    app.state.clustering = clustering_svc
    app.state.pattern = pattern_svc
    app.state.voice_auth = voice_auth
    app.state.tracking = tracking
    app.state.presence = presence_svc
    app.state.calendar = calendar_svc
    app.state.graphiti = graphiti_svc
    app.state.places = places
    app.state.feedback = feedback
    app.state.proactivity = proactivity
    app.state.twitch_bot = twitch_bot
    app.state.ideas = idea_svc
    app.state.verwaltung_db = verwaltung_db

    # --- Redis-Subscriber: Twitch-Chat Pub/Sub (DONNA-201) ---
    # Redis läuft im chat-tool-Projekt (verwaltung-redis-1, Netzwerk verwaltung_net).
    # assistent-api ist über verwaltung_net bereits drin → Hostname `redis`.
    redis_subscriber: RedisSubscriber | None = None
    if getattr(settings, "redis_enabled", True):
        try:
            redis_subscriber = RedisSubscriber(
                redis_url=settings.redis_url,
                app_state=app.state,
            )
            redis_subscriber.start()
            log.info("redis_subscriber_init", url=settings.redis_url)
        except Exception as _redis_e:  # noqa: BLE001
            log.warning("redis_subscriber_init_failed", error=str(_redis_e))
            redis_subscriber = None
    else:
        log.info("redis_subscriber_disabled", reason="REDIS_ENABLED=false")
    app.state.redis_subscriber = redis_subscriber

    log.info(
        "startup_complete",
        env=settings.app_env,
        vault_mounted=vault.ready(),
        chroma_ready=vector.ready(),
        gemini_key_present=gemini.ready(),
        ollama_url=settings.ollama_url,
        local_llm_model=settings.local_llm_model,
        scheduler_enabled=settings.scheduler_enabled,
        voice_auth_rate_limit=settings.voice_auth_rate_limit,
        voice_auth_challenge_ttl_sec=settings.voice_auth_challenge_ttl_sec,
    )

    # --- Nonce/Challenge Store Cleanup Background Task (Phase 3) ---
    async def _cleanup_nonce_stores() -> None:
        """Periodically remove expired nonces and challenges to bound memory."""
        while True:
            await asyncio.sleep(_NONCE_CLEANUP_INTERVAL_SEC)
            try:
                await voice_auth.cleanup_stores()
            except Exception as exc:  # noqa: BLE001
                log.error("nonce_cleanup_failed", error=str(exc))

    cleanup_task = asyncio.create_task(_cleanup_nonce_stores())
    log.info("nonce_cleanup_task_started", interval_sec=_NONCE_CLEANUP_INTERVAL_SEC)

    # DONNA-191: Piper-TTS entfernt — kein TTS-Warmup mehr nötig.
    # Android nutzt Samsung Neural TTS On-Device.

    # --- Whisper ProcessPool starten (DONNA-STT-Fix) ---
    # Worker-Process lädt das Modell beim ersten Task — Pre-Warm sendet einen
    # Dummy-WAV durch den Pool, damit das erste echte /transcribe nicht 5-10s
    # auf Modell-Laden warten muss. Fire-and-forget, blockiert Startup nicht.
    speech_routes.init_pool()
    log.info("whisper_pool_started")

    async def _whisper_warmup() -> None:
        import wave
        import io
        try:
            # Minimaler 16kHz Mono WAV-Buffer mit 0.5s Stille
            buf = io.BytesIO()
            with wave.open(buf, "wb") as wf:
                wf.setnchannels(1)
                wf.setsampwidth(2)
                wf.setframerate(16000)
                wf.writeframes(b"\x00\x00" * 8000)  # 0.5s @ 16kHz
            wav_bytes = buf.getvalue()

            tmp_fd, tmp_path = __import__("tempfile").mkstemp(suffix=".wav")
            try:
                with __import__("os").fdopen(tmp_fd, "wb") as f:
                    f.write(wav_bytes)

                loop = asyncio.get_event_loop()
                pool = speech_routes.get_pool()
                # Lädt das Modell im Worker-Process (initializer) und führt
                # eine Transkription auf 0.5s Stille durch — danach ist der
                # Worker warm.
                _ = await loop.run_in_executor(pool, speech_routes._worker_transcribe, tmp_path)
                log.info("whisper_warmup_done")
            finally:
                try:
                    __import__("os").unlink(tmp_path)
                except OSError:
                    pass
        except Exception as exc:  # noqa: BLE001
            log.warning("whisper_warmup_failed", error=str(exc))

    asyncio.create_task(_whisper_warmup())
    log.info("whisper_warmup_scheduled")

    try:
        yield
    finally:
        cleanup_task.cancel()
        try:
            await cleanup_task
        except asyncio.CancelledError:
            pass
        await graphiti_svc.close()
        await verwaltung_db.close()
        if redis_subscriber is not None:
            await redis_subscriber.stop()
        if twitch_bot is not None:
            await twitch_bot.stop()
        if scheduler is not None:
            scheduler.shutdown(wait=False)
            log.info("scheduler_stopped")
        # Whisper-Pool sauber herunterfahren (DONNA-STT-Fix)
        try:
            speech_routes.shutdown_pool()
        except Exception as exc:  # noqa: BLE001
            log.warning("whisper_pool_shutdown_failed", error=str(exc))
        log.info("shutdown")


def create_app() -> FastAPI:
    settings = get_settings()

    # Security: Rate-Limiting via slowapi — verhindert Abuse/DoS auf /chat.
    # Limiter wird in app.state gespeichert, damit Route-Dekoratoren darauf zugreifen können.
    limiter = Limiter(key_func=get_remote_address)

    # Security: disable all interactive API docs in production
    # Docs-Endpunkte (/docs, /redoc, /openapi.json) sind ohne Auth erreichbar
    # und exponieren die komplette API-Struktur — daher in Production deaktiviert.
    docs_url = "/docs" if settings.app_env != "production" else None
    redoc_url = "/redoc" if settings.app_env != "production" else None
    openapi_url = "/openapi.json" if settings.app_env != "production" else None

    app = FastAPI(
        title="Assistent API",
        version="0.3.0",
        description="Neural Brain backend for Mike's personal assistent.",
        lifespan=lifespan,
        docs_url=docs_url,
        redoc_url=redoc_url,
        openapi_url=openapi_url,
    )

    # Rate-Limiter an App koppeln
    app.state.limiter = limiter
    app.add_exception_handler(RateLimitExceeded, _rate_limit_exceeded_handler)

    # Security: CORS auf bekannte Origins einschränken — kein wildcard mehr.
    # cors_allow_origins aus Settings sollte in .env auf explizite Liste gesetzt sein.
    # allow_credentials=False: Cookies/Auth-Header werden nicht cross-origin gesendet.
    # Falls die App Bearer-Auth nutzt (kein Cookie), ist False korrekt.
    cors_origins = settings.cors_origins_list()
    # Fallback: wenn noch "*" in der Env steht, auf sichere Defaults beschränken
    if cors_origins == ["*"]:
        cors_origins = [
            "https://your-donna-instance.example.com",
            "http://localhost:3000",
            "http://localhost:8081",
            # Android Emulator — greift auf Host-Loopback via YOUR_SERVER_IP
            "http://YOUR_SERVER_IP:8000",
            "http://YOUR_SERVER_IP:3000",
        ]
    app.add_middleware(
        CORSMiddleware,
        allow_origins=cors_origins,
        allow_credentials=False,
        allow_methods=["GET", "POST", "OPTIONS"],
        allow_headers=["Authorization", "Content-Type", "X-Session-ID", "X-Admin-Token", "X-Test-User-Id"],
    )

    app.include_router(health_routes.router)
    app.include_router(vault_routes.router)
    app.include_router(chat_routes.router)
    app.include_router(stm_routes.router)
    app.include_router(ltm_routes.router)
    app.include_router(voice_auth_routes.router)
    app.include_router(stats_routes.router)
    app.include_router(clustering_routes.router)
    app.include_router(tts_routes.router)
    app.include_router(briefing_routes.router)
    app.include_router(mood_routes.router)
    app.include_router(consistency_routes.router)
    app.include_router(tracking_routes.router)
    app.include_router(tracking_places_routes.router)
    app.include_router(feedback_routes.router)
    app.include_router(twitch_routes.router)
    app.include_router(speech_routes.router)
    app.include_router(wake_word_routes.router)
    app.include_router(setup_routes.router)
    app.include_router(presence_routes.router)
    app.include_router(notify_routes.router)
    app.include_router(calendar_routes.router)
    app.include_router(health_data_routes.router)
    app.include_router(notifications_routes.router)
    app.include_router(smarthome_routes.router)
    app.include_router(admin_test_routes.router)
    app.include_router(admin_service_routes.router)  # DONNA-199
    app.include_router(vision_routes.router)
    app.include_router(ideas_routes.router)
    app.include_router(projekte_routes.router)

    return app


app = create_app()
