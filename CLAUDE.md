# Projekt: Assistent (External Brain / Neural Brain)

## Session-Start — PFLICHT
Vor jeder Arbeit an diesem Projekt:
1. `Skill("project-manager")` aufrufen
2. PM liest `~/.claude/results/assistent/projektmanager-memory.md`
3. Erst dann Aufgabe entgegennehmen

## Anforderungen
→ `~/.claude/skills/project-requirements/Assistent.md`

## Projekt-Kontext
- **User:** Mike (ADHS, Twitch-Streamer, Kleinunternehmer)
- **Ziel:** KI-Assistent + Neural Brain — lernt wie Mike denkt
- **Server:** Your Cloud Server, `/opt/donna`, `ssh your-server`
- **Subdomain:** your-donna-instance.example.com
- **Stack:** Python FastAPI, React Native (Android), ChromaDB, Gemini API, Syncthing, Caddy

## Obsidian-Brain (Dev-Brain)
- **STATUS_QUO:** `YOUR_BRAIN_DIR/m01_Memories/_meta/STATUS_QUO.md`
- **Active Issues:** `YOUR_BRAIN_DIR/m01_Memories/_active/`
- **Linear:** https://linear.app/your-workspace/team/DONNA/all
- **LTM/STM Vaults:** Syncthing-Sync-Ziel (Pfad noch zu klären mit Mike)

## Linear-Protokoll — PFLICHT (wie bei steuerncrm)
- **Source of Truth:** Linear-Team DONNA. Vor jeder Code-Änderung das zugehörige Ticket prüfen.
- **Neue Issues sofort anlegen:** Bei jedem Bug/Feature/Task → SOFORT Linear-Issue erstellen (MCP: `save_issue`)
- **Ticket aktualisieren:** Nach jedem Meilenstein Issue-Status in Linear aktualisieren
- **Issue-Filter:** `list_issues` IMMER mit `team: "DONNA"` (kein Workspace-Scan)

## Issue↔Memory-Abhängigkeit — Definition of Done
Ein Task gilt erst als DONE wenn:
1. ✅ Code geändert + Review-Rat PASS
2. ✅ Linear-Issue als "Done" markiert
3. ✅ Memory-Datei in `YOUR_BRAIN_DIR/m01_Memories/` aktualisiert oder archiviert
4. ✅ Bei neuer Datei: Eintrag in `_MAP_OF_CONTENT.md` ergänzt
**Ein Ticket ohne Memory-Eintrag ist NICHT done.**

## Repo-Struktur
- **Hauptrepo:** `C:/Tools/Assistent` → `github.com/your-github-user/Donna-Assistentin.git`
- **Mobile-Apps:** `mobile/` (React Native Monorepo — Android + Windows + shared)
- **Backend:** `backend/` (Python FastAPI)
- **Alles gehört in dieses Repo** — kein separates DonnaApp-Repo

## ⛔ Android Build — NUR RELEASE, NIEMALS DEBUG

**Debug-APKs sind VERBOTEN.** Debug-APKs brauchen Metro (localhost:8081) — ohne Metro hängt die App.
Release-APKs sind self-contained und verhalten sich wie die echte App.

### Android Release-Build + Install (Samsung S25 Ultra via ADB):
```bash
cd mobile/apps/android/android
.\gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```
Bei Signaturproblem: `adb uninstall com.yourcompany.donna` zuerst, dann install.

### Windows (lokal):
```bash
cd mobile && yarn windows
```
**Regel:** Claude installiert nach jedem Android-Build via ADB. Mike muss nicht selbst installieren.
**ABSOLUTES VERBOT:** `assembleDebug`, `app-debug.apk`, Metro starten — unter keinen Umständen.

## Verbotene Aktionen
- Kein git push auf main ohne Review-Rat (Ausnahme: Stufe-0 Trivial-Änderungen)
- Keine Kundendaten ungefiltert an Gemini
- Kein Schreibzugriff auf steuern-crm-DB
- Kein Cloud-Sync (nur Syncthing P2P)
- Kein lokales LLM auf CCX23 ohne Container-Grenzen (mem_limit Pflicht)
