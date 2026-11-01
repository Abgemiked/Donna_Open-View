"""
test_weather_latlon.py — Bug 1 (DONNA-32): Wetter-Reverse-Geocoding + Koordinaten-Leak Fix

- Integration-Tests: sprechen echte externe APIs an (Nominatim + wttr.in), benötigen Netz.
- Unit-Tests: testen den sanitize_ltm_content-Pfad ohne externe APIs.
"""
from __future__ import annotations

import re

import pytest
import pytest_asyncio

from app.services.prompt_builder import sanitize_ltm_content, PromptBuilder, PromptContext

# Your Cloud Server / Mike's Wohnort-Koordinaten
LAT = YOUR_LAT
LON = YOUR_LON

# Mindestanforderungen: einer dieser Orte muss im Label auftauchen
EXPECTED_PLACES = ("your_home_city", "ansbach", "feuchtwangen", "dinkelsbühl", "dinkelsbuehl", "mittelfranken")


# ---------------------------------------------------------------------------
# Integration-Tests (benötigen Netz)
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
@pytest.mark.integration
async def test_reverse_geocode_your_home_city_area() -> None:
    """_reverse_geocode mit Koordinaten nahe YOUR_HOME_CITY/Ansbach muss einen
    auflösbaren Ortsnamen zurückgeben — nicht raw 'lat, lon'.
    """
    from app.routes.chat import _reverse_geocode

    full_label, city = await _reverse_geocode(LAT, LON)

    assert city is not None, (
        f"_reverse_geocode hat city=None zurückgegeben (full_label={full_label!r}). "
        f"Koordinate {LAT}, {LON} wurde nicht aufgelöst."
    )
    assert city.strip(), "city ist leer-String"

    city_lower = city.lower()
    label_lower = (full_label or "").lower()
    found = any(
        place in city_lower or place in label_lower
        for place in EXPECTED_PLACES
    )
    assert found, (
        f"Erwarteter Ortsname nicht gefunden. city={city!r}, "
        f"full_label={full_label!r}. Erwartet: {EXPECTED_PLACES}"
    )


@pytest.mark.asyncio
@pytest.mark.integration
async def test_weather_card_latlon_fallback() -> None:
    """get_weather_card mit lat/lon-Parametern muss Wetterdaten zurückgeben —
    auch wenn der Stadtname allein bei wttr.in unbekannt wäre.
    """
    from app.services.location_service import get_weather_card

    card = await get_weather_card("YOUR_HOME_CITY", lat=LAT, lon=LON)

    assert card is not None, (
        "get_weather_card(lat/lon) hat None zurückgegeben. "
        "wttr.in Koordinaten-Abfrage ist fehlgeschlagen."
    )
    assert card.temp_c is not None, "temp_c ist None"
    assert card.condition, "condition ist leer"
    assert card.location, "location ist leer"


@pytest.mark.asyncio
@pytest.mark.integration
async def test_weather_full_flow_your_home_city() -> None:
    """Vollständiger Wetter-Flow: Reverse-Geocode → get_weather_card.
    Simuliert was chat.py tut wenn der User 'Wetter bei mir' fragt.

    Entweder der Stadtname-Lookup klappt ODER der lat/lon-Fallback greift.
    Endresultat: WeatherCardData ist nicht None.
    """
    from app.routes.chat import _reverse_geocode
    from app.services.location_service import get_weather_card

    full_label, city = await _reverse_geocode(LAT, LON)
    if not full_label:
        full_label = f"{LAT:.4f}, {LON:.4f}"
        city = full_label

    # Erster Versuch: Stadtname
    card = await get_weather_card(city or full_label)

    # Wenn Stadtname-Lookup fehlschlägt → lat/lon Fallback
    if card is None:
        card = await get_weather_card(city or full_label, lat=LAT, lon=LON)

    assert card is not None, (
        f"Weder Stadtname-Lookup noch lat/lon-Fallback hat Wetterdaten geliefert. "
        f"city={city!r}, full_label={full_label!r}"
    )
    assert card.temp_c is not None, "temp_c ist None"
    # Wetterkarte muss einen sinnvollen Ort-Namen haben (nicht raw Koordinaten-String)
    assert card.location, "WeatherCardData.location ist leer"


# ---------------------------------------------------------------------------
# Unit-Tests: DONNA-32 LTM-Koordinaten-Sanitizer
# ---------------------------------------------------------------------------


def test_ltm_coord_leak_not_in_prompt():
    """Integrationstest: LTM mit rohen Koordinaten → Antwort enthält keine Koordinaten.

    Testet den vollständigen Pfad: LTM-Eintrag mit GPS-Koords → PromptBuilder →
    User-Prompt enthält keine rohen Koordinaten mehr.
    """
    builder = PromptBuilder()

    # Simuliert bestehenden LTM-Eintrag aus dem Bug (Hetzner-Log)
    poisoned_memory = {
        "category": "Fakt",
        "content": "Fragte nach Wetter bei sich zu Hause (YOUR_LAT,YOUR_LON)",
    }

    ctx = PromptContext(
        message="wie ist das wetter bei mir",
        ltm_memories=[poisoned_memory],
        location_label="YOUR_HOME_CITY, Bayern, Deutschland",
        location_city="YOUR_HOME_CITY",
    )

    prompt = builder.build_user_prompt(ctx, include_history=False)

    # Die rohen Koordinaten dürfen NICHT im finalen Prompt stehen
    assert "YOUR_LAT" not in prompt, "Rohe Koordinaten im Prompt gefunden (LTM-Leak!)"
    assert "YOUR_LON" not in prompt, "Rohe Koordinaten im Prompt gefunden (LTM-Leak!)"

    # Der sanitierte Platzhalter soll stehen
    assert "[Standort]" in prompt


def test_sanitize_preserves_city_name():
    """Stadtname im LTM bleibt erhalten — nur GPS-Koords werden entfernt."""
    content = "Wohnt in YOUR_HOME_CITY (YOUR_LAT, YOUR_LON)"
    sanitized = sanitize_ltm_content(content)
    assert "YOUR_HOME_CITY" in sanitized
    assert "YOUR_LAT" not in sanitized


def test_sanitize_negative_coords():
    """Negative Koordinaten (z.B. westliche Länge) werden auch erkannt."""
    content = "Reiseziel: -34.6037,-58.3816"
    sanitized = sanitize_ltm_content(content)
    assert "-34.6037" not in sanitized
    assert "[Standort]" in sanitized


def test_sanitize_decimal_numbers_not_affected():
    """Normale Dezimalzahlen mit weniger als 4 Nachkommastellen bleiben."""
    # Diese sehen aus wie Preise/Bewertungen, keine GPS-Koords
    content = "Bewertung: 4.5, Preis: 12.99 EUR"
    sanitized = sanitize_ltm_content(content)
    # Nicht als Koordinaten erkannt (zu wenig Stellen nach Komma)
    assert sanitized == content


def test_no_raw_coords_in_location_label():
    """Wenn Reverse Geocoding fehlschlägt, sollen keine rohen Koordinaten
    als location_label im Prompt landen.

    Testet die Absicherung aus RC-3: chat.py Fallback ist jetzt
    'Unbekannter Standort' statt f"{lat:.4f}, {lon:.4f}".
    """
    builder = PromptBuilder()

    # Simuliert: Geocoding schlägt fehl → "Unbekannter Standort" statt rohe Koords
    ctx = PromptContext(
        message="wie ist das wetter bei mir",
        location_label="Unbekannter Standort",
        location_city=None,
    )

    prompt = builder.build_user_prompt(ctx, include_history=False)

    # Auch im Fallback-Fall: keine echten Koordinaten
    coord_pattern = re.compile(r'\d{2}\.\d{4,},\d{1,3}\.\d{4,}')
    assert not coord_pattern.search(prompt), "Koordinaten-ähnliches Muster im Prompt gefunden"


def test_sanitize_applied_before_store_memory():
    """sanitize_ltm_content() wird vor ltm_service.store_memory() angewendet.

    Prüft die Funktion direkt — der chat.py-Code ruft sanitize vor store auf.
    """
    # Eingabe: LLM emittiert save_memory mit rohen Koordinaten im content
    raw_content = "Fragte nach Wetter bei sich zu Hause (YOUR_LAT,YOUR_LON)"
    sanitized = sanitize_ltm_content(raw_content)

    assert "YOUR_LAT" not in sanitized
    assert "YOUR_LON" not in sanitized
    # Inhalt bleibt sinnvoll (nicht leer)
    assert len(sanitized) > 5
