"""Seed brain_ltm (Qdrant) mit Projektkontext-Eintraegen.

Nutzt nomic-embed-text (schnell, 137MB) — KEIN qwen2.5:7b.
Direkte Qdrant-REST-Inserts ohne mem0-Overhead.

Stand: 2026-05-07 — alle 8 Projekte aus C:/Tools/Projektübersicht.md
"""
import asyncio, sys, json, uuid
sys.path.insert(0, '/app')

ENTRIES = [
    # --- donna-assistentin ---
    {
        "content": (
            "Donna (your-donna-instance.example.com) ist Mikes persoenliche KI-Assistentin. "
            "Funktion: Chat, Ideen-Erfassung, Erinnerungen (LTM/STM), Termine, Wecker, "
            "Proaktive Impulse, Obsidian-Brain-Sync, Twitch-Bot (Viewer-Interaktion — NICHT Content-Erstellung), "
            "Wake-Word-Erkennung, Piper-TTS, Neural Brain (lernt Mikes Denkweise). "
            "Stack: Python FastAPI, React Native Android, Qdrant, SQLite, Neo4j/Graphiti, ntfy."
        ),
        "category": "user_fact",
        "session_id": "project_seed_v2",
    },
    {
        "content": (
            "Donna tut NICHT: Social-Media-Posts erstellen oder verwalten (→ streampost). "
            "Donna tut NICHT: Twitch-Clips schneiden oder auf TikTok/YouTube/Instagram posten (→ streampost). "
            "Donna tut NICHT: Stream-Planung oder OBS-Steuerung (→ streamtool). "
            "Donna tut NICHT: Steuerberechnung oder Rechnungsstellung (→ steuern-crm). "
            "Donna hat NUR Lesezugriff auf steuern-crm-Daten, KEINEN Schreibzugriff. "
            "Wenn Mike eine Idee zu Donna nennt, soll Donna KEINE Streaming-Features vorschlagen."
        ),
        "category": "user_fact",
        "session_id": "project_seed_v2",
    },

    # --- streampost (Social-Media-Verwaltung) ---
    {
        "content": (
            "Streampost (streampost.example.com) ist Mikes Social-Media-Verwaltungs-Tool. "
            "Status: AKTIV in Entwicklung, LIVE deployed. "
            "Funktion: Automation von LinkedIn-Posts (Mike Your-Name, Business), "
            "TikTok-Clips, YouTube-Shorts, Instagram-Reels, Twitch-Highlights (Abgemiked, Creator). "
            "Social-Media-Verwaltung = streampost. KEIN anderes Projekt erledigt diese Aufgabe."
        ),
        "category": "user_fact",
        "session_id": "project_seed_v2",
    },

    # --- streamtool ---
    {
        "content": (
            "Streamtool (C:/Tools/Streamtool) ist Mikes Stream-Management-Tool fuer Twitch. "
            "Status: in Planung (noch nicht deployed). "
            "Funktion: Stream-Planung, Live-Produktion, OBS-Steuerung, Clip-Verwaltung. "
            "UNTERSCHIED zu streampost: streamtool = Werkzeug fuer die LIVE-Produktion, "
            "streampost = Nachverwertung/Publishing fertiger Inhalte auf Social Media."
        ),
        "category": "user_fact",
        "session_id": "project_seed_v2",
    },

    # --- steuern-crm ---
    {
        "content": (
            "Steuern-CRM (steuern.example.com) ist produktiv im Einsatz fuer Abgemiked Media und EWV Software GmbH. "
            "Funktion: EUeR-Berechnung nach Paragraph 19 UStG, Rechnungsstellung, Kundenverwaltung. "
            "GoBD-konform: Integer-Cent-Arithmetik, Storno statt Delete, keine Backdates. "
            "DSGVO: Anonymisierungsfilter Pflicht vor Gemini-Calls wenn CRM-Daten involviert. "
            "Stack: React+TS+Vite+Tailwind, Node.js+Express, PostgreSQL, Docker."
        ),
        "category": "user_fact",
        "session_id": "project_seed_v2",
    },

    # --- chat-tool ---
    {
        "content": (
            "Chat-Tool (chat.example.com) ist Mikes Chat-Moderations- und Verwaltungstool. "
            "Status: aktiv. Funktion: Twitch- und YouTube-Chat-Moderation, WebSocket-basiert. "
            "Hartes Isolations-Prinzip: Twitch-Daten und YouTube-Daten NIEMALS vermischen. "
            "DSGVO-Pflichten aktiv: Datenschutzerklaerung, Loeschworkflow, TIA fuer YouTube. "
            "Repo: github.com/your-github-user/chat-tool"
        ),
        "category": "user_fact",
        "session_id": "project_seed_v2",
    },

    # --- learning ---
    {
        "content": (
            "Learning (learning.example.com) ist Mikes persoenliche Lernplattform. "
            "Status: aktiv. Zweck: Vorbereitung auf AI Solutions Architect / Lead AI Engineering. "
            "Auth: Twitch OAuth 2.0. Burnout-Schutz ist aktiv (PFLICHT-Feature). "
            "Stack: React+TS+Vite+Tailwind, Python+FastAPI, PostgreSQL+pgvector, Ollama, Mistral Small EU-Cloud."
        ),
        "category": "user_fact",
        "session_id": "project_seed_v2",
    },

    # --- website-abgemiked ---
    {
        "content": (
            "website-abgemiked (example.com) ist Mikes persoenliche Website und Streaming-Praesenz. "
            "Status: in Entwicklung. "
            "Funktion: Stream-Schedule API (/api/schedule), Creator-Profil, oeffentliches Portfolio. "
            "SEPARAT von Donna — kein gemeinsames Backend."
        ),
        "category": "user_fact",
        "session_id": "project_seed_v2",
    },

    # --- ausschreibungsscouting ---
    {
        "content": (
            "Ausschreibungsscouting ist EWV Software GmbHs IT-Ausschreibungs-Scraper. "
            "Status: in Entwicklung. "
            "Funktion: Automatisches Scouting auf deutschen Vergabeplattformen (DTVP, Vergabe.NRW etc.) "
            "fuer Ausschreibungen in E-Government, Cloud, DevOps, KI. "
            "Gehoert zu EWV Software GmbH (NICHT zu Abgemiked Media, NICHT zu Donna)."
        ),
        "category": "user_fact",
        "session_id": "project_seed_v2",
    },

    # --- EWV Trennung ---
    {
        "content": (
            "EWV Software GmbH ist Mikes IT-Dienstleistungsunternehmen (5-10 Mitarbeiter, "
            "Schwerpunkte: Cloud, DevOps, KI, E-Government). "
            "EWV-Projekte (ausschreibungsscouting, Kundenprojekte) sind STRIKT SEPARAT von Abgemiked Media. "
            "EWV wird NIEMALS automatisch mit Donna, streampost, chat-tool, learning oder website verknuepft. "
            "Einzige Ausnahmen wo EWV bewusst mit drin ist: steuern-crm und ausschreibungsscouting."
        ),
        "category": "user_fact",
        "session_id": "project_seed_v2",
    },

    # --- Mike Profil ---
    {
        "content": (
            "Mike Your-Name (Abgemiked) hat ADHS — braucht friktionslose Tools, kurze Antworten, "
            "minimale Entscheidungsaufwaende. Primaergeraet: Samsung Galaxy S25 Ultra. "
            "Wohnt in YOUR_HOME_CITY, Bayern. Primaere Sprache mit Donna: Deutsch. "
            "Twitch-Kanal: abgemiked (Hearthstone, Gaming, Kreativ-Content). "
            "Hat viele gleichzeitige Projekte — Donna hilft den Ueberblick zu behalten "
            "ohne Projekte miteinander zu vermischen."
        ),
        "category": "user_fact",
        "session_id": "project_seed_v2",
    },

    # --- Projekt-Grenzen fuer Donnas Antwortverhalten ---
    {
        "content": (
            "FUER DONNAS ANTWORTVERHALTEN: Wenn Mike ueber eine Idee fuer ein Projekt spricht, "
            "soll Donna bei der Funktion dieses EINEN Projekts bleiben. "
            "Donna schlaegt KEINE Features aus anderen Projekten vor ausser Mike fragt explizit. "
            "Beispiel: Idee fuer Donna → KEINE Social-Media-Integration vorschlagen (= streampost). "
            "Beispiel: Idee fuer streampost → KEINE Donna-LTM-Integration vorschlagen. "
            "Projektgrenzen respektieren = wichtigstes Qualitaetsmerkmal fuer Mike."
        ),
        "category": "user_fact",
        "session_id": "project_seed_v2",
    },
]

async def embed_text(text: str) -> list[float]:
    """Embedding via nomic-embed-text (Ollama) — schnell, 768 dim."""
    import urllib.request
    payload = json.dumps({"model": "nomic-embed-text", "input": text}).encode()
    req = urllib.request.Request(
        "http://ollama:11434/api/embed",
        data=payload,
        headers={"Content-Type": "application/json"},
    )
    r = urllib.request.urlopen(req, timeout=30)
    result = json.loads(r.read())
    embeddings = result.get("embeddings", [])
    if embeddings:
        return embeddings[0]
    raise ValueError("No embedding returned")

def upsert_point(point_id: str, vector: list[float], payload: dict) -> bool:
    """Direkt in Qdrant brain_ltm einfuegen."""
    import urllib.request
    data = json.dumps({
        "points": [{"id": point_id, "vector": vector, "payload": payload}]
    }).encode()
    req = urllib.request.Request(
        "http://qdrant:6333/collections/brain_ltm/points?wait=true",
        data=data,
        headers={"Content-Type": "application/json"},
        method="PUT",
    )
    r = urllib.request.urlopen(req, timeout=10)
    result = json.loads(r.read())
    return result.get("status") == "ok"

async def main():
    ok = 0
    fail = 0
    for entry in ENTRIES:
        content = entry["content"]
        short = content[:65].replace("\n", " ")
        try:
            vector = await embed_text(content)
            point_id = str(uuid.uuid5(uuid.NAMESPACE_DNS, content[:100]))
            payload = {
                "content": content,
                "category": entry["category"],
                "session_id": entry["session_id"],
                "original_id": point_id,
            }
            success = upsert_point(point_id, vector, payload)
            if success:
                ok += 1
                print(f"OK: {short}...")
            else:
                fail += 1
                print(f"FAIL (upsert): {short}...")
        except Exception as e:
            fail += 1
            print(f"FAIL ({e}): {short}...")

    print(f"\nFertig: {ok} OK, {fail} FAIL")

asyncio.run(main())
