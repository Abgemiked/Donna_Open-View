# Rollout: Image-Pins + mem_limit (docker-compose.yml, 2026-11-24)

**NICHT ohne Mikes Go ausrollen.** Refs #195, #192.

## Aenderung
- Pins: qdrant `v1.17.1`, syncthing `2.0.16`, ntfy per Digest (Tag nachtragen). `api` (lokal gebaut) und `neo4j` unveraendert.
- mem_limit: ntfy 128m, qdrant 1g, syncthing 256m, api 3g.

## Vorab
- `docker compose config -q` (Syntaxcheck, muss ohne Ausgabe durchlaufen)
- Image-Vorab-Check: `docker image ls` und gezielt nur `docker pull qdrant/qdrant:v1.17.1` und `docker pull syncthing/syncthing:2.0.16` (kein Pull fuer api/neo4j)

## Reihenfolge (je Dienst einzeln)
1. ntfy
2. syncthing (Port 22000/21027; Sync kurz unterbrochen)
3. qdrant (Recreate unterbricht kurz die Vektorsuche)
4. api zuletzt (**Donna-Neustart!**)

```
docker compose up -d --no-deps <dienst>
```
Nie `down`, nie `--remove-orphans`, nie `-v`.

## Nachkontrolle
- `docker ps` (Container laufen, Image-Tags/Digests wie erwartet)
- Health: `docker inspect --format '{{.State.Health.Status}}' assistent-api assistent-ntfy`
- `docker stats --no-stream` (Verbrauch unter den Limits)

## Rollback
Alte `docker-compose.yml` zurueckspielen (`git checkout <alter-commit> -- docker-compose.yml`) und je Dienst gleiches `docker compose up -d --no-deps --pull never <dienst>`.
Alte Images: qdrant `sha256:94728574965d17c6485dd361aa3c0818b325b9016dac5ea6afec7b4b2700865f`, syncthing `sha256:4a961394ca471f4e48f31ad2cef50697e502c8799e2e98477a1c3844e0c5bc54`, ntfy `sha256:d8b3408c75d5ae78e03339af6103e7e6195e56573eea6f1ad34bb4dc4f44f340`.
