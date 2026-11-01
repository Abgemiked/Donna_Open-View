"""Unit-Tests für media_profile_service (A2.2/A2.3) + chat.py Media-Gate/Live-Gate.

Fokus:
  * Profil-Zuordnung der 4 REALEN Jellyfin-User (geprüft via GET /Users 2026-10-30):
    mike→Mike, TV→TV, Watchparty→Watchparty, Yannik→Yannik. KEIN Ämi/Family/DreamDuo
    in Jellyfin — die sind eine separate Netflix-Quelle (kommt mit A3).
  * media_context-Block: baut "schon gesehen"-Zeile aus Jellyfin + LTM media_seen.
  * _MEDIA_RE/_is_media_query triggert bei Medien-Fragen, NICHT bei Fremdwörtern.
  * Live-Gate: im Stream (is_live ODER live_silent) KEIN privater Medien-Inhalt im Prompt.
"""
from __future__ import annotations

import ast
import asyncio
import inspect
import textwrap

import pytest

from app.services.media_profile_service import (
    DEFAULT_PROFILE,
    JELLYFIN_PROFILES,
    NETFLIX_PROFILES_PLANNED,
    MediaProfileService,
    resolve_profile,
)
from app.routes import chat as chat_mod
from app.routes.chat import (
    _is_media_query,
    _load_media_context,
    _LIVE_BLOCKED_CATEGORIES,
    _GENERAL_RECALL_EXCLUDED_CATEGORIES,
    _filter_live_ltm_memories,
    _filter_live_brain_hits,
    _filter_general_recall,
    _filter_general_brain_hits,
)


# ---------------------------------------------------------------------------
# Fake-Jellyfin (quell-unabhängige Abstraktion — kein echter Call)
# ---------------------------------------------------------------------------

class _FakeJellyfin:
    def __init__(self, users, played, *, ready=True):
        self._users = users
        self._played = played  # {user_id: [items]}
        self._ready = ready

    def ready(self):
        return self._ready

    async def list_users(self):
        return list(self._users)

    async def played_items(self, user_id, limit=100):
        return list(self._played.get(user_id, []))


def _svc_with_mike():
    # Reale Jellyfin-User: lowercase `mike` + `Yannik` (siehe GET /Users).
    jelly = _FakeJellyfin(
        users=[{"Name": "mike", "Id": "u1"}, {"Name": "Yannik", "Id": "u2"}],
        played={
            "u1": [
                {"Name": "Inception", "Type": "Movie", "Id": "m1"},
                {"Name": "The Wire", "Type": "Series", "Id": "s1"},
            ],
            "u2": [{"Name": "Gilmore Girls", "Type": "Series", "Id": "s2"}],
        },
    )
    return MediaProfileService(jellyfin=jelly)


# ---------------------------------------------------------------------------
# Profil-Zuordnung — die 4 REALEN Jellyfin-User
# ---------------------------------------------------------------------------

def test_resolve_profile_real_jellyfin_users():
    assert resolve_profile("mike") == "Mike"      # lowercase-User → Mike
    assert resolve_profile("Mike") == "Mike"
    assert resolve_profile("Yannik") == "Yannik"
    assert resolve_profile("TV") == "TV"
    assert resolve_profile("Watchparty") == "Watchparty"


def test_no_invented_jellyfin_profiles():
    # Ämi/Family/DreamDuo sind KEINE Jellyfin-Profile — sie sind nur im (separaten)
    # Netflix-Plan-Set gelistet und tauchen NICHT als reale Jellyfin-Mappings auf.
    for p in ("Ämi", "Family", "DreamDuo"):
        assert p not in JELLYFIN_PROFILES
        assert p in NETFLIX_PROFILES_PLANNED


def test_resolve_profile_unknown_is_titlecased_not_crash():
    # Unbekannte/künftige Jellyfin-User: nicht crashen, fail-safe title-casen.
    assert resolve_profile("gastnutzer") == "Gastnutzer"
    assert resolve_profile("") == DEFAULT_PROFILE
    assert resolve_profile(None) == DEFAULT_PROFILE


def test_jellyfin_profiles_are_the_four_real_users():
    assert JELLYFIN_PROFILES == ("Mike", "TV", "Watchparty", "Yannik")
    assert DEFAULT_PROFILE == "Mike"


# ---------------------------------------------------------------------------
# media_context-Builder
# ---------------------------------------------------------------------------

def test_seen_titles_for_mike():
    svc = _svc_with_mike()
    titles = asyncio.run(svc.seen_titles("Mike"))
    assert "Inception" in titles
    assert "The Wire" in titles
    assert "Gilmore Girls" not in titles  # das ist Ämis Profil


def test_build_media_context_includes_seen():
    svc = _svc_with_mike()
    ctx = asyncio.run(svc.build_media_context(profile="Mike"))
    assert ctx is not None
    assert "[Medien-Kontext]" in ctx
    assert "Inception" in ctx
    assert "Mike" in ctx


def test_build_media_context_merges_ltm_media_seen():
    svc = _svc_with_mike()
    ltm_seen = [{"content": "Mike hat Breaking Bad gesehen", "category": "media_seen"}]
    ctx = asyncio.run(svc.build_media_context(profile="Mike", ltm_seen=ltm_seen))
    assert ctx is not None
    assert "Breaking Bad" in ctx


def test_build_media_context_none_when_empty():
    jelly = _FakeJellyfin(users=[{"Name": "Mike", "Id": "u1"}], played={"u1": []})
    svc = MediaProfileService(jellyfin=jelly)
    assert asyncio.run(svc.build_media_context(profile="Mike")) is None


def test_service_not_ready_without_jellyfin():
    assert MediaProfileService(jellyfin=None).ready() is False


# ---------------------------------------------------------------------------
# _MEDIA_RE / _is_media_query
# ---------------------------------------------------------------------------

# Je Keyword/Alternative EIN Satz (kein zweites Keyword darin) — R5: isolierte Positivfälle.
@pytest.mark.parametrize("msg", [
    "Lust auf einen Film heute Abend",          # film
    "Welche Serie läuft heute Abend?",          # serie
    "Wann kommt die neue Staffel raus?",        # staffel
    "Gibt es neue Folgen?",                     # folgen
    "Was soll ich heute schauen?",              # schauen
    "Lust was zu gucken?",                      # gucken
    "Kannst du mir was empfehlen?",             # empfehlen
    "Was wird gerade empfohlen?",               # empfohlen
    "Hab ich das schon gesehen?",               # gesehen
    "Was steht auf meiner Watchlist?",          # watchlist
    "Ich will heute bingen",                    # binge
    "Was kommt heute im Fernsehen?",            # fernsehen
    "Zeig mir ein Video",                       # video
    "Was gibt es Neues auf Netflix?",           # netflix
    "Was ist auf Jellyfin verfügbar?",          # jellyfin
    "Was läuft auf YouTube?",                   # youtube
    "Was läuft gerade im Kino?",                # kino
    "Zeig mir die Mediathek",                   # mediathek
])
def test_media_query_positives(msg):
    assert _is_media_query(msg) is True


# R5: echte Fehlwörter, die OHNE \b matchen WÜRDEN — Test wird rot, wenn man das
# führende/abschließende \b aus _MEDIA_RE entfernt. Plus schau/guck-Nomen-Guards.
@pytest.mark.parametrize("msg", [
    "Wie ist das Wetter heute?",
    "Ich brauche Informationen zum Projekt",
    "Schalte das Licht im Wohnzimmer an",
    "Wann ist mein nächster Termin?",
    "Die Polizei jagt einen Serienmörder",   # 'serien...' nur ohne trailing \b
    "Dem Bericht zufolge steigt die Miete",  # 'folge' nur ohne leading \b
    "Das Kinoprogramm hängt am Brett",       # 'kino...' nur ohne trailing \b
    "Ein kräftiger Schauer zieht auf",       # 'schau...' darf Nomen nicht treffen
    "Er holt die Schaufel aus dem Keller",   # dito
    "Das Guckloch ist verdreckt",            # 'guck...' darf Nomen nicht treffen
    "Eine Videokonferenz steht an",          # 'video...' nur als ganzes Wort
])
def test_media_query_negatives(msg):
    assert _is_media_query(msg) is False


# ---------------------------------------------------------------------------
# Live-Gate (Stream-Safety): kein privater Medien-Inhalt im Stream
# ---------------------------------------------------------------------------

def test_load_media_context_blocked_when_live():
    svc = _svc_with_mike()
    out = asyncio.run(_load_media_context(
        svc, is_smalltalk=False, is_live=True, live_silent=False,
    ))
    assert out is None


def test_load_media_context_blocked_when_live_silent():
    svc = _svc_with_mike()
    out = asyncio.run(_load_media_context(
        svc, is_smalltalk=False, is_live=False, live_silent=True,
    ))
    assert out is None


def test_load_media_context_blocked_when_smalltalk():
    svc = _svc_with_mike()
    out = asyncio.run(_load_media_context(
        svc, is_smalltalk=True, is_live=False, live_silent=False,
    ))
    assert out is None


def test_load_media_context_loads_outside_stream():
    svc = _svc_with_mike()
    out = asyncio.run(_load_media_context(
        svc, is_smalltalk=False, is_live=False, live_silent=False,
    ))
    assert out is not None
    assert "Inception" in out


def test_load_media_context_none_when_service_missing():
    out = asyncio.run(_load_media_context(
        None, is_smalltalk=False, is_live=False, live_silent=False,
    ))
    assert out is None


# ---------------------------------------------------------------------------
# BLOCKER-1: media_seen leakt NICHT über den allgemeinen Recall/Brain-Pfad im Live-Fall
# ---------------------------------------------------------------------------

def test_media_seen_is_in_live_blocklist():
    # Direkter Mutationsbeweis: nimmt man media_seen aus der Blockliste, werden (a)/(b)/(c) rot.
    assert "media_seen" in _LIVE_BLOCKED_CATEGORIES


# ---------------------------------------------------------------------------
# ÄNDERUNG 2 (#8): media_seen aus dem ALLGEMEINEN Recall raus — auch OFFLINE —,
# aber weiter im dedizierten media_context. (A5-thread_state-Muster auf media_seen.)
# ---------------------------------------------------------------------------

def test_media_seen_in_general_recall_exclusion_set():
    # Single Source of Truth: media_seen steht im zentralen Ausschluss-Satz.
    # RED, wenn media_seen dort entfernt wird.
    assert "media_seen" in _GENERAL_RECALL_EXCLUDED_CATEGORIES


def test_media_seen_dropped_from_general_recall_offline():
    # (offline, kein Stream): media_seen darf den allgemeinen Recall/Meta-Dump nicht zumüllen.
    mems = [
        {"category": "user_fact", "content": "Mike mag Pizza"},
        {"category": "media_seen", "content": "Mike hat Breaking Bad gesehen"},
    ]
    out = _filter_general_recall(mems)
    cats = [m["category"] for m in out]
    assert "media_seen" not in cats
    assert all("Breaking Bad" not in m.get("content", "") for m in out)
    assert "user_fact" in cats  # harmlose Kategorien bleiben


def test_media_seen_still_delivered_via_dedicated_media_context():
    # Gegenprobe / Nicht-kaputtmach-Garantie: derselbe media_seen-Eintrag, über den
    # dedizierten Pfad (ltm_seen) gereicht, landet WEITERHIN im [Medien-Kontext].
    svc = _svc_with_mike()
    media_mem = {"content": "Mike hat Breaking Bad gesehen", "category": "media_seen"}
    ctx = asyncio.run(svc.build_media_context(profile="Mike", ltm_seen=[media_mem]))
    assert ctx is not None and "Breaking Bad" in ctx


def test_wiring_dedicated_media_recall_uses_media_seen_category():
    # media_context-Pfad darf NICHT kaputtgehen: der dedizierte Recall ruft
    # recall_relevant(..., category="media_seen", ...). RED, wenn die Kategorie wegfällt.
    tree = ast.parse(textwrap.dedent(inspect.getsource(chat_mod.chat)))
    found = any(
        isinstance(n, ast.Call) and any(
            kw.arg == "category"
            and isinstance(kw.value, ast.Constant)
            and kw.value.value == "media_seen"
            for kw in n.keywords
        )
        for n in ast.walk(tree)
    )
    assert found, 'Dedizierter Medien-Recall mit category="media_seen" fehlt'


def test_wiring_dedicated_media_seen_not_through_general_filter():
    # F3 (R4): der dedizierte media_seen-Recall (_media_ltm_seen) darf NICHT durch
    # _filter_general_recall laufen, bevor er in _load_media_context geht — sonst würde
    # die Medien-Empfehlung ihre eigene Quelle wegfiltern. RED, wenn jemand das einschiebt.
    tree = ast.parse(textwrap.dedent(inspect.getsource(chat_mod.chat)))
    # (a) kein _filter_general_recall(...) mit _media_ltm_seen als Argument
    for n in ast.walk(tree):
        if (isinstance(n, ast.Call) and isinstance(n.func, ast.Name)
                and n.func.id == "_filter_general_recall"):
            arg_names = {s.id for a in n.args for s in ast.walk(a) if isinstance(s, ast.Name)}
            arg_names |= {s.id for kw in n.keywords for s in ast.walk(kw.value)
                          if isinstance(s, ast.Name)}
            assert "_media_ltm_seen" not in arg_names, \
                "_media_ltm_seen läuft durch _filter_general_recall (Medien-Empfehlung kaputt)"
    # (b) der ltm_seen=-Arg an _load_media_context ist der NACKTE Name _media_ltm_seen
    load_calls = [
        n for n in ast.walk(tree)
        if isinstance(n, ast.Call) and isinstance(n.func, ast.Name)
        and n.func.id == "_load_media_context"
    ]
    assert load_calls, "_load_media_context-Aufruf nicht gefunden"
    for c in load_calls:
        kw = {k.arg: k.value for k in c.keywords}
        assert "ltm_seen" in kw
        assert isinstance(kw["ltm_seen"], ast.Name) and kw["ltm_seen"].id == "_media_ltm_seen", \
            "ltm_seen muss der ungefilterte _media_ltm_seen sein"


# ---------------------------------------------------------------------------
# FIX 3 (#8b/R3): media_seen/thread_state leaken NICHT über _retrieve-Brain-Hits (offline)
# ---------------------------------------------------------------------------

def test_general_brain_hits_exclude_media_seen_and_thread_state():
    # media_seen UND thread_state LTM-Brain-Hits raus — IMMER (auch offline).
    hits = [
        {"source": "ltm", "text": "Mike hat Dune gesehen", "meta": {"category": "media_seen"}},
        {"source": "ltm", "text": "Stand: Steuer offen Anlage EÜR", "meta": {"category": "thread_state"}},
        {"source": "ltm", "text": "Mike programmiert gern", "meta": {"category": "user_fact"}},
        {"source": "stm", "text": "kurzzeit-media_seen", "meta": {"category": "media_seen"}},
    ]
    out = _filter_general_brain_hits(hits, ltm_cap=5)
    # kein LTM-Hit mehr mit ausgeschlossener Kategorie:
    assert all((h.get("meta") or {}).get("category") not in ("media_seen", "thread_state")
               for h in out if h["source"] == "ltm")
    assert all("Dune" not in h.get("text", "") for h in out)
    assert all("Anlage EÜR" not in h.get("text", "") for h in out)
    # harmloser LTM-Hit bleibt, STM bleibt UNBERÜHRT (auch wenn meta.category media_seen):
    assert any(h["text"] == "Mike programmiert gern" for h in out)
    assert any(h["source"] == "stm" for h in out)


def test_general_brain_hits_overfetch_cap_keeps_real_facts():
    # Over-Fetch-Semantik: 6 media_seen + 5 user_fact LTM-Hits, Cap 5 → alle 5 echten
    # Fakten bleiben, KEIN media_seen verdrängt sie (sonst wäre der Over-Fetch sinnlos).
    hits = (
        [{"source": "ltm", "text": f"seen{i}", "meta": {"category": "media_seen"}} for i in range(6)]
        + [{"source": "ltm", "text": f"fact{i}", "meta": {"category": "user_fact"}} for i in range(5)]
    )
    out = _filter_general_brain_hits(hits, ltm_cap=5)
    assert [h["text"] for h in out] == [f"fact{i}" for i in range(5)]


def test_general_brain_hits_caps_ltm_but_not_stm():
    # LTM wird auf ltm_cap gekappt; STM-Hits bleiben zusätzlich alle erhalten.
    hits = (
        [{"source": "ltm", "text": f"f{i}", "meta": {"category": "user_fact"}} for i in range(8)]
        + [{"source": "stm", "text": f"s{i}", "meta": {}} for i in range(3)]
    )
    out = _filter_general_brain_hits(hits, ltm_cap=5)
    ltm_out = [h for h in out if h["source"] == "ltm"]
    stm_out = [h for h in out if h["source"] == "stm"]
    assert len(ltm_out) == 5
    assert len(stm_out) == 3


def test_wiring_brain_hits_run_through_general_filter():
    # AST: `hits` wird (offline+online) aus _filter_general_brain_hits(...) neu zugewiesen.
    # RED, wenn der Aufruf entfernt wird → media_seen leakt als [Relevante Brain-Einträge].
    tree = ast.parse(textwrap.dedent(inspect.getsource(chat_mod.chat)))
    found = any(
        isinstance(n, ast.Assign)
        and any(isinstance(t, ast.Name) and t.id == "hits" for t in n.targets)
        and isinstance(n.value, ast.Call)
        and isinstance(n.value.func, ast.Name)
        and n.value.func.id == "_filter_general_brain_hits"
        for n in ast.walk(tree)
    )
    assert found, "hits muss durch _filter_general_brain_hits laufen (offline+online)"


def test_a_media_seen_ltm_memory_blocked_live():
    # (a) media_seen-Eintrag aus recall_relevant/get_all darf im Live-Fall NICHT ins Prompt.
    mems = [
        {"category": "user_fact", "content": "Mike mag Pizza"},
        {"category": "media_seen", "content": "Mike hat Breaking Bad gesehen"},
    ]
    out = _filter_live_ltm_memories(mems)
    cats = [m["category"] for m in out]
    assert "media_seen" not in cats
    assert all("Breaking Bad" not in m.get("content", "") for m in out)
    assert "user_fact" in cats  # harmlose Kategorien bleiben erhalten


def test_b_media_seen_brain_hit_blocked_live():
    # (b) derselbe Leak über _retrieve/Brain-Hits (source=="ltm", meta.category).
    hits = [
        {"source": "ltm", "text": "Mike hat Dune gesehen", "meta": {"category": "media_seen"}},
        {"source": "ltm", "text": "Mike programmiert gern", "meta": {"category": "user_fact"}},
        {"source": "stm", "text": "kurzfristiger Kontext", "meta": {"category": "media_seen"}},
    ]
    out = _filter_live_brain_hits(hits)
    # LTM-media_seen-Hit entfernt:
    assert not any(
        h["source"] == "ltm" and (h.get("meta") or {}).get("category") == "media_seen"
        for h in out
    )
    assert all("Dune" not in h.get("text", "") for h in out)
    # harmloser LTM-Hit bleibt, STM bleibt unberührt (spezifikationsgemäß):
    assert any(h["text"] == "Mike programmiert gern" for h in out)
    assert any(h["source"] == "stm" for h in out)


def test_c_meta_query_all_excludes_media_seen_live():
    # (c) Meta-Query "was weißt du über mich" (get_all) im Live-Fall ohne media_seen.
    all_mem = [
        {"category": "user_preference", "content": "mag Pizza"},
        {"category": "media_seen", "content": "hat Inception gesehen"},
        {"category": "user_habit", "content": "joggt morgens"},
    ]
    out = _filter_live_ltm_memories(all_mem)
    joined = " ".join(m["content"] for m in out)
    assert "Inception" not in joined
    assert "Pizza" in joined and "joggt" in joined


# ---------------------------------------------------------------------------
# BLOCKER-2: Latenz-Budget + Garbage-Resilienz im Medien-Kontext
# ---------------------------------------------------------------------------

class _HangingMedia:
    """Fake MediaProfileService, dessen build_media_context zu lange braucht."""
    def __init__(self, delay_s: float, result="[Medien-Kontext]\nSchon gesehen (Mike): X"):
        self._delay = delay_s
        self._result = result

    def ready(self):
        return True

    async def build_media_context(self, *, profile="Mike", ltm_seen=None, max_items=8):
        await asyncio.sleep(self._delay)
        return self._result


class _ThrowingMedia:
    def ready(self):
        return True

    async def build_media_context(self, *, profile="Mike", ltm_seen=None, max_items=8):
        raise RuntimeError("quelle kaputt")


def test_load_media_context_returns_none_after_budget(monkeypatch):
    # Hänger-Test: build_media_context > Budget → None (hartes asyncio.wait_for-Budget).
    # Mutation (wait_for entfernen) → gibt den echten Kontext erst nach 1s zurück → rot.
    monkeypatch.setattr(chat_mod, "_MEDIA_CTX_TIMEOUT_S", 0.1)
    svc = _HangingMedia(delay_s=1.0)
    out = asyncio.run(_load_media_context(
        svc, is_smalltalk=False, is_live=False, live_silent=False,
    ))
    assert out is None


def test_load_media_context_fast_result_passes(monkeypatch):
    # Gegenprobe: schnell genug → Kontext kommt durch (Budget blockt nicht fälschlich).
    monkeypatch.setattr(chat_mod, "_MEDIA_CTX_TIMEOUT_S", 1.0)
    svc = _HangingMedia(delay_s=0.0)
    out = asyncio.run(_load_media_context(
        svc, is_smalltalk=False, is_live=False, live_silent=False,
    ))
    assert out is not None and "Medien-Kontext" in out


def test_load_media_context_none_when_source_throws():
    out = asyncio.run(_load_media_context(
        _ThrowingMedia(), is_smalltalk=False, is_live=False, live_silent=False,
    ))
    assert out is None


def test_seen_titles_survives_non_dict_garbage():
    # Garbage-Antwort (Nicht-Dict-Listenelemente) darf nicht crashen → [] statt AttributeError.
    jelly = _FakeJellyfin(users=[1, 2, "x"], played={})
    svc = MediaProfileService(jellyfin=jelly)
    assert asyncio.run(svc.seen_titles("Mike")) == []


def test_build_media_context_survives_non_dict_played_items():
    jelly = _FakeJellyfin(
        users=[{"Name": "mike", "Id": "u1"}],
        played={"u1": [{"Name": "Inception"}, 42, "garbage", None]},
    )
    svc = MediaProfileService(jellyfin=jelly)
    ctx = asyncio.run(svc.build_media_context(profile="Mike"))
    assert ctx is not None and "Inception" in ctx


def test_build_media_context_survives_non_dict_ltm_seen():
    svc = _svc_with_mike()
    ltm_seen = ["not a dict", 42, {"content": "Mike hat Dune gesehen"}]
    ctx = asyncio.run(svc.build_media_context(profile="Mike", ltm_seen=ltm_seen))
    assert ctx is not None and "Dune" in ctx
