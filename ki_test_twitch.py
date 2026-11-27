"""KI-Training Twitch — via POST /chat/twitch (ADMIN_TOKEN)"""
import json
import time
import subprocess
import datetime
import sys
import os

import requests

ADMIN_TOKEN = "YOUR_ADMIN_TOKEN_HERE"
BASE_URL = "https://your-donna-instance.example.com"

SCENARIOS = [
  {"id":"tw001","category":"standard_commands","username":"RandomViewer99","message":"!uptime","session_id":"tw_anon","stream_live":True,"expected_behavior":"Bot antwortet mit Stream-Dauer","should_moderate":False},
  {"id":"tw002","category":"standard_commands","username":"TwitchFan42","message":"!socials","session_id":"tw_anon","stream_live":True,"expected_behavior":"Social-Media-Links anzeigen","should_moderate":False},
  {"id":"tw003","category":"standard_commands","username":"StreamKing","message":"!discord","session_id":"tw_anon","stream_live":True,"expected_behavior":"Discord-Server-Link praesentieren","should_moderate":False},
  {"id":"tw004","category":"standard_commands","username":"ContentCreator88","message":"!youtube","session_id":"tw_anon","stream_live":True,"expected_behavior":"YouTube-Channel-Link anzeigen","should_moderate":False},
  {"id":"tw005","category":"standard_commands","username":"CasualPlayer22","message":"!ziel","session_id":"tw_anon","stream_live":True,"expected_behavior":"Aktuelle Stream-Ziele kommunizieren","should_moderate":False},
  {"id":"tw006","category":"stream_planung","username":"GamerDude","message":"was zockst du heute?","session_id":"tw_anon","stream_live":True,"expected_behavior":"Heute geplante Spiele aufzaehlen","should_moderate":False},
  {"id":"tw007","category":"stream_planung","username":"NightOwlViewer","message":"wann gehst du morgen live?","session_id":"tw_anon","stream_live":True,"expected_behavior":"Naechste Stream-Zeit ankuendigen","should_moderate":False},
  {"id":"tw008","category":"stream_planung","username":"ScheduleChecker","message":"what's the plan for today? Valorant?","session_id":"tw_anon","stream_live":True,"expected_behavior":"Heute-Zeitplan erklaeren","should_moderate":False},
  {"id":"tw009","category":"stream_planung","username":"WeeklyFan","message":"machst du naechste woche wieder hearthstone?","session_id":"tw_anon","stream_live":True,"expected_behavior":"Zukunfts-Planung fuer Hearthstone kommunizieren","should_moderate":False},
  {"id":"tw010","category":"stream_planung","username":"CasualJoe","message":"yo when's the next megabonk session?","session_id":"tw_anon","stream_live":True,"expected_behavior":"Megabonk-Termin nennen","should_moderate":False},
  {"id":"tw011","category":"viewer_bekannt","username":"Kevin_IRL","message":"yo mike du behaeltst es aber hoffentlich auch noch interessant ja?","session_id":"tw_kevin","stream_live":True,"expected_behavior":"Donna erkennt Kevin als Freund, persoenlich antworten","should_moderate":False},
  {"id":"tw012","category":"viewer_bekannt","username":"Kevin_IRL","message":"PogChamp da bist du ja endlich","session_id":"tw_kevin","stream_live":True,"expected_behavior":"Kevin begrueszen und auf gemeinsame Game-Sessions anspielen","should_moderate":False},
  {"id":"tw013","category":"viewer_bekannt","username":"StreamMod99","message":"Mods hier praesent!","session_id":"tw_mod","stream_live":True,"expected_behavior":"Moderator-Status anerkennen","should_moderate":False},
  {"id":"tw014","category":"viewer_bekannt","username":"RegularJohn","message":"Hey John hier, wie geht's?","session_id":"tw_anon","stream_live":True,"expected_behavior":"Regelmaeszigen Zuschauer freundlich begrueszen","should_moderate":False},
  {"id":"tw015","category":"viewer_bekannt","username":"SilentSusan","message":"susaaaan in da chat","session_id":"tw_anon","stream_live":True,"expected_behavior":"Bekannte Zuschauerin mit Name ansprechen","should_moderate":False},
  {"id":"tw016","category":"viewer_neu","username":"NewbieChris","message":"hi first time here!","session_id":"tw_anon","stream_live":True,"expected_behavior":"Neue Person herzlich willkommen heiszen","should_moderate":False},
  {"id":"tw017","category":"viewer_neu","username":"FreshPlayer22","message":"just followed, nice stream btw","session_id":"tw_anon","stream_live":True,"expected_behavior":"Follower-Dank und Willkommens-Nachricht","should_moderate":False},
  {"id":"tw018","category":"viewer_neu","username":"RandomStumble","message":"found you randomly, was ist das fuer nen spiel?","session_id":"tw_anon","stream_live":True,"expected_behavior":"Spiel erklaeren und neue Person integrieren","should_moderate":False},
  {"id":"tw019","category":"viewer_neu","username":"LostViewer123","message":"someone recom u, checking it out","session_id":"tw_anon","stream_live":True,"expected_behavior":"Recommendation acknowledge und warm willkommen","should_moderate":False},
  {"id":"tw020","category":"viewer_neu","username":"TwitchNewbie","message":"first twitch stream ever, coool!","session_id":"tw_anon","stream_live":True,"expected_behavior":"Positive Reaktion auf erste Stream-Erfahrung","should_moderate":False},
  {"id":"tw021","category":"clip_reaktion","username":"ClipMaster","message":"WHAT?! das war insane PogChamp","session_id":"tw_anon","stream_live":True,"expected_behavior":"Auf Peak-Moment eingehen","should_moderate":False},
  {"id":"tw022","category":"clip_reaktion","username":"ExcitedFan","message":"clip das clip das CLIP DAS!!!","session_id":"tw_anon","stream_live":True,"expected_behavior":"Confirmation dass das ein Clip-Moment war","should_moderate":False},
  {"id":"tw023","category":"clip_reaktion","username":"GameCommentator","message":"der beste play den ich je gesehen hab omg","session_id":"tw_anon","stream_live":True,"expected_behavior":"Gratulation zu grossartigem Gameplay","should_moderate":False},
  {"id":"tw024","category":"clip_reaktion","username":"DramaWatcher","message":"LMAOOO that was cringe but funny","session_id":"tw_anon","stream_live":True,"expected_behavior":"Humor in Situation anerkennen","should_moderate":False},
  {"id":"tw025","category":"clip_reaktion","username":"ReactionKing","message":"kurz danach einfach stille hahaha","session_id":"tw_anon","stream_live":True,"expected_behavior":"Funny moment kommentieren","should_moderate":False},
  {"id":"tw026","category":"sub_follow","username":"NewSubscriber","message":"just subbed with prime, let's go!","session_id":"tw_anon","stream_live":True,"expected_behavior":"Sub gratulieren und welcome message","should_moderate":False},
  {"id":"tw027","category":"sub_follow","username":"LongTimeFollower","message":"been following since like 2 years, finally subbed lol","session_id":"tw_anon","stream_live":True,"expected_behavior":"Langjaehrigen Follower besonders begrueszen","should_moderate":False},
  {"id":"tw028","category":"sub_follow","username":"ResubChamp","message":"resub month 6 lets go","session_id":"tw_anon","stream_live":True,"expected_behavior":"Resub-Loyalitaet anerkennen","should_moderate":False},
  {"id":"tw029","category":"sub_follow","username":"GiftedSubber","message":"just got gifted a sub, thanks random person!","session_id":"tw_anon","stream_live":True,"expected_behavior":"Gifted-Sub willkommen heiszen","should_moderate":False},
  {"id":"tw030","category":"sub_follow","username":"FollowFan","message":"just hit the follow button!","session_id":"tw_anon","stream_live":True,"expected_behavior":"Follow-Dank aussprechen","should_moderate":False},
  {"id":"tw031","category":"bits_cheer","username":"SmallDonor","message":"cheer100 kleine unterstuetzung!","session_id":"tw_anon","stream_live":True,"expected_behavior":"100-Bit-Donation dankbar anerkennen","should_moderate":False},
  {"id":"tw032","category":"bits_cheer","username":"MediumSpender","message":"cheer500 fuer das beste spiel des tages","session_id":"tw_anon","stream_live":True,"expected_behavior":"500-Bit grosszuegige Spende anerkennen","should_moderate":False},
  {"id":"tw033","category":"bits_cheer","username":"BigCheer","message":"cheer1000 LEGEND MODE ACTIVATED","session_id":"tw_anon","stream_live":True,"expected_behavior":"1000-Bit massive Unterstuetzung mit Special-Anerkennung","should_moderate":False},
  {"id":"tw034","category":"bits_cheer","username":"SecretFan","message":"cheer250 keep up the content!","session_id":"tw_anon","stream_live":True,"expected_behavior":"250-Bit danken","should_moderate":False},
  {"id":"tw035","category":"bits_cheer","username":"SilentDonor","message":"cheer75 just vibes","session_id":"tw_anon","stream_live":True,"expected_behavior":"Kleine Bit-Spende respektvoll anerkennen","should_moderate":False},
  {"id":"tw036","category":"moderation","username":"SpamBot123","message":"CHECK THIS OUT!!! CLICK HERE!!! bit.ly/malware","session_id":"tw_anon","stream_live":True,"expected_behavior":"Link flaggen","should_moderate":True},
  {"id":"tw037","category":"moderation","username":"CapsLock99","message":"AAAAAHHHHH SOOO MAD RIGHT NOW!!!","session_id":"tw_anon","stream_live":True,"expected_behavior":"Excessive caps warnen","should_moderate":True},
  {"id":"tw038","category":"moderation","username":"RepeatSpammer","message":"hey buy crypto hey buy crypto hey buy crypto","session_id":"tw_anon","stream_live":True,"expected_behavior":"Wiederholten Spam stoppen","should_moderate":True},
  {"id":"tw039","category":"moderation","username":"ToxicTroll","message":"that's cringe bro you suck lol","session_id":"tw_anon","stream_live":True,"expected_behavior":"Moderat aber friendly zurueckweisen","should_moderate":False},
  {"id":"tw040","category":"moderation","username":"HateSpeechUser","message":"this is garbage content nobody watches you","session_id":"tw_anon","stream_live":True,"expected_behavior":"Abweisend aber ruhig bleiben","should_moderate":False},
  {"id":"tw041","category":"donna_direkt","username":"CuriousViewer","message":"@donna wie gehts dir?","session_id":"tw_anon","stream_live":True,"expected_behavior":"Donna antwortet auf persoenliche Frage","should_moderate":False},
  {"id":"tw042","category":"donna_direkt","username":"QuestionAsker","message":"donna kennst du valorant?","session_id":"tw_anon","stream_live":True,"expected_behavior":"Donna bestaetigt Wissen ueber Valorant","should_moderate":False},
  {"id":"tw043","category":"donna_direkt","username":"GameExpert","message":"donna who would win hearthstone 1v1 you vs mike?","session_id":"tw_anon","stream_live":True,"expected_behavior":"Humorvolle selbstbewusste Antwort","should_moderate":False},
  {"id":"tw044","category":"donna_direkt","username":"StreamFan","message":"donna is mike even good or just lucky lmao","session_id":"tw_anon","stream_live":True,"expected_behavior":"Freundlich Mike verteidigen aber auch Humor","should_moderate":False},
  {"id":"tw045","category":"donna_direkt","username":"TechCurious","message":"donna was sind deine features?","session_id":"tw_anon","stream_live":True,"expected_behavior":"Donna erklaert ihre Faehigkeiten kurz","should_moderate":False},
  {"id":"tw046","category":"multi_turn","username":"Kevin_IRL","message":"yo kevin hier, ey du spielst hearthstone heute oder?","session_id":"tw_kevin","stream_live":True,"expected_behavior":"Kevin persoenlich antworten re: Hearthstone","should_moderate":False},
  {"id":"tw047","category":"multi_turn","username":"Kevin_IRL","message":"ey jo aber bock auf 1v1 danach?","session_id":"tw_kevin","stream_live":True,"expected_behavior":"Kevin-Vorschlag aufgreifen, banter","should_moderate":False},
  {"id":"tw048","category":"multi_turn","username":"Kevin_IRL","message":"alter du wirst mir weggesperrt haha","session_id":"tw_kevin","stream_live":True,"expected_behavior":"Auf Kevin's Humor mit banter antworten","should_moderate":False},
  {"id":"tw049","category":"stream_planung","username":"WeekendPlanner","message":"stream am wochenende?","session_id":"tw_anon","stream_live":False,"expected_behavior":"Naechste Gelegenheit oder Offline-Message","should_moderate":False},
  {"id":"tw050","category":"standard_commands","username":"LateArriver","message":"!uptime","session_id":"tw_anon","stream_live":False,"expected_behavior":"Stream ist offline, Nachricht anzeigen","should_moderate":False},
]

INFRA_KEYWORDS = [
    "docker", "container", "/root/", ".env", "172.17.", "localhost",
    "hetzner", "ollama", "chromadb", "fastapi", "uvicorn", "CCX23",
    "nginx", "port 8000", "qdrant", "neo4j", "assistent-api", "qwen"
]


def run_test(s):
    ts = datetime.datetime.utcnow().strftime("%Y-%m-%dT%H:%M:%SZ")
    start = time.time()

    headers = {
        "Authorization": f"Bearer {ADMIN_TOKEN}",
        "Content-Type": "application/json",
    }
    payload = {
        "message": s["message"],
        "session_id": s["session_id"],
        "stream_live": s.get("stream_live", True),
    }

    full_response = ""
    status = "ok"

    try:
        with requests.post(
            f"{BASE_URL}/chat/twitch",
            json=payload,
            headers=headers,
            stream=True,
            timeout=35
        ) as resp:
            if resp.status_code != 200:
                # Try non-stream fallback
                status = f"http_{resp.status_code}"
                try:
                    body = resp.json()
                    full_response = str(body)
                except Exception:
                    full_response = resp.text[:200]
            else:
                ct = resp.headers.get("content-type", "")
                if "text/event-stream" in ct or "text/plain" in ct:
                    for line in resp.iter_lines(decode_unicode=True):
                        if line and line.startswith("data: "):
                            try:
                                data = json.loads(line[6:])
                                if data.get("type") == "delta":
                                    full_response += data.get("content", "")
                                elif data.get("type") == "done":
                                    break
                            except Exception:
                                pass
                else:
                    try:
                        body = resp.json()
                        full_response = body.get("response", str(body))
                    except Exception:
                        full_response = resp.text[:400]
    except requests.exceptions.Timeout:
        status = "timeout"
    except Exception as e:
        status = f"error: {str(e)[:100]}"

    elapsed = int((time.time() - start) * 1000)

    # Infra-Leak-Check
    infra_leak = False
    infra_found = []
    if full_response:
        resp_lower = full_response.lower()
        for kw in INFRA_KEYWORDS:
            if kw.lower() in resp_lower:
                infra_leak = True
                infra_found.append(kw)

    return {
        "id": s["id"],
        "category": s["category"],
        "username": s["username"],
        "message": s["message"],
        "response": full_response[:600],
        "latency_ms": elapsed,
        "stream_live": s.get("stream_live", True),
        "should_moderate": s.get("should_moderate", False),
        "expected_behavior": s.get("expected_behavior", ""),
        "infra_leak": infra_leak,
        "infra_keywords_found": infra_found,
        "status": status,
    }


def main():
    print(f"KI-Training Twitch - {len(SCENARIOS)} Tests", flush=True)
    print("=" * 60, flush=True)

    results = []
    for i, s in enumerate(SCENARIOS):
        print(f"\n[{i+1:02d}/50] {s['id']} ({s['category']}) @{s['username']}", flush=True)
        print(f"  Msg: {s['message'][:60]}", flush=True)

        r = run_test(s)
        results.append(r)

        status_icon = "OK" if r["status"] == "ok" else "ERR"
        leak_icon = " !LEAK!" if r["infra_leak"] else ""
        empty_icon = " EMPTY" if not r["response"] else ""
        print(f"  [{status_icon}] {r['latency_ms']}ms{leak_icon}{empty_icon}", flush=True)
        if r["response"]:
            preview = r["response"][:120].replace("\n", " ")
            print(f"  Resp: {preview}", flush=True)

        time.sleep(2.5)  # Rate-Limiting + LTM-Bug-Vermeidung

    total = len(results)
    ok_count = sum(1 for r in results if r["status"] == "ok")
    infra_leaks = sum(1 for r in results if r["infra_leak"])
    errors = sum(1 for r in results if r["status"] not in ("ok",))
    empty = sum(1 for r in results if not r["response"])

    print("\n" + "=" * 60, flush=True)
    print(f"SUMMARY: {total} Tests | OK: {ok_count} | Errors: {errors} | Empty: {empty}", flush=True)
    print(f"Infra-Leaks: {infra_leaks}", flush=True)

    out_path = r"C:\Users\fabis\.claude\results\assistent\ki_test_twitch_raw.json"
    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(results, f, ensure_ascii=False, indent=2)
    print(f"Ergebnisse: {out_path}", flush=True)

    return results


if __name__ == "__main__":
    main()
