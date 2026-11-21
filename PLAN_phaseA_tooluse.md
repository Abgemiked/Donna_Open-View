# PLAN — Phase A: Inbox-Issue als erstes natives Anthropic-Tool (Ebene-2-Migration)

**Projekt:** donna-assistentin · **Branch:** `feat/donna-tooluse-phaseA` · **Basis:** `origin/main` @ `634740e`
**Rolle:** Senior/Backend-Dev (team-dev) · **Modell der App:** `claude-haiku-5-5`
**Autoritativ:** `~/.claude/results/assistent/ARCHITEKTUR_native_tooluse.md` (Abschnitte 3–6, 8)

---

## Ziel
Die Inbox-Issue-Fähigkeit wird vom Text-Marker-/Anleitungsblock-Ansatz auf **native Anthropic
Tool-Use** umgestellt — NUR für Issues. Alle anderen Marker (DONNA_ACTION, save_memory,
home_control, navigate/Tesla, set_stream_title …) bleiben unverändert. Kein Big-Bang.

## Betroffene Dateien
- `backend/app/services/claude_client.py` — Tool-Use-Round-Trip in `stream()` (+ `_MAX_TOOL_ROUNDS`, `tool_executor`-Param).
- `backend/app/routes/chat.py` — conditional Tool-Liste + `tool_executor`-Closure; Entfernen der Issue-Marker-Pfade.
- `backend/tests/test_tooluse_phaseA.py` — NEU, TDD-Unit-Tests.

## DoD-Checkliste (team-dev)
- [x] PLAN_MD auf dem Branch angelegt.
- [x] `stream()` iteriert Content-Block-Events (Text-Deltas yield, tool_use gesammelt).
- [x] Round-Trip-Schleife mit GETRENNTEN Zählern (`_MAX_PAUSE_CONTINUATIONS` + `_MAX_TOOL_ROUNDS`).
- [x] `pause_turn`/`tool_use`/`refusal`/`max_tokens`/end_turn alle behandelt.
- [x] `max_tokens` mit offenem tool_use → NICHT ausführen (fail-closed).
- [x] 3 Tools (propose/confirm/cancel), kompakte input_schema, strict + eager_input_streaming.
- [x] Conditional inclusion (ready & nicht live/injection/web) = fail-closed Live-Gating.
- [x] `tool_executor`-Closure: Defense-in-depth-Gate + sanitize + pending + create_issue.
- [x] propose: kein Write; confirm: Write nur via `take_pending`; cancel: verwirft.
- [x] Entfernt: build_instruction_block-Injektion, Turn-1-Marker-Handling, Turn-2-Regex-Pfad, ISSUE_CONFIRM aus Stripper-Lookahead.
- [x] Behalten: `_pending_issues`, create_issue, compose_issue, resolve_project_label, sanitize_issue_field/_MARKERISH_RE, alle Live-Gates, alle anderen Marker, Twitch AUS.
- [x] Unit-Tests grün (neu) + bestehende Tests grün.
- [x] Commit auf Branch (kein Push/Merge/Deploy).

## Invarianten-Checkliste (Architektur-Doc Abschnitt 8)
- [x] Live-Guard/Live-Privacy fail-closed: privates Tool nicht in `tools`-Liste wenn live/injection/web + Executor-Check.
- [x] Keine privaten Daten im TTS-Stream: tool_use ist separater Content-Block, nie durch text_stream.
- [x] Menschliche Bestätigung vor GitHub-Write: propose→confirm, Write nur via `take_pending` (single-use, TTL).
- [x] Tesla-Deny unberührt; Twitch bleibt AUS.
- [x] fail-closed: create_issue→None / Exception / max_tokens-Trunkierung nie als Erfolg.
- [x] Secrets/Token nie loggen: Executor loggt nur name/status/number/error_type.
- [x] Feld-Sanitisierung auf jeden String-Tool-Input (sanitize_issue_field).
- [x] Latenz unverändert: effort=low bleibt; Round-Trip = Yield-Lücke wie pause_turn (Keepalive deckt es).

## Teststrategie
Mock GitHub-create (`create_issue`) + Claude-Stream. Pytest unter `backend/tests/`.
Fälle: propose (sanitisiert/pending/kein Write), confirm mit/ohne pending (Write nur bei pending),
cancel, Live-Gating (Tool nicht in Liste), fail-closed bei create_issue→None,
sanitize gegen Marker-Injection, Round-Trip-Loop (tool_use→tool_result→end_turn), max_tokens fail-closed.

## Abweichungen vom Architektur-Doc
- `KNOWN_PROJECT_SLUGS` enthält `"uebergreifend"` BEREITS → enum = `sorted(KNOWN_PROJECT_SLUGS)` (kein erneutes Anhängen, deterministisch sortiert für stabiles Schema/Prompt-Caching).
- `github_context`-Variable bleibt (default None im PromptBuilder), wird in Phase A fest auf `None` gesetzt statt die Plumbing-Signatur zu entfernen (geringste Churn, spätere Phasen können sie wiederverwenden).
- `generate()` NICHT um tool_executor-Round-Trip erweitert: der /chat-Pfad nutzt nur `stream()`; dokumentiert im Docstring.

## Test-Output + Commit-Hash

- **Neue Phase-A-Tests:** `tests/test_tooluse_phaseA.py` — **19/19 passed** (2026-11-08 10:52 UTC).
- **Betroffene/angepasste Bestandstests:** `test_claude_client.py` + `test_github_inbox_chat.py`
  (neu auf Tool-Wiring) + `test_github_inbox_service.py` → zusammen **183/183 passed**.
- **Invarianz-kritische Bestandstests grün:** `test_smarthome_chat.py`,
  `test_live_guard_home_control.py`, `test_marker_single_source.py` (im 268/268-Lauf).
- **Unit-Gesamtsuite (ohne `tests/functional/` Live-Tests):** 1606 passed, 3 skipped,
  **12 pre-existing Failures** (graphiti/neo4j, mem0, twitch date-based, stream-live-watcher)
  — verifiziert identisch auf Basis `634740e` OHNE meine Änderung → nicht von Phase A verursacht.
- **Commit-Hash:** siehe `git log` auf `feat/donna-tooluse-phaseA` (unten im Report).

---

## Korrektur-Runde (Review-Rat 2/6 → Fixes B1–B13)

| Fix | Status | Umsetzung (Datei) |
|---|---|---|
| B1 maxLength → 400 | erledigt | `maxLength` aus title-Schema raus, in description; neuer Fallback in `claude_client.stream` (400 nicht-web-search + Client-Tools → 1× ohne Inbox-Tools, Web-Tools bleiben); Schema-Regression-Test |
| B2 Confirm-Bypass | erledigt | request-scoped `_local["proposed"]` in Executor; confirm im selben Request → is_error, kein take_pending/Write |
| B3 sanitize umgehbar | erledigt | `sanitize_issue_field`: Steuer-+Zero-Width-Zeichen ZUERST, dann Marker-Regex, Fixpunkt-Schleife; `_MARKERISH_RE` um navigate/tesla_navigate/play_music/note ergänzt |
| B4 verwaistes Pending | erledigt | Pending trägt `token`; `drop_pending_if_token`; chat.py erfasst Start-Token, finally räumt Orphan token-genau weg (1-Turn-Lebensdauer) |
| B5 leer/Non-String | erledigt | Executor: Non-String → is_error; title+body nach Sanitize leer → is_error, kein Pending |
| B6 leere session_id | erledigt | Executor: leere session_id → is_error |
| B7 eager_input_streaming | erledigt | aus allen 3 Tools entfernt (strict bleibt) |
| B8 required:[] | erledigt | confirm + cancel: `"required": []` ergänzt |
| B9 Test wiederhergestellt | erledigt | `test_issue_marker_stripped_from_parse_actions` zurück in test_github_inbox_chat.py |
| B10 Runden-Trenner | erledigt | `claude_client`: " " zwischen tool_use-Runden (nur wenn nötig); NICHT bei pause_turn (Satzfluss) |
| B11 ehrlicher Fehler nach Write | erledigt | Executor-`state{ran,wrote}`; chat.py ClaudeUnavailable-Pfad: wrote → „Aufgabe angelegt, Verbindung abgebrochen" |
| B12 Retry nur bei tool_rounds==0 | erledigt | web-search-disabled-Retry-Gate um `tool_rounds == 0` erweitert |
| B13 Web-Defense-in-depth | erledigt | `_SourceCollector.saw_web()`; Stream setzt `web_search_requests=1` bei Web-Block auch ohne usage-Zähler |

**Testergebnis Korrektur-Runde:** test_tooluse_phaseA + test_claude_client + test_github_inbox_chat +
test_github_inbox_service = **200/200 passed**; Gesamt-Unit-Suite (ohne functional): **1623 passed,
3 skipped, 12 pre-existing failures** (unverändert, verifiziert nicht von Phase A verursacht).
Timestamp: 2026-11-08.

---

## Abschluss-Touch-up (nach APPROVE — T1–T4)

| Fix | Status | Umsetzung |
|---|---|---|
| T1 Honesty Leer-Stream | erledigt | `chat.py`: Zweig `not _claude_chunks_sent and _inbox_tool_state["wrote"]` → ehrlicher „in deiner Inbox angelegt (#n)"-delta statt claude_empty-error; Executor-`state` trägt jetzt `number` |
| T2 Laufzeit-Regressionen | erledigt | +12 Tests: B1 Double-400-Terminierung, B1×web-search-disabled-Kette, B1 nur-Client-Tools (kein tools-Key), B10 kein Trenner bei pause_turn, B13 Mischfall kein Overcount, B4-Sequenz (orphan/confirmed/replaced), B2 cancel-then-confirm + [confirm,propose]-Reihenfolge, T1-Verdrahtung |
| T3 B13-Overcount | erledigt | synthetisches Web-Signal separat als `usage_out["_web_seen"]`, NIE auf `web_search_requests` addiert; nur gesetzt, wenn echter Zähler == 0 (sonst redundant) — Kosten-Metrik bleibt exakt |
| T4 Sanitize-Härtung | erledigt | `sanitize_issue_field`: NFKC-Normalisierung vorab + Entfernung der Kategorien Cc/Cf (Newline bleibt) zusätzlich zum Zero-Width-Set, Fixpunkt-Schleife + Cap unverändert |

**Testergebnis Touch-up:** betroffene Dateien **225/225 passed**; Gesamt-Unit-Suite (ohne functional):
**1635 passed, 3 skipped, 12 pre-existing failures** (unverändert). Timestamp: 2026-11-08.
