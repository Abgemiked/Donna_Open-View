"""places_service.py — GPS-Gewohnheitserkennung aus Tracking-Daten.

Analysiert GPS-Punkte der letzten N Tage, clustert sie nach Nähe (150 m Radius)
und reverse-geocodet die Cluster-Zentren via Nominatim.
Ergebnis: häufig besuchte Orte (Restaurants, Läden, etc.) für Donna-Kontext.
"""
from __future__ import annotations

import json
import math
import sqlite3
import time
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any

import httpx

from app.core.logger import get_logger

log = get_logger("service.places")

_CLUSTER_RADIUS_M = 150      # Koordinaten innerhalb 150 m = gleicher Ort
_MIN_VISITS = 3              # Mindestbesuche um als "Gewohnheit" zu gelten
_GEOCODE_CACHE_TTL_S = 86400 # Geocoding-Ergebnis 24h cachen


def _haversine(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    """Distanz in Metern zwischen zwei GPS-Punkten."""
    R = 6_371_000
    φ1, φ2 = math.radians(lat1), math.radians(lat2)
    Δφ = math.radians(lat2 - lat1)
    Δλ = math.radians(lon2 - lon1)
    a = math.sin(Δφ / 2) ** 2 + math.cos(φ1) * math.cos(φ2) * math.sin(Δλ / 2) ** 2
    return R * 2 * math.atan2(math.sqrt(a), math.sqrt(1 - a))


class PlacesService:
    """Lernt häufige Orte aus GPS-Tracking-Daten."""

    def __init__(self, tracking_db_path: str) -> None:
        self._db_path = tracking_db_path
        # In-Memory-Cache für Geocoding-Ergebnisse (lat,lon → name)
        self._geocode_cache: dict[str, tuple[str, float]] = {}
        log.info("places_service_ready", db=tracking_db_path)

    # ── GPS-Cluster ───────────────────────────────────────────────────────

    def _load_locations(self, days: int) -> list[dict]:
        """Lädt GPS-Events der letzten N Tage aus der Tracking-DB."""
        since = (datetime.now(timezone.utc) - timedelta(days=days)).isoformat()
        try:
            conn = sqlite3.connect(self._db_path, check_same_thread=False)
            conn.row_factory = sqlite3.Row
            rows = conn.execute(
                "SELECT ts, payload FROM tracking_events "
                "WHERE type='location' AND ts >= ? ORDER BY ts",
                (since,),
            ).fetchall()
            conn.close()
        except Exception as e:  # noqa: BLE001
            log.warning("places_load_failed", error=str(e))
            return []

        points = []
        for row in rows:
            try:
                data = json.loads(row["payload"])
                lat = data.get("lat")
                lon = data.get("lon")
                if lat is not None and lon is not None:
                    points.append({"lat": float(lat), "lon": float(lon), "ts": row["ts"]})
            except Exception:
                continue
        return points

    def _cluster_points(self, points: list[dict]) -> list[dict]:
        """Gruppiert GPS-Punkte nach Nähe (greedy, kein ML nötig)."""
        clusters: list[dict[str, Any]] = []
        assigned = [False] * len(points)

        for i, pt in enumerate(points):
            if assigned[i]:
                continue
            # Neuen Cluster starten
            cluster_pts = [pt]
            assigned[i] = True
            for j, other in enumerate(points):
                if assigned[j]:
                    continue
                d = _haversine(pt["lat"], pt["lon"], other["lat"], other["lon"])
                if d <= _CLUSTER_RADIUS_M:
                    cluster_pts.append(other)
                    assigned[j] = True

            # Cluster-Zentrum = Mittelwert
            avg_lat = sum(p["lat"] for p in cluster_pts) / len(cluster_pts)
            avg_lon = sum(p["lon"] for p in cluster_pts) / len(cluster_pts)
            timestamps = sorted(p["ts"] for p in cluster_pts)
            clusters.append({
                "lat": avg_lat,
                "lon": avg_lon,
                "visits": len(cluster_pts),
                "first_seen": timestamps[0],
                "last_seen": timestamps[-1],
            })

        return sorted(clusters, key=lambda c: c["visits"], reverse=True)

    # ── Geocoding ─────────────────────────────────────────────────────────

    async def _reverse_geocode(self, lat: float, lon: float) -> str:
        """Nominatim Reverse-Geocoding mit Cache. Rate-Limit: 1 req/s."""
        cache_key = f"{lat:.4f},{lon:.4f}"
        if cache_key in self._geocode_cache:
            name, ts = self._geocode_cache[cache_key]
            if time.time() - ts < _GEOCODE_CACHE_TTL_S:
                return name

        try:
            await asyncio.sleep(1.1)  # Nominatim: max 1 req/sec
            async with httpx.AsyncClient(timeout=5.0) as client:
                resp = await client.get(
                    "https://nominatim.openstreetmap.org/reverse",
                    params={
                        "lat": lat, "lon": lon,
                        "format": "json", "zoom": 17,
                        "addressdetails": 1,
                    },
                    headers={"User-Agent": "DonnaAssistant/1.0 (your-donna-instance.example.com)"},
                )
                if resp.status_code != 200:
                    return f"{lat:.4f},{lon:.4f}"
                data = resp.json()
                addr = data.get("address", {})
                # Spezifischster Name: amenity > shop > building > road + housenumber
                name = (
                    addr.get("amenity")
                    or addr.get("shop")
                    or addr.get("building")
                    or addr.get("leisure")
                    or addr.get("tourism")
                )
                road = addr.get("road", "")
                housenr = addr.get("house_number", "")
                city = addr.get("city") or addr.get("town") or addr.get("village") or ""

                if name:
                    label = f"{name}, {road} {housenr}".strip(", ") if road else name
                elif road:
                    label = f"{road} {housenr}, {city}".strip(", ")
                else:
                    label = data.get("display_name", f"{lat:.4f},{lon:.4f}")[:80]

                self._geocode_cache[cache_key] = (label, time.time())
                return label
        except Exception as e:  # noqa: BLE001
            log.warning("places_geocode_failed", error=str(e))
            return f"{lat:.4f},{lon:.4f}"

    # ── Public API ────────────────────────────────────────────────────────

    async def analyze_places(self, days: int = 30, min_visits: int = _MIN_VISITS) -> list[dict]:
        """Analysiert GPS-Daten → Liste häufiger Orte mit Geocoding."""
        import asyncio  # noqa: PLC0415

        points = self._load_locations(days)
        if not points:
            log.info("places_no_data")
            return []

        clusters = self._cluster_points(points)
        frequent = [c for c in clusters if c["visits"] >= min_visits]

        log.info("places_clusters", total=len(clusters), frequent=len(frequent))

        # Geocoding für Top-20 (Rate-Limit beachten)
        results = []
        for cluster in frequent[:20]:
            name = await self._reverse_geocode(cluster["lat"], cluster["lon"])
            results.append({
                "name": name,
                "lat": round(cluster["lat"], 5),
                "lon": round(cluster["lon"], 5),
                "visits": cluster["visits"],
                "last_seen": cluster["last_seen"],
            })

        return results

    def get_frequent_places_sync(self, days: int = 30) -> list[dict]:
        """Synchrone Version (ohne Geocoding) für Prompt-Injection."""
        points = self._load_locations(days)
        if not points:
            return []
        clusters = self._cluster_points(points)
        frequent = [c for c in clusters if c["visits"] >= _MIN_VISITS]
        return [
            {
                "lat": round(c["lat"], 5),
                "lon": round(c["lon"], 5),
                "visits": c["visits"],
                "last_seen": c["last_seen"],
            }
            for c in frequent[:10]
        ]


import asyncio  # noqa: E402 — wird in analyze_places gebraucht
