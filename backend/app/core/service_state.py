"""Shared in-memory service state — set by lifespan, read by admin toggle routes.

DONNA-199: Admin Service Toggle-Endpoints brauchen Zugriff auf den Scheduler und
die Twitch-Enabled-Flags. Da der Scheduler in der lifespan()-Closure lebt und
Route-Handler keine Closure-Variablen haben, wird er hier als Modul-Global
gespeichert (set by lifespan, read by admin_service routes).

State ist rein in-memory — kein Persistieren über Restarts hinaus.
"""
from __future__ import annotations

import time
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from apscheduler.schedulers.asyncio import AsyncIOScheduler

APP_START_TIME: float = time.time()
"""Unix-Timestamp beim Modul-Load (Proxy für App-Startup-Zeit)."""

scheduler: "AsyncIOScheduler | None" = None
"""APScheduler-Instanz — wird von lifespan() nach Erstellung gesetzt."""

DONNA_TWITCH_ENABLED: bool = True
"""In-memory Toggle für den Twitch-Service.
Toggled via POST /admin/service/twitch/enable|disable."""
