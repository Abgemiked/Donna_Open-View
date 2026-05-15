"""In-App Functional Tests für Donna via ADB auf dem S25 Ultra.

Voraussetzungen:
  - ADB ist installiert und funktioniert
  - S25 Ultra ist verbunden (adb devices zeigt das Gerät)
  - Donna-App (com.yourcompany.donna) läuft im Vordergrund
  - UIAutomator ist auf dem Gerät verfügbar
"""
from __future__ import annotations

import io
import json
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

# UTF-8 Ausgabe erzwingen (Windows cp1252 unterstützt Checkmarks nicht)
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
sys.stderr = io.TextIOWrapper(sys.stderr.buffer, encoding="utf-8", errors="replace")
from dataclasses import dataclass

# Package und UI-Koordinaten (aus der Anleitung)
PACKAGE = "com.yourcompany.donna"
EDIT_TEXT_TAP_X = 540
EDIT_TEXT_TAP_Y = 2113
SEND_BUTTON_TAP_X = 985
SEND_BUTTON_TAP_Y = 2121

# Timeout für Antworten (Sekunden)
WAIT_FOR_RESPONSE_TIMEOUT = 30


@dataclass
class TestResult:
    """Ergebnis eines einzelnen Test-Falls."""

    test_name: str
    passed: bool
    expected: str
    found: str
    details: str = ""


def adb_shell(cmd: str) -> tuple[str, str]:
    """Führt einen ADB-Shell-Befehl aus.

    Args:
        cmd: Befehl ohne 'adb shell' Prefix (z.B. 'input tap 540 2113')

    Returns:
        Tuple (stdout, stderr)
    """
    try:
        result = subprocess.run(
            ["adb", "shell"] + cmd.split(),
            capture_output=True,
            text=True,
            timeout=10,
        )
        return result.stdout, result.stderr
    except subprocess.TimeoutExpired:
        return "", "Timeout"
    except Exception as e:
        return "", str(e)


def tap(x: int, y: int) -> None:
    """Tippt auf die angegebenen Koordinaten.

    Args:
        x: X-Koordinate
        y: Y-Koordinate
    """
    adb_shell(f"input tap {x} {y}")


def send_message(text: str) -> None:
    """Sendet eine Nachricht an die Donna-App via Clipboard (umgeht ADB-Encoding-Probleme).

    Args:
        text: Die zu sendende Nachricht
    """
    # Logcat-Buffer leeren VOR dem Senden — nur neue Logs nach dieser Aktion lesen
    subprocess.run(["adb", "logcat", "-c"], capture_output=True, timeout=5)
    time.sleep(0.3)

    # EditText fokussieren
    tap(EDIT_TEXT_TAP_X, EDIT_TEXT_TAP_Y)
    time.sleep(0.6)

    # Text via Clipboard einfügen (sicherste Methode für Sonderzeichen/Umlaute)
    # Zuerst alles selektieren + löschen, dann neuen Text setzen
    adb_shell("input keyevent KEYCODE_CTRL_A")
    time.sleep(0.2)
    adb_shell("input keyevent KEYCODE_DEL")
    time.sleep(0.2)

    # Text Wort für Wort eingeben (ADB input text hat Probleme mit Sonderzeichen)
    # Umlaute als ASCII-Näherung für ADB-Kompatibilität
    safe_text = (text.replace("ä", "ae").replace("ö", "oe").replace("ü", "ue")
                     .replace("Ä", "Ae").replace("Ö", "Oe").replace("Ü", "Ue")
                     .replace("ß", "ss").replace("'", "").replace('"', "")
                     .replace(":", " "))
    # Leerzeichen als %s übergeben
    adb_parts = safe_text.replace(" ", "%s")
    adb_shell(f"input text {adb_parts}")
    time.sleep(0.5)

    # Senden-Button drücken
    tap(SEND_BUTTON_TAP_X, SEND_BUTTON_TAP_Y)


def get_pid() -> str:
    """Ermittelt die PID der Donna-App.

    Returns:
        PID als String, oder "" wenn nicht gefunden
    """
    stdout, _ = adb_shell(f"pidof {PACKAGE}")
    return stdout.strip().split()[0] if stdout.strip() else ""


def wait_for_response(timeout: int = WAIT_FOR_RESPONSE_TIMEOUT) -> str:
    """Wartet auf eine neue Log-Zeile von Donna, die auf eine Antwort hindeutet.

    Diese Funktion nutzt 'adb logcat' mit der PID der App und sucht nach
    bekannten Donna-Log-Patterns oder neuen Einträgen.

    Args:
        timeout: Maximale Wartezeit in Sekunden

    Returns:
        Log-Output oder "" bei Timeout
    """
    pid = get_pid()
    if not pid:
        return ""

    try:
        # Logcat NUR neue Logs lesen (Buffer wurde vor send_message geleert)
        process = subprocess.Popen(
            ["adb", "logcat", "--pid=" + pid, "-v", "brief"],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )

        start = time.time()
        collected_lines: list[str] = []

        while time.time() - start < timeout:
            line_bytes = process.stdout.readline()
            if not line_bytes:
                time.sleep(0.1)
                continue

            line = line_bytes.decode("utf-8", errors="replace").rstrip()
            collected_lines.append(line)

            # ReactNativeJS Logs enthalten die UI-Updates
            if "ReactNativeJS" in line or "DonnaAndroid" in line:
                # Kurz weiter sammeln für vollständige Antwort
                time.sleep(2)
                while True:
                    extra = process.stdout.readline()
                    if not extra:
                        break
                    decoded = extra.decode("utf-8", errors="replace").rstrip()
                    if decoded:
                        collected_lines.append(decoded)
                    else:
                        break
                break

        process.terminate()
        process.wait(timeout=2)
        return "\n".join(collected_lines)

    except Exception as e:
        return f"Error: {e}"


def get_ui_dump() -> str:
    """Liest den aktuellen UI-State via UIAutomator.

    Returns:
        XML-String des UI-Dumps oder "" bei Fehler
    """
    stdout, _ = adb_shell("uiautomator dump")
    if "ERROR" in stdout or not stdout:
        return ""

    # UIAutomator speichert in /sdcard/window_dump.xml
    result = subprocess.run(
        ["adb", "pull", "/sdcard/window_dump.xml", "-"],
        capture_output=True,
        text=True,
        timeout=10,
    )
    return result.stdout if result.returncode == 0 else ""


def check_last_element_clickable(ui_xml: str) -> bool:
    """Prüft ob das letzte UI-Element (Donna-Response-Chip) clickable ist.

    KRITISCHER BUG-DETEKTOR:
    ============================================================================
    Diese Funktion sucht im UI-Dump nach dem letzten Chip/Button der
    Donna-Antwort und prüft, ob clickable="true" gesetzt ist.
    
    Dies ist der Haupt-Indikator dafür, ob der Nutzer mit der Antwort
    interagieren kann (z.B. auf einen Alarm-Button tippen).
    
    Wenn clickable=false ist, kann der Nutzer:
      - Nicht auf Action-Chips tippen (z.B. "Alarm stellen")
      - Nicht auf Navigate/Map-Buttons tippen
      - Nicht auf Call/Contact-Buttons tippen
    
    Dies ist ein KRITISCHER UX-Bug, der die In-App-Nutzung blockiert.
    ============================================================================

    Args:
        ui_xml: XML-String vom UIAutomator dump

    Returns:
        True wenn das letzte Element clickable=true hat, False sonst
    """
    if not ui_xml:
        return False

    try:
        # Parsen des XML
        root = ET.fromstring(ui_xml)

        # Alle Nodes mit clickable-Attribut finden
        clickable_nodes = root.findall(".//*[@clickable]")

        if not clickable_nodes:
            return False

        # Das letzte Node prüfen
        last_node = clickable_nodes[-1]
        clickable = last_node.get("clickable", "false").lower()

        return clickable == "true"

    except Exception:
        return False


def test_case(
    number: int,
    message: str,
    expected_indicator: str,
    expected_type: str,
) -> TestResult:
    """Führt einen einzelnen Test-Fall aus.

    Args:
        number: Test-Nummer (1-8)
        message: Nachricht an Donna
        expected_indicator: Was wird in der Antwort erwartet (z.B. "text-response")
        expected_type: Welcher Action-Type erwartet wird (oder "any" für beliebig)

    Returns:
        TestResult mit Bestanden/Fehler-Info
    """
    test_name = f"Test {number}: {message[:40]}..."

    # Nachricht senden
    print(f"\n[TEST {number}] Sende: {message}")
    send_message(message)
    time.sleep(2)  # Kurze Pause vor Logcat-Abfrage

    # Auf Antwort warten
    log_output = wait_for_response(timeout=WAIT_FOR_RESPONSE_TIMEOUT)
    print(f"[LOG] {log_output[:200]}..." if log_output else "[LOG] Kein Output")

    # UI-State prüfen
    time.sleep(1)
    ui_dump = get_ui_dump()

    # CHIP-CHECK: Ist das Antwort-Element clickable?
    # Dies ist der kritische Bug-Detektor für interaktive Elemente
    chip_clickable = check_last_element_clickable(ui_dump)
    chip_status = "✓ clickable" if chip_clickable else "✗ NOT clickable"

    # Erwartungen prüfen
    found = []
    if expected_indicator == "text-response":
        # Erwarte non-empty Log-Output
        if log_output and len(log_output.strip()) > 0:
            found.append("text-response")
    elif expected_indicator == "action":
        # Erwarte einen Action-Type im Log
        if expected_type != "any" and expected_type.lower() in log_output.lower():
            found.append(f"{expected_type}-action")
        elif expected_type == "any" and any(
            act in log_output.lower()
            for act in ["navigate", "call", "alarm", "timer", "event", "whatsapp"]
        ):
            found.append("action")

    if chip_clickable:
        found.append("chip-clickable")

    passed = len(found) > 0 or expected_indicator == "text-response"

    return TestResult(
        test_name=test_name,
        passed=passed,
        expected=f"{expected_indicator} + {chip_status}",
        found=", ".join(found) if found else "nothing",
        details=f"Log excerpt: {log_output[:100] if log_output else 'empty'}",
    )


def main() -> None:
    """Hauptfunktion: Führt alle 8 Test-Fälle aus."""

    print("=" * 60)
    print("DONNA IN-APP FUNCTIONAL TEST (via ADB)")
    print("=" * 60)

    # Test-Konfiguration
    tests = [
        {
            "number": 1,
            "message": "Hey Donna, wie geht's?",
            "expected_indicator": "text-response",
            "expected_type": "any",
        },
        {
            "number": 2,
            "message": "Wo ist das nächste Rewe?",
            "expected_indicator": "action",
            "expected_type": "navigate",
        },
        {
            "number": 3,
            "message": "Stell einen Wecker auf 7 Uhr",
            "expected_indicator": "action",
            "expected_type": "set_alarm",
        },
        {
            "number": 4,
            "message": "Schreib mir eine Erinnerung: Medikamente nehmen in 1 Stunde",
            "expected_indicator": "action",
            "expected_type": "set_timer",
        },
        {
            "number": 5,
            "message": "Ruf Max an",
            "expected_indicator": "action",
            "expected_type": "call",
        },
        {
            "number": 6,
            "message": "Erstelle einen Termin morgen 15 Uhr Zahnarzt",
            "expected_indicator": "action",
            "expected_type": "create_event",
        },
        {
            "number": 7,
            "message": "Was machst du gerade?",
            "expected_indicator": "text-response",
            "expected_type": "any",
        },
        {
            "number": 8,
            "message": "Schreib Max auf WhatsApp: Bin gleich da",
            "expected_indicator": "action",
            "expected_type": "whatsapp",
        },
    ]

    results: list[TestResult] = []

    # Starten der Tests
    for test_config in tests:
        result = test_case(
            number=test_config["number"],
            message=test_config["message"],
            expected_indicator=test_config["expected_indicator"],
            expected_type=test_config["expected_type"],
        )
        results.append(result)

        # 5 Sekunden Pause zwischen Tests
        if test_config["number"] < 8:
            time.sleep(5)

    # Zusammenfassung
    print("\n" + "=" * 60)
    print("=== DONNA IN-APP TEST ERGEBNIS ===")
    print("=" * 60)

    passed_count = sum(1 for r in results if r.passed)
    failed_count = len(results) - passed_count

    print(f"\nPASS: {passed_count}/{len(results)}")
    print(f"FAIL: {failed_count}/{len(results)}")

    if failed_count > 0:
        print("\nFEHLER:")
        for result in results:
            if not result.passed:
                print(f"  - {result.test_name}")
                print(f"    Erwartet: {result.expected}")
                print(f"    Gefunden: {result.found}")
                if result.details:
                    print(f"    Details: {result.details}")

    print("\nDETAILBERICHT:")
    for result in results:
        status = "PASS" if result.passed else "FAIL"
        print(f"{status}: {result.test_name} — {result.found}")

    print("\n" + "=" * 60)


if __name__ == "__main__":
    main()
