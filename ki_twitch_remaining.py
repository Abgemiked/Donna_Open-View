import json, time, urllib.request

ADMIN_TOKEN = "YOUR_ADMIN_TOKEN_HERE"
BASE_URL = "http://YOUR_SERVER_IP:8000"

REMAINING = [
  {"id":"tw033","category":"bits_cheer","username":"BigCheer","message":"cheer1000 LEGEND MODE ACTIVATED","session_id":"tw_anon","stream_live":True,"should_moderate":False},
  {"id":"tw034","category":"bits_cheer","username":"SecretFan","message":"cheer250 keep up the content!","session_id":"tw_anon","stream_live":True,"should_moderate":False},
  {"id":"tw035","category":"bits_cheer","username":"SilentDonor","message":"cheer75 just vibes","session_id":"tw_anon","stream_live":True,"should_moderate":False},
  {"id":"tw036","category":"moderation","username":"SpamBot123","message":"CHECK THIS OUT CLICK HERE bit.ly/free","session_id":"tw_anon","stream_live":True,"should_moderate":True},
  {"id":"tw037","category":"moderation","username":"CapsLock99","message":"AAAAHHH SOOO MAD RIGHT NOW!!!","session_id":"tw_anon","stream_live":True,"should_moderate":True},
  {"id":"tw038","category":"moderation","username":"RepeatSpammer","message":"hey buy crypto hey buy crypto hey buy crypto","session_id":"tw_anon","stream_live":True,"should_moderate":True},
  {"id":"tw039","category":"moderation","username":"ToxicTroll","message":"thats cringe bro you suck lol","session_id":"tw_anon","stream_live":True,"should_moderate":False},
  {"id":"tw040","category":"moderation","username":"HateSpeech","message":"this is garbage content nobody watches you","session_id":"tw_anon","stream_live":True,"should_moderate":False},
  {"id":"tw041","category":"donna_direkt","username":"CuriousViewer","message":"@donna wie gehts dir?","session_id":"tw_anon","stream_live":True,"should_moderate":False},
  {"id":"tw042","category":"donna_direkt","username":"QuestionAsker","message":"donna kennst du valorant?","session_id":"tw_anon","stream_live":True,"should_moderate":False},
  {"id":"tw043","category":"donna_direkt","username":"GameExpert","message":"donna who would win hearthstone 1v1 you vs mike?","session_id":"tw_anon","stream_live":True,"should_moderate":False},
  {"id":"tw044","category":"donna_direkt","username":"StreamFan","message":"donna is mike even good or just lucky lmao","session_id":"tw_anon","stream_live":True,"should_moderate":False},
  {"id":"tw045","category":"donna_direkt","username":"TechCurious","message":"donna was sind deine features?","session_id":"tw_anon","stream_live":True,"should_moderate":False},
  {"id":"tw046","category":"multi_turn","username":"Kevin_IRL","message":"yo spielst du hearthstone heute?","session_id":"tw_kevin","stream_live":True,"should_moderate":False},
  {"id":"tw047","category":"multi_turn","username":"Kevin_IRL","message":"bock auf 1v1 danach?","session_id":"tw_kevin","stream_live":True,"should_moderate":False},
  {"id":"tw048","category":"multi_turn","username":"Kevin_IRL","message":"alter du wirst mir weggesperrt haha","session_id":"tw_kevin","stream_live":True,"should_moderate":False},
  {"id":"tw049","category":"stream_planung","username":"WeekendPlanner","message":"stream am wochenende?","session_id":"tw_anon","stream_live":False,"should_moderate":False},
  {"id":"tw050","category":"standard_commands","username":"LateArriver","message":"!uptime","session_id":"tw_anon","stream_live":False,"should_moderate":False},
]

results = []
for i, s in enumerate(REMAINING):
    print("[%02d/18] %s @%s: %s" % (i+1, s["id"], s["username"], s["message"][:50]))
    start = time.time()
    payload = json.dumps({"message": s["message"], "session_id": s["session_id"], "stream_live": s.get("stream_live", True)}).encode()
    req = urllib.request.Request(BASE_URL + "/chat/twitch", data=payload,
        headers={"Authorization": "Bearer " + ADMIN_TOKEN, "Content-Type": "application/json"}, method="POST")
    resp_text = ""
    status = "ok"
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            body = json.loads(resp.read().decode())
            resp_text = str(body.get("response") or "")[:400]
    except Exception as e:
        status = "error: " + str(e)[:80]
    elapsed = int((time.time()-start)*1000)
    results.append({"id":s["id"],"category":s["category"],"username":s["username"],"message":s["message"],"response":resp_text,"latency_ms":elapsed,"status":status,"should_moderate":s.get("should_moderate",False)})
    print("  [%s] %dms  %s" % (status[:2].upper(), elapsed, resp_text[:100]))
    import sys; sys.stdout.flush()
    time.sleep(2.5)

with open("/tmp/ki_twitch_remaining.json","w") as f:
    json.dump(results, f, ensure_ascii=False, indent=2)
print("Done - saved to /tmp/ki_twitch_remaining.json")
