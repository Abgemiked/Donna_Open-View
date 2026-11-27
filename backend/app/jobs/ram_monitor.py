"""RAM monitor — alerts via ntfy when host memory used > threshold.

Scheduled every N minutes (default 5). Emits an alert at most once per
configurable cooldown to avoid flooding (default 30 min).
"""
from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from typing import Callable

import httpx
import psutil

from app.core.logger import get_logger

log = get_logger("ram_monitor")


@dataclass
class RamAlertState:
    last_alert_at: datetime | None = None


def _used_mb() -> int:
    vm = psutil.virtual_memory()
    return int(vm.used / (1024 * 1024))


async def check_ram(
    *,
    threshold_mb: int,
    ntfy_topic: str | None,
    ntfy_url: str = "https://ntfy.sh",
    cooldown_min: int = 30,
    state: RamAlertState | None = None,
    now_fn: Callable[[], datetime] | None = None,
) -> dict:
    """One RAM check. Returns status dict. Safe to call on a schedule."""
    now_fn = now_fn or (lambda: datetime.now(timezone.utc))
    state = state or RamAlertState()
    used = _used_mb()
    over = used > threshold_mb
    now = now_fn()

    result = {"used_mb": used, "threshold_mb": threshold_mb, "over_threshold": over}

    if not over:
        log.debug("ram_ok", used_mb=used, threshold_mb=threshold_mb)
        return result

    # Cooldown check
    if state.last_alert_at and (now - state.last_alert_at) < timedelta(minutes=cooldown_min):
        log.info("ram_alert_suppressed_cooldown", used_mb=used, threshold_mb=threshold_mb)
        result["alert_sent"] = False
        result["reason"] = "cooldown"
        return result

    # Fire alert
    if not ntfy_topic:
        log.warning(
            "ram_alert_no_ntfy_topic",
            used_mb=used,
            threshold_mb=threshold_mb,
            detail="Set NTFY_TOPIC to receive RAM alerts.",
        )
        result["alert_sent"] = False
        result["reason"] = "ntfy_topic_missing"
        return result

    title = f"CCX23 RAM kritisch: {used} MB"
    body = (
        f"Host RAM used={used} MB, threshold={threshold_mb} MB. "
        f"Upgrade auf CCX33 erwaegen wenn das oefter vorkommt."
    )
    try:
        async with httpx.AsyncClient(timeout=5.0) as client:
            r = await client.post(
                f"{ntfy_url.rstrip('/')}/{ntfy_topic}",
                content=body.encode("utf-8"),
                headers={
                    "Title": title,
                    "Priority": "high",
                    "Tags": "warning,server,ram",
                },
            )
            r.raise_for_status()
        state.last_alert_at = now
        log.warning("ram_alert_sent", used_mb=used, threshold_mb=threshold_mb)
        result["alert_sent"] = True
    except Exception as e:  # noqa: BLE001
        log.error("ram_alert_send_failed", error=str(e))
        result["alert_sent"] = False
        result["reason"] = f"send_failed: {e}"

    return result
