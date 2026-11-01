# Deploy Phase 2 — Local LLM + Smart Router + /chat

## Vorbedingungen
- Phase 1 läuft, `/health` grün (`vault_mounted`, `chroma_ready`).
- Minecraft auf CCX23 aus (Ollama braucht ~5.5 GB RAM).
- `.env` enthält `ADMIN_TOKEN` und idealerweise `GEMINI_API_KEY` + `NTFY_TOPIC`.

## Deploy-Schritte (auf Hetzner, vollständiger Cycle)

```bash
ssh your-server
cd /opt/donna

# 1) Code holen
git pull

# 2) Images bauen / ziehen
docker compose pull
docker compose build api

# 3) Ollama-Container starten (zuerst, damit das Model gezogen werden kann)
docker compose up -d ollama

# 4) Model pullen (~5.5 GB, dauert 5-10 Minuten)
docker exec -it assistent-ollama ollama pull llama3.1:8b-instruct-q4_K_M

# 5) API neustarten (pickt Ollama automatisch auf, depends_on sorgt dafür)
docker compose up -d api

# 6) Logs kurz beobachten
docker compose logs --tail=50 api
```

## Smoke-Tests

```bash
# Health inkl. neuer Felder
curl -s https://your-donna-instance.example.com/health | jq

# Erwartete neue Felder:
#   "version": "0.2.0"
#   "chroma_collections": {"brain_stm": 0, "brain_ltm": 0}
#   "local_llm_reachable": true
#   "local_llm_model": "llama3.1:8b-instruct-q4_K_M"
```

```bash
# Chat: allgemeine Frage (→ gemini)
curl -N -H "Authorization: Bearer $ADMIN_TOKEN" \
     -H "Content-Type: application/json" \
     -X POST https://your-donna-instance.example.com/chat \
     -d '{"message":"Wie wird das Wetter morgen in Bochum?"}' \
     -D -

# In den Response-Headern muss stehen:
#   X-Route: gemini
#   X-Route-Reason: default
#   X-Retrieval-Hits: 0
#   X-Route-Fallback: none
```

```bash
# Chat: sensible Frage (→ local)
curl -N -H "Authorization: Bearer $ADMIN_TOKEN" \
     -H "Content-Type: application/json" \
     -X POST https://your-donna-instance.example.com/chat \
     -d '{"message":"Mein Passwort war letztens anders, erinnerst du dich?"}' \
     -D -

# Header:
#   X-Route: local
#   X-Route-Reason: sensitive_keyword
```

## RAM-Check

```bash
free -m
# Used sollte zwischen 7.5 und 9 GB liegen (API + Ollama geladenes Model + übriger Stack).
# > 14000 MB → ntfy-Alert fired automatisch alle 5 min.
```

## Troubleshooting

| Symptom | Check |
|---|---|
| `local_llm_reachable: false` | `docker compose logs ollama` + `docker exec assistent-ollama ollama list` |
| `X-Route-Fallback: gemini` | Model noch nicht gezogen? `docker exec assistent-ollama ollama pull llama3.1:8b-instruct-q4_K_M` |
| `/chat` hängt > 60 s | Ollama RAM-Starvation — `free -m` prüfen, ggf. Minecraft oder anderen Container stoppen |
| Consolidation läuft nicht | `/health` zeigt kein Scheduler-Feld? Dann `SCHEDULER_ENABLED=false` in .env entfernen |
| ntfy-Alert kommt nicht | `NTFY_TOPIC` gesetzt? `curl -d "test" https://ntfy.sh/$NTFY_TOPIC` testen |

## Rollback

```bash
ssh your-server
cd /opt/donna
git checkout <phase-1-commit>
docker compose up -d api
docker compose stop ollama
```

Das Phase-1-Verhalten ist 100 % back-compat: alte Vault-Ordner (`inbox/`, `ideas/` etc.) funktionieren als Aliase weiter, ChromaDB `brain` → `brain_ltm`.
