# Tests — Donna Backend

## Funktionale Tests (`tests/functional/`)

### Voraussetzungen

1. **Backend läuft** — starte es lokal oder zeige auf den Remote-Server:

   ```bash
   # Lokal
   uvicorn app.main:app --host YOUR_SERVER_IP --port 8000

   # Oder Remote-Server als Ziel
   export DONNA_TEST_URL=https://your-donna-instance.example.com
   ```

2. **ADMIN_TOKEN gesetzt**:

   ```bash
   export ADMIN_TOKEN=dein_token_hier
   ```

### Tests ausführen

```bash
pytest tests/functional/ -v --tb=short
```

### pytest-Konfiguration

`pytest.ini` im Backend-Root enthält bereits:

```ini
[pytest]
asyncio_mode = auto
testpaths = tests
```

`asyncio_mode = auto` ist Pflicht — alle Fixtures und Tests in `functional/`
sind `async` und laufen sonst nicht.

---

## Live-Analyse am Android-Gerät (Metro Dev-Server)

Um während der Funktions-Tests das App-Verhalten in Echtzeit am Handy zu
beobachten, vier Terminals parallel öffnen:

**Terminal 1 — Metro Dev-Server starten:**

```bash
cd android && npx react-native start --port 8081
```

**Terminal 2 — ADB-Reverse-Tunnel einrichten** (USB-Verbindung vorausgesetzt):

```bash
adb reverse tcp:8081 tcp:8081
```

**Terminal 3 — React-Native-Logs streamen:**

```bash
adb logcat -s ReactNative:V ReactNativeJS:V
```

**Terminal 4 — Funktionstests starten:**

```bash
pytest tests/functional/ -v --tb=short
```

Alle API-Antworten und App-Reaktionen sind dann in Echtzeit in Terminal 3
sichtbar.
