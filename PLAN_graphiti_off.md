# PLAN — Graphiti/Neo4j abschalten (Issue #57)
Stufe 2 (Compose + Health-Route). Mike-Entscheid 2026-11-11.
Befund: Neo4j 0 Knoten; Graphiti nicht funktionsfaehig (Ollama fehlt -> search "Connection error", Fallback LTM); Clients lesen neo4j_ready nicht.
- [ ] Backup /root/backups: neo4j_data+logs Tarball, docker-compose.yml, .env
- [ ] Repo: compose (depends_on raus, neo4j profiles:[graphiti]), health.py (neo4j_status=disabled), .env.example DONNA_GRAPHITI=false
- [ ] Review-Rat >=4/6
- [ ] Server: compose syncen (git pull/ff), .env DONNA_GRAPHITI=false, up -d api, stop neo4j, nginx reload
- [ ] Verifikation: /health, Chat, docker ps, free -m, restart-Test
Rollback: cp /root/backups/<ts>/docker-compose.yml + .env zurueck; docker compose --profile graphiti up -d neo4j; Volumes unveraendert (Tarball zusaetzlich).
