import asyncio, sys
sys.path.insert(0, '/app')

PROJECTS = [
    {
        "name": "Projekt: donna-assistentin",
        "body": (
            "Mike Your-Name (Abgemiked) entwickelt Donna, eine persoenliche KI-Assistentin. "
            "Status: aktiv in Entwicklung (Session 37+). "
            "Stack: Python FastAPI Backend, React Native Android App, Your Cloud Server Server. "
            "Features: Chat (Mistral/Gemini), LTM (Qdrant), STM (SQLite), Graphiti Knowledge Graph (Neo4j), "
            "Piper-TTS, ntfy Push-Notifications, Twitch-Bot, Obsidian-Brain-Sync, Proaktive Impulse, Ideen-Erfassung. "
            "Deployed: your-donna-instance.example.com, Docker Compose. "
            "Linear: DONNA-Team (https://linear.app/your-workspace/team/DONNA). "
            "Kern-Vision: Neural Brain der Mikes Denkweise lernt — Ideen-Cluster, Arbeits- und Entscheidungsmuster, ADHS-gerechte Friktionslosigkeit."
        ),
        "group": "projects"
    },
    {
        "name": "Projekt: steuern-crm",
        "body": (
            "Steuer-Tool und CRM-Tool fuer Abgemiked Media und EWV Software GmbH. "
            "Status: produktiv im Einsatz. "
            "Stack: React+TS+Vite+Tailwind Frontend, Node.js+Express Backend, PostgreSQL, Docker. "
            "Features: EUeR-Berechnung (Paragraph 19 UStG), Rechnungsstellung, Kundenverwaltung, DSGVO-konform. "
            "Deployed: Hetzner, steuern.example.com. "
            "Linear: SteuernCRM-Team. "
            "Besonderheit: GoBD-konform, Integer-Cent-Arithmetik, keine Backdates, Storno statt Delete."
        ),
        "group": "projects"
    },
    {
        "name": "Projekt: website-abgemiked",
        "body": (
            "Persoenliche Website und Streaming-Praesenz fuer Mike Your-Name / Abgemiked Media. "
            "Status: in Entwicklung. URL: example.com. "
            "Enthaelt: Stream-Schedule API (/api/schedule), oeffentliches Profil, Creator-Praesenz. "
            "Linear: website-abgemiked-Team."
        ),
        "group": "projects"
    },
    {
        "name": "Projekt: streamtool",
        "body": (
            "Stream-Management-Tool fuer Twitch-Streaming von Abgemiked. "
            "Status: in Planung/Entwicklung. "
            "Zweck: Stream-Planung, Clip-Management, Social-Media-Publishing. "
            "Zielplattformen: TikTok, YouTube, Instagram, Twitch. "
            "Linear: streamtool-Team."
        ),
        "group": "projects"
    },
    {
        "name": "Projekt: ausschreibungsscouting",
        "body": (
            "Automatisiertes Scouting von IT-Ausschreibungen fuer EWV Software GmbH. "
            "Bereiche: E-Government, Cloud, DevOps, KI-Dienstleistungen. "
            "Status: in Entwicklung. "
            "Stack: Playwright-MCP-basierter Scraper, deutsche Vergabeplattformen (DTVP, Vergabe.NRW etc.). "
            "Zweck: Relevante Ausschreibungen automatisch finden und Mike/EWV benachrichtigen. "
            "Linear: ausschreibungsscouting-Team."
        ),
        "group": "projects"
    },
    {
        "name": "Offene Idee: Projektmanagement-Web-UI (2026-05-07)",
        "body": (
            "Mike hat folgende Idee geaeussert: "
            "Ich brauche eine Software mit einer Web UI die meine Projekte die ich bei Cloud gerade umsetze "
            "und extern plant und auch Informationen aus den lineartickets und aus den Umsetzungen mitbekommt "
            "damit ich strukturiert arbeiten kann. "
            "Kern: Zentrales Dashboard das alle Projekte (donna, steuern-crm, website, streamtool, ausschreibungsscouting) "
            "plus Linear-Ticket-Status plus Deploy-Status vereint. "
            "Status: Idee, noch kein Projekt angelegt. "
            "Tags: projektmanagement, dashboard, linear-integration, web-ui, strukturierung, cloud-projekte."
        ),
        "group": "ideas"
    },
    {
        "name": "Mike Your-Name — Persoenlichkeit und Arbeitsweise",
        "body": (
            "Mike Your-Name ist Kleinunternehmer (Abgemiked Media, Paragraph 19 UStG), Twitch-Streamer "
            "und Geschaeftsfuehrer von EWV Software GmbH (5-10 Mitarbeiter, IT-Dienstleistungen: Cloud, DevOps, KI, E-Government). "
            "Hat ADHS — braucht friktionslose Tools, klare Strukturen, minimale Entscheidungsaufwaende. "
            "Wohnt in YOUR_HOME_CITY, Bayern. "
            "Primaergeraet: Samsung Galaxy S25 Ultra. "
            "Streaming: Twitch-Kanal abgemiked — Hearthstone, Gaming, Kreativ-Content. "
            "Social Media: LinkedIn (Mike Your-Name, Business), TikTok/YouTube/Instagram (Abgemiked, Creator). "
            "Server: Your Cloud Server your-server, Ubuntu 24.04. "
            "Hat viele Ideen gleichzeitig — braucht System um Ideen zu erfassen, vernetzen und weiterzuverfolgen. "
            "Spricht Donna auf Deutsch. Bevorzugt kurze klare Antworten ohne Umschweife."
        ),
        "group": "user_context"
    },
    {
        "name": "EWV Software GmbH — Unternehmenskontext",
        "body": (
            "EWV Software GmbH ist Mikes IT-Dienstleistungsunternehmen mit 5-10 Mitarbeitern. "
            "Schwerpunkte: Cloud (Azure, AWS), DevOps (Docker, Kubernetes, CI/CD), KI-Integration, E-Government-Loesungen. "
            "Kunden: oeffentliche Verwaltung, Mittelstand. "
            "Aktuell aktiv: Ausschreibungsscouting fuer neue IT-Auftraege. "
            "Trennung: Donna und example.com gehoeren zu Abgemiked Media (nicht EWV) — "
            "EWV wird NICHT automatisch mit Donna-Projekten verknuepft."
        ),
        "group": "user_context"
    },
]

async def main():
    from app.services.graphiti_service import GraphitiService
    svc = GraphitiService()
    print("enabled:", svc.enabled())

    if not svc.enabled():
        print("DONNA_GRAPHITI ist false — abgebrochen.")
        return

    ok = 0
    fail = 0
    for p in PROJECTS:
        result = await svc.add_episode_raw(
            name=p["name"],
            episode_body=p["body"],
            group_id=p["group"]
        )
        status = "OK" if result else "FAIL"
        if result:
            ok += 1
        else:
            fail += 1
        print(f"{status}: {p['name'][:70]}")

    print(f"\nFertig: {ok} OK, {fail} FAIL")

asyncio.run(main())
