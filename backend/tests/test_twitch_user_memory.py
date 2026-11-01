"""Tests für DONNA-42 B: Per-User-Memory im Twitch-Chat."""
from __future__ import annotations

import os
import tempfile

import pytest

from app.services.twitch_user_memory import (
    TwitchUserMemory,
    extract_residence_location,
    extract_query_location,
    extract_name,
    extract_hobby,
)


@pytest.fixture
def tmp_db_path():
    with tempfile.NamedTemporaryFile(suffix=".db", delete=False) as f:
        path = f.name
    try:
        yield path
    finally:
        try:
            os.unlink(path)
        except OSError:
            pass


# ── Wohnort-Extraktion (explizit) ─────────────────────────────────────────────

class TestExtractResidence:
    def test_ich_wohne_in(self):
        assert extract_residence_location("Ich wohne in München") == "München"

    def test_ich_komme_aus(self):
        assert extract_residence_location("ich komme aus Hamburg") == "Hamburg"

    def test_ich_lebe_in(self):
        assert extract_residence_location("ich lebe in Berlin seit 5 Jahren") == "Berlin"

    def test_bei_mir_in(self):
        assert extract_residence_location("bei mir in Köln regnet es") == "Köln"

    def test_hier_in(self):
        assert extract_residence_location("hier in Frankfurt scheint die Sonne") == "Frankfurt"

    def test_bindestrich_stadt(self):
        # Doppelnamige Städte
        assert extract_residence_location("ich wohne in Saarbrücken") == "Saarbrücken"

    def test_nicht_extraktion_ohne_wohnort_satz(self):
        # Keine Wohnort-Aussage → None
        assert extract_residence_location("wie ist das wetter in München") is None

    def test_nicht_extraktion_bei_deutschland(self):
        # "Deutschland" ist kein konkreter Wohnort
        assert extract_residence_location("ich wohne in Deutschland") is None

    def test_no_match(self):
        assert extract_residence_location("Hallo Donna") is None


# ── Wetter-Frage-Extraktion ───────────────────────────────────────────────────

class TestExtractQueryLocation:
    def test_wetter_in_stadt(self):
        assert extract_query_location("wie ist das wetter in München") == "München"

    def test_temperatur_in_stadt(self):
        assert extract_query_location("temperatur in Berlin gerade?") == "Berlin"

    def test_kein_wetter_kein_match(self):
        # Keine Wetter-Frage → None (auch wenn Ort genannt wird)
        assert extract_query_location("ich wohne in München") is None

    def test_wetter_ohne_stadt_kein_match(self):
        assert extract_query_location("wie ist das wetter") is None


# ── TwitchUserMemory CRUD ─────────────────────────────────────────────────────

class TestTwitchUserMemoryCRUD:
    def test_get_unknown_user_returns_none(self, tmp_db_path):
        mem = TwitchUserMemory(db_path=tmp_db_path)
        assert mem.get("nobody") is None

    def test_touch_creates_entry(self, tmp_db_path):
        mem = TwitchUserMemory(db_path=tmp_db_path)
        mem.touch("Arcsore")
        result = mem.get("arcsore")
        assert result is not None
        assert result["user_login"] == "arcsore"
        assert result["location"] is None
        assert result["first_seen"] is not None
        assert result["last_seen"] is not None

    def test_set_location_persists(self, tmp_db_path):
        mem = TwitchUserMemory(db_path=tmp_db_path)
        mem.set_location("arcsore", "München")
        result = mem.get("arcsore")
        assert result["location"] == "München"
        assert result["location_updated_at"] is not None

    def test_set_location_overwrites(self, tmp_db_path):
        """User zieht um → neuer Wohnort überschreibt alten."""
        mem = TwitchUserMemory(db_path=tmp_db_path)
        mem.set_location("arcsore", "München")
        mem.set_location("arcsore", "Hamburg")
        assert mem.get("arcsore")["location"] == "Hamburg"

    def test_user_login_case_insensitive(self, tmp_db_path):
        mem = TwitchUserMemory(db_path=tmp_db_path)
        mem.set_location("ArCsOrE", "Köln")
        # Lookup mit anderer Case
        assert mem.get("arcsore")["location"] == "Köln"
        assert mem.get("ARCSORE")["location"] == "Köln"

    def test_set_fact(self, tmp_db_path):
        mem = TwitchUserMemory(db_path=tmp_db_path)
        mem.set_fact("arcsore", "lieblingsspiel", "Valorant")
        result = mem.get("arcsore")
        assert result["facts"]["lieblingsspiel"] == "Valorant"

    def test_facts_persist_independently_from_location(self, tmp_db_path):
        mem = TwitchUserMemory(db_path=tmp_db_path)
        mem.set_location("arcsore", "Berlin")
        mem.set_fact("arcsore", "lieblingsspiel", "Hearthstone")
        result = mem.get("arcsore")
        assert result["location"] == "Berlin"
        assert result["facts"]["lieblingsspiel"] == "Hearthstone"

    def test_context_string_with_location(self, tmp_db_path):
        mem = TwitchUserMemory(db_path=tmp_db_path)
        mem.set_location("arcsore", "München")
        ctx = mem.context_string("arcsore")
        assert "arcsore" in ctx.lower()
        assert "München" in ctx
        assert ctx.startswith("[User-Kontext")

    def test_context_string_empty_for_unknown(self, tmp_db_path):
        mem = TwitchUserMemory(db_path=tmp_db_path)
        assert mem.context_string("ghost") == ""

    def test_context_string_empty_for_no_data(self, tmp_db_path):
        """User existiert (touched), hat aber keine Daten → leerer Kontext."""
        mem = TwitchUserMemory(db_path=tmp_db_path)
        mem.touch("arcsore")
        assert mem.context_string("arcsore") == ""

    def test_two_users_isolated(self, tmp_db_path):
        """Cross-User-Isolation: A's Daten sind nicht in B's Memory."""
        mem = TwitchUserMemory(db_path=tmp_db_path)
        mem.set_location("alice", "Köln")
        mem.set_location("bob", "Berlin")
        assert mem.get("alice")["location"] == "Köln"
        assert mem.get("bob")["location"] == "Berlin"
        assert "Berlin" not in mem.context_string("alice")
        assert "Köln" not in mem.context_string("bob")


# ── Name-Extraktion ───────────────────────────────────────────────────────────

class TestExtractName:
    def test_ich_heisse(self):
        assert extract_name("ich heiße Tom") == "Tom"

    def test_ich_heisse_alt(self):
        assert extract_name("ich heisse Lena") == "Lena"

    def test_mein_name_ist(self):
        assert extract_name("mein name ist Max") == "Max"

    def test_man_nennt_mich(self):
        assert extract_name("man nennt mich Sascha") == "Sascha"

    def test_nenn_mich(self):
        assert extract_name("nenn mich Jessi") == "Jessi"

    def test_ich_bin(self):
        assert extract_name("ich bin Tom") == "Tom"

    def test_ich_bin_negative(self):
        # "ich bin müde" darf NICHT als Name "Müde" extrahieren
        assert extract_name("ich bin müde") is None
        assert extract_name("ich bin happy") is None
        assert extract_name("ich bin online") is None

    def test_no_name_in_text(self):
        assert extract_name("hallo zusammen") is None
        assert extract_name("wie ist das wetter") is None


# ── Hobby-Extraktion ──────────────────────────────────────────────────────────

class TestExtractHobby:
    def test_mein_hobby_ist(self):
        result = extract_hobby("mein hobby ist Gitarre spielen")
        assert result is not None
        assert "Gitarre" in result

    def test_meine_hobbys_sind(self):
        result = extract_hobby("meine hobbys sind Klettern und Bouldern")
        assert result is not None
        assert "Klettern" in result

    def test_in_meiner_freizeit(self):
        result = extract_hobby("in meiner freizeit fotografiere ich gerne")
        assert result is not None
        assert "fotografiere" in result.lower()

    def test_ich_liebe(self):
        result = extract_hobby("ich liebe Bouldern")
        assert result is not None
        assert "Bouldern" in result

    def test_ich_spiele_gern(self):
        result = extract_hobby("ich spiele gern Gitarre")
        assert result is not None
        assert "Gitarre" in result

    def test_no_hobby(self):
        assert extract_hobby("hallo donna") is None


# ── context_string mit Name + Hobby ──────────────────────────────────────────

class TestContextStringExtended:
    def test_includes_name_and_hobby(self, tmp_db_path):
        mem = TwitchUserMemory(db_path=tmp_db_path)
        mem.set_fact("weyojessi", "name", "Jessi")
        mem.set_fact("weyojessi", "hobby", "Bouldern")
        mem.set_location("weyojessi", "Stuttgart")
        ctx = mem.context_string("weyojessi")
        assert "Jessi" in ctx
        assert "Bouldern" in ctx
        assert "Stuttgart" in ctx
        assert "weyojessi" in ctx
