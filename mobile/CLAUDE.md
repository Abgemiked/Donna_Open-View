# Projekt: DonnaApp (Phase 4 — React Native)

## Session-Start — PFLICHT
Vor jeder Arbeit an diesem Projekt:
1. `Skill("project-manager")` aufrufen
2. PM liest `~/.claude/results/assistent/projektmanager-memory.md`
3. Erst dann Aufgabe entgegennehmen

## Projekt-Kontext
- **User:** Mike (ADHS, Twitch-Streamer, Samsung S25 Ultra)
- **Ziel:** Native Android + Windows App für Donna-Assistent
- **Backend:** https://your-donna-instance.example.com (FastAPI, Phase 3+ deployed)
- **Stack:** React Native 0.76 (New Architecture), Yarn Workspaces, Turborepo
- **Android:** Sprache, Side-Button, Streaming-Chat (Samsung S25 Ultra)
- **Windows:** System-Tray, Strg+Shift+D Hotkey, Overlay-Popup, Sprache (Cortana-Style)
- **Voice:** Hybrid — WinRT SpeechRecognizer (Windows) + @react-native-voice/voice (Android)

## Monorepo-Struktur
- `apps/android/` — React Native Android App (donna-android)
- `apps/windows/` — React Native Windows App (donna-windows)
- `packages/shared/` — Shared Business Logic (@donna/shared, ~80% Code-Sharing)

## Verbotene Aktionen
- Kein git push auf main ohne Review-Rat (Stufe 2: >=4/6 PASS)
- Keine Kundendaten ungefiltert an externe KI-Services
- Kein Cloud-Sync außer Syncthing P2P
