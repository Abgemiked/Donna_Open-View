# Changelog

## [0.7.0] — 2026-04-25 — DONNA-8: HDBSCAN Nightly Clustering + Muster-Erkennung + Vault-Profil

### Added
- **ClusteringService** (`backend/app/services/clustering_service.py`) — HDBSCAN-basiertes
  Nightly Clustering aller LTM-Embeddings aus ChromaDB. Läuft täglich 02:00 UTC via APScheduler.
  - HDBSCAN (min_cluster_size=3, metric=euclidean) clustert alle LTM-Vektoren
  - TF-IDF Keyword-Extraktion pro Cluster (Top-3 Terme, deutsche + englische Stopwörter gefiltert)
  - Cluster-Namen: `cluster_<top_keyword>` — ASCII-lowercase, max 30 Zeichen
  - LTM-Metadaten-Update: `metadata["cluster"]` in ChromaDB nach jedem Lauf
  - Schreibt `vault/profile/clusters.md` (Markdown-Profil mit Cluster-Summary, Top-Keywords, Beispielen)
  - Dry-Run-Modus: schreibt keine Dateien, loggt nur was passiert wäre
  - Clustering-Status wird in SQLite (`clustering_status`-Tabelle) gespeichert
- **PatternService** (`backend/app/services/pattern_service.py`) — Nutzungsmuster-Erkennung
  aus SQLite `event_log` Tabelle.
  - Event-Typen: `chat_message`, `ltm_store`, `mood_log`, `voice_input`
  - Pattern 1: Häufigste Tageszeit (morning/afternoon/evening/night)
  - Pattern 2: Häufigste Wochentage (Top-3)
  - Pattern 3: Durchschnittliche Session-Länge (Messages pro Session)
  - Pattern 4: Häufigste LTM-Kategorien
  - Schreibt `vault/profile/patterns.md`
  - `log_event()` Methode für andere Services
- **Clustering Router** (`backend/app/routes/clustering.py`)
  - `POST /clustering/run?dry_run=true` — manueller Trigger, Bearer-Auth
  - `GET /clustering/status` — letzter Lauf (Timestamp, Cluster-Count, Entry-Count)
- **APScheduler CronJob** in `main.py` — täglich 02:00 UTC: Clustering + Pattern-Analyse
- **Vault-Unterverzeichnisse** in `main.py` lifespan: `profile/`, `inbox/`, `ideas/` werden
  automatisch angelegt wenn nicht vorhanden
- **3 neue Settings** in `config.py`:
  - `clustering_min_cluster_size` (default: 3)
  - `clustering_cron_hour` (default: 2)
- **9 Tests** in `tests/test_donna8.py`:
  - Slugify-Tests (3), TF-IDF-Tests (2), peak_time-Pattern (1),
  - mock_embeddings-Clustering (1), vault_profile-Schreibung (1), dry_run-Endpoint (1)

### Changed
- `requirements.txt`: `hdbscan==0.8.40`, `scikit-learn==1.5.2`, `numpy==1.26.4` hinzugefügt
- `main.py`: ClusteringService + PatternService initialisiert, in `app.state` registriert,
  `/clustering`-Router eingebunden, Nightly-Clustering-Job im Scheduler
- `config.py`: 2 neue Clustering-Settings

---

## [0.6.1] — 2026-04-25 — DONNA-7: Mood-Detection, RAM-Alert, Consistency-Tracking, LTM-Curation

### Added
- **MoodService** (`backend/app/services/mood_service.py`) — Keyword-basierte Stimmungserkennung
  (kein ML). Kategorien: frustrated, happy, tired, focused. Confidence-Score basierend auf
  Keyword-Trefferquote. Log via SQLite (`mood_log`-Tabelle). Manuelle Korrektur über API.
- **ConsistencyService** (`backend/app/services/consistency_service.py`) — Nutzungs-Tracking
  pro Tag (SQLite `usage_log`). Streak, 30-Tage-Aktivität, heutiger Zähler. Kein Scham-Trigger.
- **LTM-Curation-Job** (`backend/app/jobs/ltm_curation.py`) — Quartalsweise Bereinigung via
  APScheduler CronJob (`0 3 1 */3 *`). Merge Embedding-Duplikate (L2 < 0.05), archiviere
  Low-Confidence (< 0.3) und verwaiste Einträge in `_forget/` Ordner. Dry-Run-Modus.
- **`POST /ltm/curate?dry_run=true`** — Manueller Curation-Trigger mit Dry-Run-Support.
- **Stats-Router** (`backend/app/routes/stats.py`):
  - `GET /stats/consistency` — Nutzungs-Zusammenfassung (streak, total_30d, today_count)
  - `GET /stats/mood?days=7` — Mood-History der letzten N Tage
  - `POST /stats/mood/{log_id}/correct` — Mood-Korrektur durch Mike
- **3 neue Settings** in `config.py`: `mood_db_path`, `consistency_db_path`. `ntfy_url`
  und `ntfy_topic` auf selbst-gehostete ntfy-Instanz aktualisiert.
- **`.env.example`** ergänzt: `NTFY_URL`, `NTFY_TOPIC`, `RAM_ALERT_THRESHOLD_MB`,
  `MOOD_DB_PATH`, `CONSISTENCY_DB_PATH`.
- **14 Tests** in `tests/test_donna7.py` (alle grün): Mood-Detection (frustrated/happy/neutral/
  low-confidence), Mood-Log/Correct, Consistency-Streak, 30d-Zähler, record_message, Summary,
  LTM-Curation Dry-Run + leere Collection.

### Changed
- `chat.py`: Mood-Detection nach User-Message (confidence ≥ 0.7 → log), Consistency-Tracking
  (record_message) — beides best-effort, bricht Chat-Flow nicht.
- `main.py`: MoodService + ConsistencyService initialisiert, LTM-Curation-CronJob registriert,
  `/stats`-Router registriert.
- `ntfy_topic` Type von `Optional[str]` auf `str` geändert (Default: `"donna-alerts"`).

### Security
- Mood-Daten bleiben vollständig lokal — werden NIE an Gemini oder externe Services gesendet.

---

## [0.6.0] — 2026-04-25 — Phase 6: LTM-Service — ChromaDB-basierter Langzeit-Speicher

### Added
- **LTMService** (`backend/app/services/ltm_service.py`) — ChromaDB PersistentClient,
  speichert Nutzer-Präferenzen, Fakten und Gewohnheiten dauerhaft. Deduplizierung via
  L2-Distanz-Schwelle (< 0.1). Kategorien: `user_preference`, `user_fact`, `user_habit`.
- **`GET /ltm`** — alle gespeicherten Memories abrufen (auth-geschützt).
- **`DELETE /ltm/{memory_id}`** — einzelne Memory löschen (auth-geschützt).
- **LTM-Recall im Chat-Prompt** — `POST /chat` lädt bis zu 5 relevante Memories semantisch
  und fügt sie als `[Langzeitgedächtnis über den Nutzer]`-Block VOR der STM-History ein.
- **Trigger-basiertes Speichern** — Nachrichten mit Triggern wie "ich mag", "ich wohne",
  "ich heiße" etc. werden automatisch nach LLM-Antwort im LTM gespeichert.
- **1 neues Setting** `ltm_db_path` (default: `data/ltm`) in `config.py`
- **4 Tests** in `tests/test_ltm.py` (TDD, alle grün): store+recall, Deduplication, Category-Filter, Delete

### Changed
- `chat.py`: `_build_prompt_with_history` um `ltm_memories`-Parameter erweitert, LTM zuerst im Prompt
- `main.py`: LTMService initialisiert, `app.state.ltm` gesetzt, `/ltm`-Router registriert

## [0.5.0] — 2026-04-25 — Phase 5: STM-Service / Session-Context (DONNA-19)

### Added
- **STM-Service** (`backend/app/services/stm_service.py`) — session-basierter
  Short-Term Memory via SQLite + aiosqlite. Speichert Gesprächsverlauf pro
  Session-ID, TTL-Filter (2h), Auto-Cleanup aller Einträge >24h beim Start.
- **`GET /stm/{session_id}`** — letzten N Messages einer Session abrufen (auth-geschützt).
- **`DELETE /stm/{session_id}`** — alle Messages einer Session löschen (auth-geschützt).
- **Session-Kontext im Chat** — `POST /chat` lädt STM-History vor LLM-Call und
  speichert User-Message + Assistant-Antwort nach der Antwort.
- **`X-Session-ID`-Header** — wird in jeder Chat-Antwort zurückgeschickt. Client
  kann ID aus Payload, Header oder auto-generiert (UUID4) setzen.
- **`aiosqlite==0.20.0`** in `requirements.txt`
- **1 neues Setting** `stm_db_path` (default: `/data/stm.db`) in `config.py`
- **6 Tests** in `tests/test_stm.py` (TDD, alle grün)

### Changed
- `chat.py`: `_build_prompt` → `_build_prompt_with_history` (inklusive STM-History-Block)
- `main.py`: STM-Service-Init + Router-Registrierung im Lifespan
- `.env.example`: `STM_DB_PATH` dokumentiert

---

## [0.3.0] — 2026-04-24 — Phase 3: Voice-Auth Hardening (DONNA-4)

### Added
- **Voice-Auth Hardening Endpoint** — Separater, eigenständiger Endpoint-Pfad (`/voice-auth/*`).
  Die bestehende HMAC Bearer-Auth (`core/auth.py`) bleibt vollständig unverändert.
- **`GET /voice-auth/challenge`** — Gibt UUID4 Challenge-ID + zufälligen deutschen Satz (Liveness-Check).
  Challenge ist single-use und läuft nach 60s ab.
- **`POST /voice-auth/verify`** — Multi-Schritt-Verifikation:
  1. Rate-Limit-Check (Sliding Window, 5 Versuche/min/IP, 15 min Cooldown)
  2. Timestamp-Skew-Prüfung (max. ±30s)
  3. Nonce-Verbrauch (single-use, TTL 30s) — Replay-Schutz
  4. Challenge-Verbrauch (single-use, TTL 60s) — Liveness-Garantie
  5. Audio-Hash-Format-Validierung (SHA-256 Hex, 64 Zeichen)
- **`backend/app/core/nonce_store.py`** — Thread-safe asyncio-Lock In-Memory Store mit TTL
  (separat für Nonces und Challenges). Auto-Cleanup alle 10s via Background-Task.
- **`backend/app/core/rate_limiter.py`** — `SlidingWindowRateLimiter` mit Cooldown-State pro IP.
  Kein Redis — reines In-Memory.
- **`backend/app/services/voice_auth_service.py`** — Business-Logic inkl. strukturiertem Logging
  aller Failed-Attempts (structlog, Felder: ip, reason, challenge_id, nonce).
- **`backend/app/schemas/voice_auth.py`** — Pydantic-Modelle: `ChallengeResponse`, `VerifyRequest`,
  `VerifyResponse`, `ErrorResponse`. `VerifyRequest` validiert audio_hash-Format strikt.
- **`backend/app/data/challenge_phrases.py`** — 61 deutsche Liveness-Sätze (4-8 Wörter).
- **16 Tests** in 4 Testdateien (TDD, alle grün):
  - `test_voice_auth_nonce.py` — NonceStore (6 Tests)
  - `test_voice_auth_rate_limit.py` — RateLimiter (7 Tests)
  - `test_voice_auth_challenge.py` — Challenge-Lifecycle + Timestamp (7 Tests)
  - `test_voice_auth_integration.py` — End-to-End + Regression Bearer-Auth (9 Tests)
- **5 neue Settings** in `config.py`:
  `VOICE_AUTH_RATE_LIMIT`, `VOICE_AUTH_COOLDOWN_MIN`, `VOICE_AUTH_NONCE_TTL_SEC`,
  `VOICE_AUTH_CHALLENGE_TTL_SEC`, `VOICE_AUTH_TIMESTAMP_SKEW_SEC`

### Notes
- Phase 3 implementiert NUR die Hardening-Schicht. Echte Stimm-Biometrie → Phase 4.
- Replay-Angriff mit mitgeschnittenem Audio-Sample schlägt fehl: Nonce + Timestamp verbraucht,
  Challenge single-use, Rate-Limit begrenzt Brute-Force.

## [0.2.1] — 2026-04-24 — Gemini Model Switch (Free-Tier-Fix)

### Changed
- **Default Gemini-Model:** `gemini-2.0-flash` → `gemini-2.5-flash`.
  - Grund: `gemini-2.0-flash` (+ `2.0-flash-lite`) haben fuer Mike's Free-Tier-Key `limit: 0` (kein Free-Tier verfuegbar) -> HTTP 429.
  - `gemini-1.5-flash` ist in Mike's Projekt nicht mehr gelistet.
  - Live-Probe gegen das `/v1beta/models`-Endpoint des Keys: `gemini-2.5-flash`, `gemini-2.5-flash-lite`, `gemini-flash-latest` antworten sauber, `2.0-flash*` werfen 429.
  - Wahl: `gemini-2.5-flash` — neuestes Flagship-Flash, ohne Thinking-Output, Free-Tier-fähig.
- **Neu konfigurierbar:** `GEMINI_MODEL` in `.env` (default `gemini-2.5-flash`). Modellwechsel ohne Code-Aenderung moeglich.
- `/health` zeigt jetzt zusätzlich `gemini_model` (aktives Model).

## [0.2.0] — 2026-04-24 — Phase 2

### Added
- **Ollama-Container** (`assistent-ollama`) mit Llama 3.1 8B Q4 (mem_limit 6500 MB, reservation 5500 MB).
- **`SmartRouter`** (`backend/app/services/smart_router.py`): Heuristik-Routing local↔gemini.
  - PII-Detektoren: IBAN DE, Steuer-ID (11-stellig), Steuernummer, Phone DE, PLZ+Stadt, Email.
  - Sensible Tags: `#privat`, `#intern`, `#sensibel`, `#nsfw`, `#geheim`.
  - Sensible Keywords (Word-Boundary): `passwort`, `steuer`, `rechnung`, `gehalt`, `kontonummer`, `kreditkarte`, `ausweis`, `einkommen`, etc.
  - Längen-Heuristik: `prompt+context > 6000 Zeichen` → gemini.
  - Route-Entscheidung wird geloggt und in Response-Headern ausgegeben — kein silent failover.
- **`LocalLLMClient`** (`backend/app/services/local_llm_client.py`): Async httpx-Client für Ollama `/api/chat` mit Streaming und `health()`. Fehler heben `LocalLLMUnavailable` — keine stille Degradation.
- **2-Vault-Struktur** (`backend/app/services/vault_service.py`):
  - Neue Ordner: `stm/inbox`, `stm/daily`, `ltm/ideas`, `ltm/notes`, `ltm/profile`, `ltm/clusters`, `ltm/_consolidation_log`, `_forget`.
  - Phase-1-Ordner (`inbox/ideas/...`) bleiben als Aliase funktional (back-compat).
  - Neue APIs: `write_stm()`, `write_ltm(subfolder=...)`, `move_to_forget(reason=...)`, `list_stm()`, `list_ltm()`.
  - Path-Traversal-Schutz weiterhin aktiv.
- **ChromaDB Multi-Collection** (`backend/app/services/vector_store.py`):
  - Zwei Collections: `brain_stm`, `brain_ltm` (cosine).
  - Legacy `brain` → Alias auf `brain_ltm`.
  - `count_all()` listet beide.
- **Weekly Consolidation Job** (`backend/app/jobs/consolidation.py`):
  - APScheduler CronTrigger Sonntag 02:00 UTC.
  - Idempotent pro ISO-Woche (Log-File als Sentinel).
  - Embedding via Gemini text-embedding-004, Fallback auf deterministische Shingle-Hashes ohne Key.
  - Duplikate (cos ≥ threshold) → `_forget/` mit Reason-File.
  - Neues → `ltm/notes/` mit Source-Footer.
  - Summary-Log nach `ltm/_consolidation_log/YYYY-WW.md`.
- **RAM-Monitor Job** (`backend/app/jobs/ram_monitor.py`):
  - APScheduler IntervalTrigger (default 5 min).
  - ntfy-Alert bei `psutil.virtual_memory().used > 14000 MB` (konfigurierbar).
  - Cooldown 30 min gegen Alert-Flooding.
- **`/chat` Endpoint** (`backend/app/routes/chat.py`):
  - `POST /chat` — Bearer-Auth (hmac.compare_digest, Shared-Kernel-Wiederverwendung).
  - Best-effort RAG-Retrieval (5 LTM + 3 STM) via ChromaDB.
  - Streaming `text/event-stream`.
  - Response-Header: `X-Route`, `X-Route-Reason`, `X-Retrieval-Hits`, `X-Route-Fallback`.
  - Local→Gemini-Fallback sichtbar (kein silent failover), Notice wird in den Stream geschrieben.
- **`/health`** erweitert: `version`, `chroma_collections` (count je Collection), `local_llm_reachable`, `local_llm_model`.
- **Config** (`backend/app/config.py`): neue Felder `ollama_url`, `local_llm_model`, `local_llm_timeout_s`, Scheduler-Cron, Thresholds, `ntfy_url`.
- **Unit-Tests** (`backend/tests/`): 34 Tests grün — SmartRouter (19), VaultService (9), Consolidation (3), RAM-Monitor (3).

### Changed
- `docker-compose.yml`: Service `ollama` hinzugefügt, `api` bekommt `depends_on: ollama` und neue ENV-Variablen (`OLLAMA_URL`, `LOCAL_LLM_MODEL`). Neues Volume `ollama_models`.
- `backend/requirements.txt`: `httpx`, `apscheduler`, `psutil`, `pytest`, `pytest-asyncio`.
- `backend/app/main.py`: APScheduler in Lifespan gestartet (RAM + Consolidation Jobs), LocalLLMClient + SmartRouter im `app.state`.
- Version: `0.1.0` → `0.2.0`.

### Deviations vom Plan-MD
- **Auth-Header:** Plan-MD spezifizierte `X-Admin-Token`. Umgesetzt mit bestehendem Bearer-Token-Kernel (`app/core/auth.py`, hmac.compare_digest) um Shared-Kernel-Duplikation zu vermeiden (Regel aus `role-senior-dev.md`). Beide sind funktional äquivalent; kein UX-Unterschied für Mike (Curl-Call gleich: `-H "Authorization: Bearer $ADMIN_TOKEN"`).

### Security / Ops
- Kein silent failover: Local→Gemini-Fallback erscheint als `[warn]`-Zeile im Stream UND im Log.
- RAM-Alert bei > 14 GB (Upgrade-Trigger auf CCX33).
- Path-Traversal-Schutz im `_forget/`-Move explizit validiert.
- Consolidation idempotent — sicher gegen Re-Run.

### Not Yet (explicit out-of-scope für Phase 2)
- Voice-Auth-Hardening (Phase 3, Stufe 3).
- Twitch-Bot + Pen-Test (Phase 5, Stufe 3).
- LTM-Curation-Job quartalsweise (Phase 6).
- Proaktivitäts-Feedback-Loop (Phase 6).
- Samsung Side-Button + Fallback (Phase 4).
- Mood-Detection (Phase 6).
- Consistency-Tracking (Phase 6).

## [0.1.0] — 2026-04-23 — Phase 1
- FastAPI + ChromaDB + Gemini-Proxy + Vault + Bearer-Auth.
- Docker Compose + Syncthing + NPM-Reverse-Proxy.
- Deploy auf Your Cloud Server, `/health` grün.
