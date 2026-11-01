"""Tests for the RAM monitor (threshold, cooldown, missing topic)."""
from __future__ import annotations

from datetime import datetime, timedelta, timezone
from unittest.mock import patch

import pytest

from app.jobs.ram_monitor import RamAlertState, check_ram


class _FakeVM:
    def __init__(self, used_bytes: int):
        self.used = used_bytes


@pytest.mark.asyncio
async def test_under_threshold_no_alert():
    with patch("app.jobs.ram_monitor.psutil.virtual_memory", return_value=_FakeVM(1024 * 1024 * 1000)):
        r = await check_ram(threshold_mb=14000, ntfy_topic="t")
    assert r["over_threshold"] is False
    assert "alert_sent" not in r


@pytest.mark.asyncio
async def test_over_threshold_no_topic():
    with patch("app.jobs.ram_monitor.psutil.virtual_memory", return_value=_FakeVM(1024 * 1024 * 15000)):
        r = await check_ram(threshold_mb=14000, ntfy_topic=None)
    assert r["over_threshold"] is True
    assert r["alert_sent"] is False
    assert r["reason"] == "ntfy_topic_missing"


@pytest.mark.asyncio
async def test_cooldown_suppresses_alert():
    state = RamAlertState(last_alert_at=datetime.now(timezone.utc) - timedelta(minutes=5))
    with patch("app.jobs.ram_monitor.psutil.virtual_memory", return_value=_FakeVM(1024 * 1024 * 15000)):
        r = await check_ram(threshold_mb=14000, ntfy_topic="t", cooldown_min=30, state=state)
    assert r["alert_sent"] is False
    assert r["reason"] == "cooldown"
