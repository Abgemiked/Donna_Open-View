"""
location_service.py — Wetter-Karte (wttr.in), Google Maps Deep Links,
                      Overpass/OSM Nearby-Suche für Donna

Kein API-Key nötig — wttr.in und Overpass sind kostenlos.
"""
from __future__ import annotations

import math
import re
import time
from dataclasses import dataclass, field, asdict

import httpx

from app.core.logger import get_logger

log = get_logger("service.location")

# DONNA-74: Koordinaten-Pattern — erkennt rohe GPS-Strings wie "YOUR_LAT,YOUR_LON"
# Wird genutzt um zu verhindern dass Koordinaten als Ortsname ins LLM-Prompt gelangen.
# Mindestens 4 Nachkommastellen (konsistent mit _COORD_PATTERN in prompt_builder.py).
_RAW_COORD_RE = re.compile(r'^-?\d{1,3}\.\d{4,}\s*,\s*-?\d{1,3}\.\d{4,}$')

# DONNA-74: In-Memory-Cache (lat/lon gerundet auf 2 Dezimalstellen → Ortsname).
# Key: "lat_2dp,lon_2dp"  Value: (lesbarer Ortsname, timestamp)
# Wird befüllt von chat.py nach erfolgreichem Reverse-Geocoding.
_location_name_cache: dict[str, tuple[str, float]] = {}
_CACHE_MAX_AGE_SEC = 3600  # Cache-Einträge nach 1 Stunde als veraltet behandeln


def _cache_key(lat: float, lon: float) -> str:
    """Erzeugt einen stabilen Cache-Key aus gerundeten Koordinaten."""
    return f"{lat:.2f},{lon:.2f}"


def set_location_cache(lat: float, lon: float, city_name: str) -> None:
    """DONNA-74: Speichert einen Ortsnamen für ein Koordinaten-Paar im Cache.

    Wird von chat.py nach erfolgreichem Reverse-Geocoding aufgerufen,
    damit get_weather_card() bei Nominatim-Ausfall trotzdem einen lesbaren
    Namen zurückgeben kann.
    """
    if city_name:
        _location_name_cache[_cache_key(lat, lon)] = (city_name, time.time())


def _looks_like_coords(s: str) -> bool:
    """True wenn der String ein rohes Koordinaten-Paar ist (z.B. 'YOUR_LAT,YOUR_LON')."""
    return bool(_RAW_COORD_RE.match(s.strip()))


# wttr.in Wetter-Code → Emoji
_CONDITION_ICONS: dict[int, str] = {
    113: "☀️", 116: "⛅", 119: "☁️", 122: "☁️",
    143: "🌫️", 176: "🌦️", 179: "🌨️", 182: "🌨️", 185: "🌨️",
    200: "⛈️", 227: "❄️", 230: "❄️",
    248: "🌫️", 260: "🌫️", 263: "🌦️", 266: "🌦️",
    281: "🌨️", 284: "🌨️", 293: "🌦️", 296: "🌦️",
    299: "🌧️", 302: "🌧️", 305: "🌧️", 308: "🌧️",
    311: "🌨️", 314: "🌨️", 317: "🌨️", 320: "❄️",
    323: "❄️", 326: "❄️", 329: "❄️", 332: "❄️",
    335: "❄️", 338: "❄️", 350: "🌨️", 353: "🌦️",
    356: "🌧️", 359: "🌧️", 362: "🌨️", 365: "🌨️",
    368: "❄️", 371: "❄️", 374: "🌨️", 377: "🌨️",
    386: "⛈️", 389: "⛈️", 392: "⛈️", 395: "⛈️",
}


@dataclass
class WeatherCardData:
    location: str
    temp_c: int
    feels_like_c: int
    temp_min: int
    temp_max: int
    condition: str
    condition_icon: str
    humidity: int
    wind_kmh: int
    # Stündliche Vorschau: [{time: "12 Uhr", temp_c: 18, icon: "☀️", precip_pct: 5}]
    hourly: list[dict] = field(default_factory=list)

    def as_dict(self) -> dict:
        return asdict(self)

    def generate_summary(self) -> str:
        """Natürliche, kompakte Wetterzusammenfassung für TTS — ein flüssiger Satz.

        Beispiel-Ausgaben:
          "In YOUR_HOME_CITY sind es gerade 11 Grad, leicht bewölkt, den restlichen
           Tag soll es 11-12 Grad haben, Abends auch 11 Grad."
          "In YOUR_HOME_CITY sind es gerade 7 Grad, leicht bewölkt, Mittags soll es
           15 Grad haben, Nachmittags wird es 20 Grad und Abends dann 11 Grad."
        """
        from datetime import datetime
        now_h = datetime.now().hour

        _FALLBACK_LOCATIONS = {"deinem Standort", "deinem aktuellen Standort"}
        preposition = "An" if self.location in _FALLBACK_LOCATIONS else "In"
        intro = f"{preposition} {self.location} sind es gerade {self.temp_c} Grad, {self.condition}"

        # Tagesabschnitte — Großschreibung für natürlicheres Vorlesen
        period_map = [
            (6,  "Früh"),
            (9,  "Vormittags"),
            (12, "Mittags"),
            (15, "Nachmittags"),
            (18, "Abends"),
            (21, "Nachts"),
        ]
        slots: list[tuple[str, int]] = []  # (label, temp_c)
        for target_h, label in period_map:
            if target_h <= now_h:
                continue
            entry = next(
                (h for h in self.hourly
                 if int(str(h["time"]).split()[0].split(":")[0]) >= target_h),
                None,
            )
            if entry:
                slots.append((label, int(round(float(entry["temp_c"])))))
            if len(slots) >= 3:
                break

        if not slots:
            return intro + "."

        temps = [t for _, t in slots]
        temp_range = max(temps) - min(temps)
        last_label, last_temp = slots[-1]

        # Schmale Spanne (≤3 Grad) → "den restlichen Tag soll es X-Y Grad haben"
        if temp_range <= 3:
            t_min, t_max = min(temps), max(temps)
            group_part = (
                f"den restlichen Tag soll es {t_min} Grad haben"
                if t_min == t_max
                else f"den restlichen Tag soll es {t_min}-{t_max} Grad haben"
            )
            # Letzter Slot gleich aktuelle Temp → separat mit "auch" anhängen
            if last_temp == self.temp_c and len(slots) >= 2:
                return f"{intro}, {group_part}, {last_label} auch {last_temp} Grad."
            return f"{intro}, {group_part}."

        # Breite Spanne → Slots einzeln aufzählen mit natürlichen Verbindungen
        parts: list[str] = []
        for i, (label, temp) in enumerate(slots):
            if i == 0:
                parts.append(f"{label} soll es {temp} Grad haben")
            elif i == len(slots) - 1:
                suffix = "auch" if temp == self.temp_c else "dann"
                parts.append(f"und {label} {suffix} {temp} Grad")
            else:
                parts.append(f"{label} wird es {temp} Grad")

        if len(parts) == 1:
            return f"{intro}, {parts[0]}."
        # "A, B und C" — kein Komma vor "und"
        joined = ", ".join(parts[:-1]) + " " + parts[-1]
        return f"{intro}, {joined}."

    def as_prompt_text(self) -> str:
        """Kompakte Wetterdaten für den Gemini-Prompt — ausgeschriebene Einheiten."""
        _FALLBACK_LOCATIONS = {"deinem Standort", "deinem aktuellen Standort"}
        if self.location in _FALLBACK_LOCATIONS:
            location_label = f"an {self.location}"
        else:
            location_label = self.location
        return (
            f"[Aktuelle Wetterdaten für {location_label}]: "
            f"{self.temp_c} Grad (gefühlt {self.feels_like_c} Grad), "
            f"{self.condition} {self.condition_icon}, "
            f"Min {self.temp_min} Grad / Max {self.temp_max} Grad, "
            f"Luftfeuchtigkeit {self.humidity} Prozent, Wind {self.wind_kmh} km/h."
        )


async def get_weather_card(
    location: str,
    lat: float | None = None,
    lon: float | None = None,
) -> WeatherCardData | None:
    """
    Holt aktuelle Wetterdaten von wttr.in (kostenlos, kein API-Key).
    Gibt None zurück wenn der Dienst nicht erreichbar ist.

    Bei lat/lon werden Koordinaten direkt an wttr.in übergeben — nützlich wenn
    Nominatim keinen verwertbaren Ortsnamen liefert (z.B. Randlagen/Weiler).
    """
    # location_city wird direkt vom Geocoder übergeben — kein weiteres Splitting nötig
    city = location.strip()

    # DONNA-74: Sicherstellen dass kein roher Koordinaten-String als Ortsname verwendet wird.
    # Wenn die übergebene location wie "YOUR_LAT,YOUR_LON" aussieht oder ein generischer
    # Platzhalterwert ist → lesbaren Namen aus Cache holen oder Fallback verwenden.
    _COORD_FALLBACKS = {"Unbekannter Standort", "deinem aktuellen Standort", ""}
    if _looks_like_coords(city) or city in _COORD_FALLBACKS:
        # Cache prüfen (befüllt von chat.py nach erfolgreichem Reverse-Geocoding)
        if lat is not None and lon is not None:
            cached_entry = _location_name_cache.get(_cache_key(lat, lon))
            if cached_entry and time.time() - cached_entry[1] <= _CACHE_MAX_AGE_SEC:
                city = cached_entry[0]
                log.info("weather_card_city_from_cache", cached_name=city)
            else:
                city = "deinem Standort"
        else:
            city = "deinem Standort"

    # Koordinaten-URL hat Vorrang wenn explizit angegeben
    if lat is not None and lon is not None:
        url = f"https://wttr.in/{lat:.4f},{lon:.4f}?format=j1&lang=de"
    else:
        encoded = city.replace(" ", "+")
        url = f"https://wttr.in/{encoded}?format=j1&lang=de"
    try:
        async with httpx.AsyncClient(timeout=5.0) as client:
            resp = await client.get(
                url,
                headers={"User-Agent": "DonnaAssistant/1.0 (your-donna-instance.example.com)"},
            )
            if resp.status_code != 200:
                log.warning("wttr_in_error", status=resp.status_code, location=city)
                return None
            data = resp.json()
            cc = data["current_condition"][0]
            today = data["weather"][0]
            code = int(cc.get("weatherCode", 113))
            # Deutsch bevorzugen, Fallback auf Englisch
            lang_de = cc.get("lang_de", [])
            condition = (lang_de[0].get("value") if lang_de else None) or \
                        (cc.get("weatherDesc") or [{}])[0].get("value", "")
            # Stündliche Vorschau aus wttr.in (0, 300, 600, ..., 2100)
            hourly: list[dict] = []
            for h in today.get("hourly", []):
                t = str(h.get("time", "0")).zfill(4)
                hour = int(t[:-2]) if len(t) >= 2 else 0
                h_code = int(h.get("weatherCode", 113))
                hourly.append({
                    "time": f"{hour} Uhr",
                    "temp_c": int(h.get("tempC", 0)),
                    "icon": _CONDITION_ICONS.get(h_code, "🌡️"),
                    "precip_pct": int(h.get("chanceofrain", 0)),
                })
            card = WeatherCardData(
                location=city,
                temp_c=int(cc.get("temp_C", 0)),
                feels_like_c=int(cc.get("FeelsLikeC", 0)),
                temp_min=int(today.get("mintempC", 0)),
                temp_max=int(today.get("maxtempC", 0)),
                condition=condition,
                condition_icon=_CONDITION_ICONS.get(code, "🌡️"),
                humidity=int(cc.get("humidity", 0)),
                wind_kmh=int(cc.get("windspeedKmph", 0)),
                hourly=hourly,
            )
            log.info("weather_card_fetched", location=city, temp=card.temp_c)
            return card
    except Exception as e:  # noqa: BLE001
        log.warning("weather_card_failed", error=str(e), location="[redacted]")
        return None


# Häufige Suchbegriffe → sauberer Maps-Suchterm
_SEARCH_TERM_MAP: dict[str, str] = {
    "kino": "Kinos", "film": "Kinos", "movie": "Kinos",
    "döner": "Döner", "doener": "Döner",
    "restaurant": "Restaurants", "essen": "Restaurants",
    "pizza": "Pizza", "sushi": "Sushi",
    "café": "Café", "cafe": "Café", "kaffee": "Café",
    "tankstelle": "Tankstellen",
    "apotheke": "Apotheken",
    "supermarkt": "Supermärkte",
    "bäcker": "Bäckerei", "baecker": "Bäckerei",
    "bar": "Bars",
}


# ── Overpass / OSM Nearby-Suche ─────────────────────────────────────────────

# Keyword → OSM-Amenity-Tag für Overpass-Abfragen
_OVERPASS_TAG: dict[str, str] = {
    "kino": "amenity=cinema",
    "cinema": "amenity=cinema",
    "film": "amenity=cinema",
    "movie": "amenity=cinema",
    "restaurant": "amenity=restaurant",
    "essen": "amenity=restaurant",
    "döner": "amenity=fast_food",
    "doener": "amenity=fast_food",
    "kebab": "amenity=fast_food",
    "pizza": "amenity=restaurant",
    "sushi": "amenity=restaurant",
    "café": "amenity=cafe",
    "cafe": "amenity=cafe",
    "kaffee": "amenity=cafe",
    "tankstelle": "amenity=fuel",
    "apotheke": "amenity=pharmacy",
    "pharmacy": "amenity=pharmacy",
    "supermarkt": "shop=supermarket",
    "bäcker": "shop=bakery",
    "baecker": "shop=bakery",
    "bar": "amenity=bar",
    "lokal": "amenity=restaurant",
    "imbiss": "amenity=fast_food",
}


def _haversine_km(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    """Luftlinienentfernung in km zwischen zwei GPS-Punkten."""
    r = 6371.0
    dlat = math.radians(lat2 - lat1)
    dlon = math.radians(lon2 - lon1)
    a = math.sin(dlat / 2) ** 2 + math.cos(math.radians(lat1)) * math.cos(math.radians(lat2)) * math.sin(dlon / 2) ** 2
    return r * 2 * math.asin(math.sqrt(a))


async def get_nearby_places(
    query: str,
    lat: float,
    lon: float,
    radius_m: int = 50_000,
    max_results: int = 10,
) -> list[dict]:
    """
    Sucht reale Orte (Kinos, Restaurants, …) über OpenStreetMap Overpass API.
    Gibt eine Liste zurück: [{name, distance_km, address, lat, lon}]
    Kein API-Key benötigt.
    """
    lower = query.lower()
    osm_tag = next((tag for kw, tag in _OVERPASS_TAG.items() if kw in lower), None)
    if osm_tag is None:
        return []

    key, _, value = osm_tag.partition("=")
    overpass_query = (
        f'[out:json][timeout:10];'
        f'('
        f'  node["{key}"="{value}"](around:{radius_m},{lat},{lon});'
        f'  way["{key}"="{value}"](around:{radius_m},{lat},{lon});'
        f');'
        f'out center {max_results};'
    )
    url = "https://overpass-api.de/api/interpreter"
    try:
        async with httpx.AsyncClient(timeout=12.0) as client:
            resp = await client.post(
                url,
                data={"data": overpass_query},
                headers={"User-Agent": "DonnaAssistant/1.0 (your-donna-instance.example.com)"},
            )
            if resp.status_code != 200:
                log.warning("overpass_error", status=resp.status_code)
                return []
            elements = resp.json().get("elements", [])
            places = []
            for el in elements:
                tags = el.get("tags", {})
                name = tags.get("name") or tags.get("brand")
                if not name:
                    continue
                # Koordinaten: node = lat/lon direkt, way = center
                elat = el.get("lat") or (el.get("center") or {}).get("lat")
                elon = el.get("lon") or (el.get("center") or {}).get("lon")
                dist_km = round(_haversine_km(lat, lon, elat, elon), 1) if elat and elon else None
                # Adresse zusammenbauen
                addr_parts = [
                    tags.get("addr:street", ""),
                    tags.get("addr:housenumber", ""),
                    tags.get("addr:city", ""),
                ]
                address = " ".join(p for p in addr_parts if p).strip() or None
                places.append({
                    "name": name,
                    "distance_km": dist_km,
                    "address": address,
                    "lat": elat,
                    "lon": elon,
                    "phone": tags.get("phone") or tags.get("contact:phone"),
                    "website": tags.get("website") or tags.get("contact:website"),
                    "opening_hours": tags.get("opening_hours"),
                })
            # Nach Entfernung sortieren
            places.sort(key=lambda p: p["distance_km"] or 9999)
            log.info("overpass_places_found", count=len(places), query=query, tag=osm_tag)
            return places
    except Exception as e:  # noqa: BLE001
        log.warning("overpass_failed", error=str(e), query=query)
        return []


def format_places_for_prompt(places: list[dict], query_type: str) -> str:
    """Formatiert Overpass-Ergebnisse als Kontext-Block für den LLM-Prompt."""
    if not places:
        return f"[Overpass-Suche: Keine {query_type} in der Nähe gefunden]"
    lines = [f"[{query_type} in der Nähe — aus OpenStreetMap]:"]
    for p in places:
        dist = f" ({p['distance_km']} km)" if p["distance_km"] else ""
        addr = f" — {p['address']}" if p["address"] else ""
        oh = f" · Öffnungszeiten: {p['opening_hours']}" if p["opening_hours"] else ""
        web = f" · {p['website']}" if p["website"] else ""
        lines.append(f"• {p['name']}{dist}{addr}{oh}{web}")
    return "\n".join(lines)


async def forward_geocode(query: str, limit: int = 3) -> list[dict]:
    """
    Nominatim Forward-Geocoding: Suchbegriff → Liste von Treffern.
    Gibt max. `limit` Treffer zurueck: [{display_name, address, lat, lon}]
    Kein API-Key noetig — Nominatim ist kostenlos.
    Rate-Limit: 1 req/sec laut Nutzungsbedingungen (wird hier einmalig pro Aufruf eingehalten).
    """
    import httpx as _httpx  # noqa: PLC0415 (lokaler Import um zirkulaere Abhaengigkeit zu vermeiden)
    url = "https://nominatim.openstreetmap.org/search"
    try:
        async with _httpx.AsyncClient(timeout=8.0) as client:
            resp = await client.get(
                url,
                params={
                    "q": query,
                    "format": "json",
                    "limit": limit,
                    "addressdetails": 1,
                },
                headers={"User-Agent": "DonnaAssistant/1.0 (your-donna-instance.example.com)"},
            )
            if resp.status_code != 200:
                log.warning("nominatim_forward_geocode_error", status=resp.status_code, query=query)
                return []
            elements = resp.json()
            results = []
            for el in elements:
                addr = el.get("address", {})
                # Kurze lesbare Adresse zusammenbauen
                road = addr.get("road", "")
                housenr = addr.get("house_number", "")
                city = addr.get("city") or addr.get("town") or addr.get("village") or addr.get("county") or ""
                postcode = addr.get("postcode", "")
                short_addr = " ".join(p for p in [road, housenr, postcode, city] if p).strip()
                results.append({
                    "display_name": el.get("display_name", ""),
                    "address": short_addr or el.get("display_name", "")[:60],
                    "lat": float(el["lat"]),
                    "lon": float(el["lon"]),
                })
            log.info("nominatim_forward_geocode_ok", query=query, hits=len(results))
            return results
    except Exception as e:  # noqa: BLE001
        log.warning("nominatim_forward_geocode_failed", error=str(e), query=query)
        return []


def build_map_card(query: str, lat: float | None = None, lon: float | None = None) -> dict:
    """
    Erstellt einen Google Maps Deep Link für 'in der Nähe'-Suchen.
    Extrahiert den Hauptbegriff für eine saubere Maps-URL.
    Öffnet die Google Maps App direkt auf dem Android-Gerät.
    """
    lower = query.lower()
    clean_term = next(
        (term for kw, term in _SEARCH_TERM_MAP.items() if kw in lower),
        query,  # Fallback: Originalfrage
    )
    encoded = clean_term.replace(" ", "+")
    if lat is not None and lon is not None:
        maps_url = f"https://www.google.com/maps/search/{encoded}/@{lat:.5f},{lon:.5f},14z"
    else:
        maps_url = f"https://www.google.com/maps/search/{encoded}"
    return {"query": clean_term, "maps_url": maps_url, "lat": lat, "lon": lon}
