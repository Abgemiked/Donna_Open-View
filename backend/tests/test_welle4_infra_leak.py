"""FIX 3 (ki-training g004): Infra-Leak-Guard auf Read-Time.

Donna soll Server-Interna (your-server/CCX23, Nginx Proxy Manager, Postgres/Redis, …)
nicht ungefragt aus dem LTM in den Chat-Kontext ziehen — ausser Mike fragt im
aktuellen Turn gezielt nach seiner Infrastruktur.
"""
from __future__ import annotations

from app.routes.chat import (
    _build_prompt_context,
    _contains_infra,
    _is_infra_query,
)


# --- Primitive --------------------------------------------------------------

def test_contains_infra_true():
    assert _contains_infra("Der Server läuft auf CCX23 mit Nginx Proxy Manager")
    assert _contains_infra("postgres und redis im docker container")
    assert _contains_infra("Pfad /opt/donna, port 8000")


def test_contains_infra_false():
    assert not _contains_infra("Mike mag lieber Pizza als Pasta")
    assert not _contains_infra("Nächster Stream ist am Freitag")


def test_is_infra_query_true_when_mike_asks():
    assert _is_infra_query("Wie ist mein Backend konfiguriert?")
    assert _is_infra_query("Zeig mir das docker-Deployment")
    assert _is_infra_query("Welche datenbank nutze ich?")


def test_is_infra_query_false_for_unrelated():
    assert not _is_infra_query("Wie wird das Wetter morgen?")
    assert not _is_infra_query("Erinnere mich an den Zahnarzt")


# --- Read-Time-Filter im Kontext-Builder ------------------------------------

_INFRA_MEM = {"category": "user_fact",
              "content": "Server your-server CCX23, Postgres + Redis im Docker, Nginx Proxy Manager"}
_SAFE_MEM = {"category": "user_preference", "content": "Mag lieber Pizza als Pasta"}


def test_infra_ltm_filtered_when_unrelated_question():
    ctx = _build_prompt_context(
        message="Wie wird das Wetter morgen?",
        hits=[],
        history=[],
        ltm_memories=[_INFRA_MEM, _SAFE_MEM],
    )
    contents = [m["content"] for m in ctx.ltm_memories]
    assert any("Pizza" in c for c in contents)
    assert not any("CCX23" in c or "Postgres" in c for c in contents)


def test_infra_ltm_kept_when_mike_asks_about_infra():
    ctx = _build_prompt_context(
        message="Wie sieht meine Server-Architektur aus?",
        hits=[],
        history=[],
        ltm_memories=[_INFRA_MEM, _SAFE_MEM],
    )
    contents = [m["content"] for m in ctx.ltm_memories]
    assert any("CCX23" in c for c in contents)  # Infra darf durch, Mike fragt gezielt


def test_infra_hit_filtered_from_retrieval():
    hits = [
        {"source": "ltm", "text": "Donna läuft als fastapi/uvicorn auf hetzner"},
        {"source": "stm", "text": "Letzter Deploy war okay"},  # kein Infra-Keyword
        {"source": "ltm", "text": "Mike trinkt morgens Kaffee"},
    ]
    ctx = _build_prompt_context(
        message="Was machen wir heute?",
        hits=hits,
        history=[],
    )
    texts = [h["text"] for h in ctx.brain_hits]
    assert not any("fastapi" in t or "hetzner" in t for t in texts)
    assert any("Kaffee" in t for t in texts)
    assert any("Deploy war okay" in t for t in texts)


def test_safe_memories_always_kept():
    ctx = _build_prompt_context(
        message="Erzähl mir was",
        hits=[],
        history=[],
        ltm_memories=[_SAFE_MEM],
    )
    assert len(ctx.ltm_memories) == 1


# --- R4: Präzisierung Gate + Leak-Erkennung (weniger FP / weniger Über-Filterung) ---

import pytest


@pytest.mark.parametrize("msg", [
    "Welcher Discord-Server ist am aktivsten?",
    "Zeig mir ein cooles Gaming-Setup für den Stream",
    "Lohnt sich eine Infrarotheizung fürs Büro?",
    # R4-Lücke: bare "server"/"setup" + FP "sports"/"reports" dürfen NICHT öffnen.
    "Valorant Server Ping ist schlecht",
    "Mein Setup zum Zocken",
    "Zeig mir die Sports Ergebnisse",
    "Reports vom Stream anschauen",
    "Lag auf dem Server vom Spiel",
])
def test_compounds_do_not_open_gate(msg):
    # Komposita/FP/bare-server dürfen das Gate NICHT öffnen → Infra-LTM bleibt gefiltert.
    assert _is_infra_query(msg) is False
    ctx = _build_prompt_context(message=msg, hits=[], history=[], ltm_memories=[_INFRA_MEM])
    assert not any("CCX23" in m["content"] for m in ctx.ltm_memories)


@pytest.mark.parametrize("msg", [
    "Wo wirst du gehostet?",
    "Welche IP und Ports nutzt du?",
    "Wie sieht meine Server-Architektur aus?",
    "Welches Backend nutzt du?",
    "Hast du einen SSH-Zugang?",
])
def test_genuine_infra_questions_open_gate(msg):
    assert _is_infra_query(msg) is True
    ctx = _build_prompt_context(message=msg, hits=[], history=[], ltm_memories=[_INFRA_MEM])
    assert any("CCX23" in m["content"] for m in ctx.ltm_memories)


@pytest.mark.parametrize("text", [
    "Der Müllcontainer wird dienstags geleert",     # Mandat-FP: 'container' Wortgrenze
    "Ich bin heute Sport 8000 Schritte gelaufen",   # Mandat-FP: 'port 8000' Wortgrenze
    "Mike mag Sport und wandert gern",
])
def test_no_false_positive_infra_detection(text):
    # FP-Strings dürfen NICHT als Infra-Leak erkannt werden (Mandat: Müllcontainer/Sport 8000).
    assert _contains_infra(text) is False


def test_real_infra_content_still_detected():
    assert _contains_infra("läuft im docker container auf port 8000") is True
    assert _contains_infra("Nginx Proxy Manager, Postgres, Redis auf Hetzner") is True
