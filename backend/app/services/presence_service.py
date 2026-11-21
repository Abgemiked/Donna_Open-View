"""presence_service.py — DONNA-96/97: Geräte-Präsenz aus Tracking-Events inferieren.

Liest pc_heartbeat-Events aus TrackingService (SQLite) und leitet daraus ab:
- Welches Gerät aktiv ist (PC / Android / keins)
- Idle-Zustand (aktiv / abwesend / schläft)
- Geschätzte Aktivität (arbeitet / surft / spielt / idle)

write_presence_md() schreibt den Status als Markdown-Datei in den Vault
(presence.md im Vault-Root) — aufgerufen alle 5 Min via APScheduler.
"""
from __future__ import annotations

from datetime import datetime, timezone
from pathlib import Path

from app.core.logger import get_logger

log = get_logger("service.presence")


class PresenceService:
    """Inferiert Mikes Präsenz aus Heartbeat-Tracking-Events.

    Args:
        tracking_svc: TrackingService-Instanz (benötigt get_recent()).
        vault_path:   Pfad zum Vault-Root-Verzeichnis (für write_presence_md).
    """

    def __init__(self, tracking_svc, vault_path: str | None = None) -> None:
        self._tracking = tracking_svc
        self._vault_path = vault_path

    # ── Public API ─────────────────────────────────────────────────────────

    def get_presence_context(self) -> dict:
        """Inferiert Präsenz aus den letzten 60 Min Heartbeat-Events.

        Returns:
            {
                "active_device":       "pc" | "android" | "none",
                "idle_state":          "active" | "away" | "sleeping",
                "pc_active_app":       str | None,
                "android_screen_on":   bool | None,
                "estimated_activity":  "working" | "browsing" | "gaming" | "idle",
            }
        """
        try:
            events = self._tracking.get_recent(hours=1)
        except Exception as e:
            log.warning("presence_get_recent_failed", error=str(e))
            return self._unknown()

        now = datetime.now(timezone.utc)

        # Letzten Heartbeat pro Gerät finden
        latest: dict[str, dict] = {}
        for ev in events:
            if ev.get("type") != "pc_heartbeat":
                continue
            device: str | None = ev.get("device")
            if not device:
                continue
            ts_str: str | None = ev.get("ts")
            if not ts_str:
                continue
            try:
                ts = datetime.fromisoformat(ts_str.replace("Z", "+00:00"))
            except ValueError:
                continue
            if device not in latest:
                latest[device] = ev
            else:
                try:
                    existing_ts = datetime.fromisoformat(
                        latest[device]["ts"].replace("Z", "+00:00")
                    )
                    if ts > existing_ts:
                        latest[device] = ev
                except ValueError:
                    pass

        if not latest:
            return self._sleeping_if_night(now)

        # Aktivstes Gerät: jüngstes Heartbeat jünger als 10 Min
        active_device = "none"
        freshest_ev: dict | None = None
        freshest_age = float("inf")

        for device, ev in latest.items():
            try:
                ts = datetime.fromisoformat(ev["ts"].replace("Z", "+00:00"))
            except ValueError:
                continue
            age_sec = (now - ts).total_seconds()
            if age_sec < 600 and age_sec < freshest_age:
                freshest_age = age_sec
                freshest_ev = ev
                active_device = device

        if active_device == "none" or freshest_ev is None:
            return self._sleeping_if_night(now)

        # Idle-Zustand bestimmen
        idle_sec: int = int(freshest_ev.get("idle_sec") or 0)
        screen_on: bool | None = freshest_ev.get("screen_on")

        if active_device == "android":
            idle_state = "active" if screen_on else "sleeping"
        elif idle_sec < 120:
            idle_state = "active"
        elif idle_sec < 900:
            idle_state = "away"
        else:
            idle_state = "sleeping"

        pc_active_app: str | None = freshest_ev.get("active_app") if active_device == "pc" else None
        android_screen_on: bool | None = screen_on if active_device == "android" else None

        return {
            "active_device": active_device,
            "idle_state": idle_state,
            "pc_active_app": pc_active_app,
            "android_screen_on": android_screen_on,
            "estimated_activity": _estimate_activity(active_device, idle_state, pc_active_app),
        }

    def write_presence_md(self) -> None:
        """DONNA-97: Schreibt aktuellen Präsenz-Status als presence.md in den Vault-Root."""
        if not self._vault_path:
            return
        ctx = self.get_presence_context()
        now_str = datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M UTC")

        device_map = {"pc": "🖥️ PC", "android": "📱 Android", "none": "❌ Offline"}
        idle_map = {"active": "✅ Aktiv", "away": "☕ Abwesend", "sleeping": "💤 Inaktiv"}
        activity_map = {
            "working": "💼 Arbeitet",
            "browsing": "🌐 Surft",
            "gaming": "🎮 Spielt",
            "idle": "💤 Idle",
        }

        lines = [
            f"# Presence — {now_str}",
            "",
            f"- **Aktives Gerät:** {device_map.get(ctx['active_device'], ctx['active_device'])}",
            f"- **Status:** {idle_map.get(ctx['idle_state'], ctx['idle_state'])}",
            f"- **Aktivität:** {activity_map.get(ctx['estimated_activity'], ctx['estimated_activity'])}",
        ]
        if ctx.get("pc_active_app"):
            lines.append(f"- **App:** {ctx['pc_active_app']}")
        if ctx.get("android_screen_on") is not None:
            screen_str = "Ein" if ctx["android_screen_on"] else "Aus"
            lines.append(f"- **Android-Bildschirm:** {screen_str}")

        content = "\n".join(lines) + "\n"
        try:
            presence_path = Path(self._vault_path) / "presence.md"
            presence_path.parent.mkdir(parents=True, exist_ok=True)
            presence_path.write_text(content, encoding="utf-8")
            log.info("presence_md_written")
        except Exception as e:
            log.warning("presence_write_md_failed", error=str(e))

    # ── Helpers ────────────────────────────────────────────────────────────

    @staticmethod
    def _unknown() -> dict:
        return {
            "active_device": "none",
            "idle_state": "active",
            "pc_active_app": None,
            "android_screen_on": None,
            "estimated_activity": "idle",
        }

    @staticmethod
    def _sleeping_if_night(now: datetime) -> dict:
        """Kein Heartbeat in letzter Stunde: nachts (22-8 Uhr UTC) → schläft."""
        if now.hour >= 22 or now.hour < 8:
            return {
                "active_device": "none",
                "idle_state": "sleeping",
                "pc_active_app": None,
                "android_screen_on": None,
                "estimated_activity": "idle",
            }
        return PresenceService._unknown()


def _estimate_activity(device: str, idle_state: str, active_app: str | None) -> str:
    """Schätzt Aktivität aus Gerät, Idle-Status und aktivem Programm."""
    if idle_state in ("sleeping", "away"):
        return "idle"
    if active_app:
        app_lower = active_app.lower()
        if any(k in app_lower for k in ("code", "cursor", "pycharm", "idea", "visual studio", "vim", "nvim", "editor")):
            return "working"
        if any(k in app_lower for k in ("chrome", "firefox", "edge", "safari", "browser", "zen")):
            return "browsing"
        if any(k in app_lower for k in ("steam", "valorant", "game", "lol", "wow", "twitch")):
            return "gaming"
    if device == "android":
        return "browsing"  # Handy = Browsen/Social-Media
    return "working"  # Aktiver PC ohne bekannte App → Arbeiten als Default
