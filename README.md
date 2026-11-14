# Assistent — Neural Brain

Persönlicher KI-Assistent für Mike. Lernt wie Mike denkt, nicht wie ein generisches LLM antwortet.

- **Stack:** Python 3.12 + FastAPI, ChromaDB (persistent), Ollama + Llama 3.1 8B (local LLM), Gemini API, Syncthing, Docker Compose
- **Reverse Proxy:** Nginx Proxy Manager (extern, `nginx-proxy-manager_web_net`)
- **Domain:** `your-donna-instance.example.com`
- **Server:** Your Cloud Server "your-server" (`ssh your-server`) → `/opt/donna`

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                        Donna AI                             │
│                                                             │
│  ┌──────────┐   ┌─────────────┐   ┌──────────────────────┐ │
│  │  Mobile  │   │  Twitch Bot │   │    Voice Pipeline    │ │
│  │  App     │   │  (stream    │   │  (Whisper STT +      │ │
│  │(Android/ │   │   context)  │   │   Silero VAD)        │ │
│  │ Windows) │   └──────┬──────┘   └──────────┬───────────┘ │
│  └────┬─────┘          │                     │             │
│       └────────────────┼─────────────────────┘             │
│                        ▼                                    │
│              ┌─────────────────┐                           │
│              │   Smart Router  │  Mistral → Gemini →       │
│              │  (PII-Filter +  │  Local LLM (fallback)     │
│              │  Privacy Guard) │                           │
│              └────────┬────────┘                           │
│                       │                                    │
│          ┌────────────┼──────────────┐                     │
│          ▼            ▼              ▼                     │
│     ┌─────────┐ ┌──────────┐ ┌───────────┐                │
│     │   STM   │ │   LTM    │ │  Twitch   │                │
│     │(session │ │(ChromaDB │ │  Brain    │                │
│     │ vault)  │ │  + RAG)  │ │ (isolated │                │
│     └─────────┘ └──────────┘ │  context) │                │
│                               └───────────┘                │
└─────────────────────────────────────────────────────────────┘
```

**Two isolated memory spaces on shared infrastructure** — a deliberate architectural decision:
Personal brain and Twitch stream brain run on the same backend with strict context isolation
and different privacy rules (GDPR-compliant, no cross-context data leakage).

## External Knowledge Systems

Donna's proactive behavior depends on two external tools that are **not** in this repo:

| Tool | Role | Why it matters |
|------|------|----------------|
| **Linear** | Project & task management (MCP-connected) | Donna reads open tasks, blockers, sprint state — enables proactive "what's next?" reasoning |
| **Obsidian** | Personal knowledge graph (Syncthing P2P sync) | Long-term memory nodes, concept links, and context that feeds into LTM retrieval |

Without these integrations Donna functions as a stateless chatbot.
With them she builds context over time and acts proactively.

> **Note for self-hosting:** The knowledge graph (Obsidian vault) and task state (Linear) remain
> private — they are the *data*, not the *system*. You can substitute your own tools or start
> with an empty vault.

## Phase 1 — Setup

### 1. DNS vorbereiten
A-Record `your-donna-instance.example.com` → IP der Hetzner-Box setzen. Bei Cloudflare Proxy auf **DNS only** (graue Wolke), damit Caddy direkt Let's Encrypt per HTTP-01 kriegt.

### 2. Projekt auf den Server
```bash
ssh your-server
mkdir -p /opt/donna && cd /opt/donna
# per git clone oder rsync vom lokalen Rechner
```

### 3. .env anlegen
```bash
cp .env.example .env
# ADMIN_TOKEN setzen (Pflicht):
openssl rand -hex 32
# DONNA_TOTP_SECRET setzen (für Mobile-App-Pairing, Pflicht):
python3 -c "import pyotp; print(pyotp.random_base32())"
# Optional: GEMINI_API_KEY aus https://aistudio.google.com/app/apikey
# Optional: NTFY_TOPIC für Push-Notifications
```

### 4. Start
```bash
docker compose up -d --build
docker compose logs -f api
```

### 5. Health-Check
```bash
curl -s https://your-donna-instance.example.com/health | jq
```

Erwartete Antwort:
```json
{
  "status": "ok",
  "vault_mounted": true,
  "chroma_ready": true,
  "gemini_key_present": false,
  "vault_notes_count": 0
}
```

### 6. Mobile App verbinden (TOTP-Pairing)
```bash
# QR-Code generieren (einmalig):
curl -s https://your-donna-instance.example.com/setup/qr \
  -H "Authorization: Bearer $ADMIN_TOKEN" | jq .qr_base64 | tr -d '"' | base64 -d > qr.png
# qr.png mit Google Authenticator scannen
# Ab da: beim ersten App-Start → 6-stelligen Code eingeben → gepairt
```

### 7. Erste Notiz schreiben
```bash
curl -X POST https://your-donna-instance.example.com/vault/note \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"content":"# Hallo Brain\n\nErster Test.","title":"hallo","folder":"inbox"}'
```

## Struktur
```
backend/         FastAPI-Service (Dockerfile, app/)
vault/           Markdown-Brain (inbox/ideas/notes/daily/profile)
mobile/          Platzhalter für Capacitor-App (Phase 3)
Caddyfile        Reverse-Proxy + TLS
docker-compose.yml
```

## Design-Regeln
- System bootet **ohne** `GEMINI_API_KEY` (nur Warning, kein Crash).
- Auth = Bearer-Token, Single-Secret, Single-User.
- Alle Vault-Operationen mit Path-Traversal-Schutz.
- Vault-Inhalte werden **nicht** nach Git committed (siehe `.gitignore`).

## Phase 2 — Local LLM + Smart Router + 2-Vault (2026-04-24)
- Ollama-Container mit Llama 3.1 8B Q4 (mem_limit 6500 MB)
- **Smart Router** entscheidet local↔gemini: PII (IBAN/Steuer-ID/Phone/Email/Address), sensible Tags (`#privat`, `#intern`), CRM-Allowlist, Länge > 6000 Zeichen → Gemini
- **2-Vault-Struktur:** `stm/` (inbox/daily), `ltm/` (notes/ideas/profile/clusters/_consolidation_log), `_forget/` (Review-Queue)
- **Weekly Consolidation:** Sonntag 02:00 UTC, idempotent pro ISO-Woche — Duplikate nach `_forget/`, Neues nach `ltm/notes/`
- **/chat Endpoint:** RAG-Retrieval (5 LTM + 3 STM), Streaming-Response, Debug-Header `X-Route`, `X-Route-Reason`, `X-Retrieval-Hits`, `X-Route-Fallback`
- **RAM-Monitor:** alle 5 min — ntfy-Alert bei Used > 14000 MB (CCX33-Upgrade-Trigger)

Siehe `docs/deploy-phase2.md` für Deploy-Schritte.

## Nächste Phasen
- **Phase 3:** Voice-Auth mit Hardening (Resemblyzer + Liveness-Challenge + Rate-Limit + Replay-Schutz).
- **Phase 4:** React-Native-Android-App mit Side-Button-Fallback.
- **Phase 5:** Twitch-Bot mit Pen-Test (>=50 Injection-Versuche).
- **Phase 6:** LTM-Curation (quartalsweise), Mood-Detection, Consistency-Tracking.

---

## Running your own instance

This repo is the open portfolio version of a personal AI assistant. You can run your own instance:

### Requirements

| Component | Purpose |
|-----------|---------|
| **Python 3.11+** + Docker | Backend runtime |
| **Gemini API Key** | Primary LLM (optional — local Ollama works without it) |
| **ChromaDB** | Vector database for Long-Term Memory (runs in Docker) |
| **Ollama** | Local LLM fallback (Llama 3.1 8B, auto-downloaded) |
| **React Native 0.76** | Android + Windows mobile apps |

### External dependencies (not included in this repo)

- **Obsidian + Linear** — The brain/memory layer and project tracking used in the original setup.
  This repo does not include the Obsidian vault (`YOUR_BRAIN_DIR/`). For your own instance,
  any Markdown-based note system works as a vault.
- **Twitch Integration** — Requires a Twitch bot account + app credentials from
  [dev.twitch.tv](https://dev.twitch.tv/console/apps). Fully optional.
- **ntfy** — Push notification service for background alerts. Can be self-hosted or use ntfy.sh.

### Quick start

```bash
# 1. Copy environment template
cp .env.example .env
# Edit .env — set ADMIN_TOKEN (required), DONNA_TOTP_SECRET (required for mobile pairing),
# and optional GEMINI_API_KEY
# Generate TOTP secret: python3 -c "import pyotp; print(pyotp.random_base32())"

# 2. Start
docker compose up -d --build

# 3. Verify
curl -s http://localhost:8000/health | jq
```

### Open-View sync (this file)

This public repo is automatically synced from the private main repository via GitHub Actions.
Sensitive data (domain, email, Twitch channel names) is replaced by placeholders.
See [`scripts/anonymize-for-open-view.sh`](scripts/anonymize-for-open-view.sh) for the anonymization script.
The GitHub Actions workflow (`.github/workflows/sync-open-view.yml`) is not included in this portfolio fork
as it references the private source repository.
