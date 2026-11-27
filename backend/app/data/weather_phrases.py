"""Bausteine fuer die Wetter-Antwort ohne Modell (#152). Nur Daten, keine Logik.

Aufbau einer Antwort (max. 2 Saetze): [Einstieg] + Rahmen(Lage-Teilsatz) [+ ein Zusatzsatz].
- COND[lage]: Teilsaetze (kleingeschrieben, mit Verb), passen in JEDEN Rahmen von FRAMES_NOW / FRAMES_DAY.
- Platzhalter: {In_ort}/{in_ort} ("In Ort X"/"in Ort X" oder "Bei dir"/"bei dir"), {temp}, {c} (Teilsatz),
  {C} (Teilsatz mit grossem Anfang), {Tag}/{tag} (morgen/uebermorgen), {feels}, {hoch}, {tief}, {wind},
  {hum}, {p}. Keine Platzhalter ausserhalb dieser Liste (Vertragstest).
- Alles Deutsch, Du-Form, kurz (ADHS/AuDHS: ein bis zwei Saetze).
"""
from __future__ import annotations

# ── Wetterlage je wttr/WWO-weatherCode ─────────────────────────────────────────
CODE_TO_LAGE: dict[int, str] = {
    113: "klar",
    116: "leicht",
    119: "bedeckt", 122: "bedeckt",
    143: "nebel", 248: "nebel", 260: "nebel",
    263: "niesel", 266: "niesel", 281: "niesel", 284: "niesel", 185: "moeglich_regen",
    176: "moeglich_regen", 293: "regen", 296: "regen", 299: "regen", 302: "regen", 311: "regen", 314: "regen",
    305: "starkregen", 308: "starkregen", 359: "starkregen",
    353: "schauer", 356: "schauer",
    200: "moeglich_gewitter", 386: "gewitter", 389: "gewitter", 392: "gewitter", 395: "gewitter",
    179: "moeglich_schnee", 227: "schnee", 230: "schnee", 323: "schnee", 326: "schnee", 329: "schnee",
    332: "schnee", 335: "schnee", 338: "schnee", 368: "schnee", 371: "schnee",
    182: "moeglich_schneeregen", 317: "schneeregen", 320: "schneeregen", 362: "schneeregen", 365: "schneeregen",
    350: "graupel", 374: "graupel", 377: "graupel",
}

# Lagen, bei denen es "regnet" (fuer Ja/Nein auf "Regnet es?") bzw. "schneit"
LAGEN_REGEN = frozenset({"niesel", "regen", "starkregen", "schauer", "gewitter", "schneeregen"})
LAGEN_SCHNEE = frozenset({"schnee", "schneeregen"})
LAGEN_NASS = LAGEN_REGEN | {"schnee", "graupel"}
# "moeglich"-Lagen (wttr: patchy/possible/nearby): nie als sicher formulieren
LAGEN_MOEGLICH_REGEN = frozenset({"moeglich_regen", "moeglich_gewitter", "moeglich_schneeregen"})
LAGEN_MOEGLICH_SCHNEE = frozenset({"moeglich_schnee", "moeglich_schneeregen"})

# ── Teilsaetze je Lage ─────────────────────────────────────────────────────────
COND: dict[str, tuple[str, ...]] = {
    "klar": (
        "die Sonne scheint", "der Himmel ist klar", "es ist sonnig", "es ist strahlend klar",
        "kein Wölkchen am Himmel", "die Sonne zeigt sich", "es ist heiter und klar",
        "der Himmel ist wolkenlos", "die Sonne lacht vom Himmel", "es bleibt schön sonnig",
        "es zeigt sich von der sonnigen Seite", "der Himmel strahlt blau", "es gibt viel Sonne",
        "es ist freundlich und sonnig", "es ist schönstes Sonnenwetter", "der Himmel zeigt sich wolkenfrei",
        "es ist klar und trocken", "die Sonne ist mit dabei",
    ),
    "klar_nacht": (
        "der Himmel ist klar", "es ist eine klare Nacht", "die Nacht ist wolkenlos",
        "es ist sternenklar", "kein Wölkchen am Nachthimmel", "es ist klar und trocken",
        "der Himmel ist wolkenfrei", "die Nacht ist ruhig und klar", "es ist klar am Himmel",
        "die Wolken haben Pause", "der Nachthimmel ist frei", "es ist trocken und klar",
        "keine Wolke in Sicht", "es ist klar, nur eben dunkel", "die Sterne haben freie Bahn",
        "es ist heiter und klar",
    ),
    "leicht": (
        "es ist leicht bewölkt", "ein paar Wolken ziehen vorbei", "der Himmel ist teils bewölkt",
        "Sonne und Wolken wechseln sich ab", "es sind ein paar Wolken am Himmel", "es ist locker bewölkt",
        "die Wolken sind dünn gesät", "es ist heiter bis wolkig", "die Sonne blinzelt durch die Wolken",
        "es ist zum Teil sonnig", "einzelne Wolken ziehen durch", "der Himmel ist ein bisschen verhangen",
        "mal zeigt sich Sonne, mal Wolke", "es ist freundlich mit ein paar Wolken",
        "es ist halb sonnig, halb bewölkt", "die Wolken lassen Platz für die Sonne",
        "es ist ein Mix aus Sonne und Wolken",
    ),
    "leicht_nacht": (
        "es ist leicht bewölkt", "ein paar Wolken ziehen vorbei", "der Himmel ist teils bewölkt",
        "es sind ein paar Wolken am Himmel", "es ist locker bewölkt", "die Wolken sind dünn gesät",
        "einzelne Wolken ziehen durch", "der Himmel ist ein bisschen verhangen",
        "es ist freundlich mit ein paar Wolken", "mal ist der Himmel offen, mal wolkig",
        "die Wolkendecke ist löchrig", "es ist teilweise bewölkt",
    ),
    "bedeckt": (
        "der Himmel ist bedeckt", "es ist grau in grau", "die Wolken hängen tief",
        "es ist komplett bewölkt", "die Wolkendecke ist geschlossen", "es ist trüb",
        "der Himmel ist zugezogen", "es ist grau und wolkenverhangen", "man sieht kaum Himmel",
        "die Sonne hat Pause", "es ist ziemlich grau", "die Wolken sind dicht", "es ist düster",
        "es bleibt grau", "der Himmel ist dicht bewölkt", "es ist einfach grau", "es ist wolkenverhangen",
    ),
    "nebel": (
        "es ist neblig", "Nebel liegt in der Luft", "die Sicht ist schlecht", "es ist diesig",
        "dichter Nebel liegt über allem", "es ist trüb und neblig", "alles liegt im Dunst",
        "die Sicht ist eingeschränkt", "es ist nebelverhangen", "der Nebel hängt tief",
        "man sieht nicht weit", "es ist dunstig", "ein Schleier liegt über allem", "es ist milchig-grau",
        "die Sicht ist mäßig", "der Nebel will nicht weichen", "es ist verhangen und neblig",
    ),
    "niesel": (
        "es nieselt", "leichter Nieselregen fällt", "es sprüht leicht", "es ist nieselig",
        "ein feiner Regen fällt", "es tröpfelt nur", "es fällt feiner Sprühregen",
        "es nieselt vor sich hin", "der Regen ist nur ein Hauch", "es ist feucht und nieselig",
        "kleine Tropfen kommen runter", "es ist ein bisschen Nieselwetter", "es gibt leichten Sprühregen",
        "es ist fein verregnet", "es nieselt leise", "es tröpfelt leicht",
    ),
    "regen": (
        "es regnet", "Regen ist dabei", "es ist nass", "es ist regnerisch", "Regen fällt",
        "der Regen hält an", "es ist verregnet", "es kommt Wasser von oben", "es ist nass und grau",
        "der Himmel schickt Regen", "es regnet vor sich hin", "die Straßen sind nass",
        "es ist Regenwetter", "es regnet immer wieder", "es gibt Regen", "es ist nass da draußen",
        "es regnet leise vor sich hin",
    ),
    "starkregen": (
        "es regnet stark", "es schüttet", "es gießt in Strömen", "es regnet heftig",
        "kräftiger Regen fällt", "es kommt richtig Wasser runter", "es kommt viel Regen runter",
        "der Regen ist heftig", "es schüttet wie aus Eimern", "Starkregen ist dabei",
        "es regnet in Strömen", "es ist richtig nass", "der Regen prasselt", "es gießt wie aus Kübeln",
        "es kommt ordentlich was von oben", "es ist ein kräftiger Guss",
    ),
    "schauer": (
        "es gibt Regenschauer", "Schauer ziehen durch", "es regnet schauerartig",
        "zwischendurch schauert es", "Schauer und Aufhellungen wechseln sich ab",
        "es kommen immer wieder Schauer", "es gibt einzelne Schauer", "es ist wechselhaft mit Schauern",
        "mal Regen, mal Pause", "ein paar Schauer ziehen vorbei", "es schauert zwischendurch",
        "zwischen den Schauern gibt es Pausen", "es ist Schauerwetter", "Schauer sind unterwegs",
        "es regnet in Schüben", "es kommen kurze Regengüsse",
    ),
    "gewitter": (
        "es gewittert", "Gewitter sind unterwegs", "es donnert", "ein Gewitter zieht durch",
        "es ist gewittrig", "Gewitter liegen in der Luft", "es blitzt und donnert",
        "es gibt Regen mit Gewitter", "es ist Gewitterwetter", "dunkle Wolken bringen Gewitter",
        "es grollt am Himmel", "Gewitterwolken ziehen heran", "es ist stürmisch und gewittrig",
        "es kracht am Himmel", "es gewittert ordentlich", "ein Gewitter ist im Anmarsch",
    ),
    "schnee": (
        "es schneit", "Schnee fällt", "es ist winterlich", "Flocken fallen vom Himmel", "es schneit leise",
        "der Schnee rieselt", "es gibt Schneefall", "Schneeflocken tanzen durch die Luft",
        "es ist Schneewetter", "es schneit vor sich hin", "es wird weiß da draußen",
        "es kommt Neuschnee", "Schnee ist unterwegs", "es flockt", "es rieselt Schnee", "es ist schneeig",
    ),
    "schneeregen": (
        "es gibt Schneeregen", "Regen und Schnee mischen sich", "es ist matschig nass",
        "es schneit und regnet gleichzeitig", "Schneeregen fällt", "es ist nasskalt",
        "es ist ein Mix aus Regen und Schnee", "es ist Matschwetter", "Schneematsch ist angesagt",
        "es fällt nasser Schnee", "Regen, durchsetzt mit Schnee, fällt", "es ist ungemütlich nass",
        "es ist nasskalt mit Schneeregen", "es kommt Schneeregen runter",
        "es sind Schneeregenschauer unterwegs", "nasser Schnee fällt vom Himmel",
    ),
    "graupel": (
        "es graupelt", "Graupel fällt", "es gibt Hagel", "Eiskörner fallen vom Himmel",
        "es prasselt Graupel", "es gibt Graupelschauer", "kleine Eiskörner kommen runter",
        "es ist graupelig", "Graupelschauer ziehen durch", "es prasselt eisig", "Graupel ist dabei",
        "es klackert Eis vom Himmel", "es fallen kleine Eiskörner", "es ist eisig und graupelig",
        "es graupelt zwischendurch", "eisiger Graupel kommt runter",
    ),
}

COND["moeglich_regen"] = (
    "es kann zwischendurch regnen", "vereinzelt sind Regentropfen möglich", "ein Schauer ist nicht ausgeschlossen",
    "hier und da kann es nass werden", "es sieht nach vereinzeltem Regen aus", "Regen ist möglich",
    "es bleibt meist trocken, mit einzelnen Tropfen", "gelegentlich tröpfelt es", "ein paar Tropfen können fallen",
    "der Himmel droht mit etwas Regen", "vereinzelt kann es regnen", "es ist wolkig, mit Regen in der Nähe",
    "mal tröpfelt es, mal bleibt es trocken", "ein bisschen Regen liegt in der Luft",
)
COND["moeglich_gewitter"] = (
    "vereinzelt sind Gewitter möglich", "es kann gewittern", "Gewitter sind nicht ausgeschlossen",
    "hier und da kann es donnern", "ein Gewitter kann sich bilden", "Gewitter sind in der Nähe möglich",
    "es sieht nach vereinzelten Gewittern aus", "der Himmel sieht nach Gewitter aus", "ein Gewitter ist möglich",
    "zwischendurch kann es grollen", "mit einzelnen Gewittern ist zu rechnen",
    "es kann vereinzelt blitzen und donnern", "es ist schwül und ein Gewitter ist möglich",
    "Gewitterwolken sind in der Nähe möglich",
)
COND["moeglich_schnee"] = (
    "vereinzelt kann es schneien", "ein paar Flocken sind möglich", "es kann zwischendurch schneien",
    "Schnee ist möglich", "hier und da fallen Flocken", "es kann etwas flocken",
    "mit einzelnen Schneeflocken ist zu rechnen", "der Himmel sieht nach etwas Schnee aus",
    "ein bisschen Schnee liegt in der Luft", "mal flockt es, mal nicht", "es ist wolkig, mit Schnee in der Nähe",
    "ein paar Flocken können fallen",
)
COND["moeglich_schneeregen"] = (
    "vereinzelt ist Schneeregen möglich", "es kann zwischendurch matschig nass werden",
    "Regen mit Schnee ist möglich", "hier und da kann Schneeregen fallen",
    "ein bisschen Schneeregen ist nicht ausgeschlossen", "es kann nasskalt werden", "mal fällt etwas Schneeregen",
    "mit einzelnem Schneeregen ist zu rechnen", "es sieht nach etwas Schneeregen aus",
    "ein paar nasse Flocken können fallen", "es ist wolkig, mit Schneeregen in der Nähe",
    "etwas Schneeregen liegt in der Luft",
)

# ── Rahmen ──────────────────────────────────────────────────────────────────────
# jetzt (Tag 0)
FRAMES_NOW: tuple[str, ...] = (
    "{In_ort} sind es gerade {temp} Grad und {c}.",
    "Gerade sind es {in_ort} {temp} Grad — {c}.",
    "{temp} Grad {in_ort}, und {c}.",
    "Aktuell {temp} Grad {in_ort}; {c}.",
    "{In_ort} aktuell {temp} Grad, {c}.",
    "{C} — {temp} Grad {in_ort}.",
    "Draußen sind es {in_ort} {temp} Grad, und {c}.",
    "Im Moment sind es {temp} Grad {in_ort}, {c}.",
)
# morgen / uebermorgen
FRAMES_DAY: tuple[str, ...] = (
    "{Tag} sind es {in_ort} um die {temp} Grad, und {c}.",
    "{Tag} {in_ort}: etwa {temp} Grad, {c}.",
    "Für {tag} sind {temp} Grad {in_ort} angesagt, und {c}.",
    "{Tag} wird's {in_ort} etwa {temp} Grad, {c}.",
    "Mit etwa {temp} Grad kannst du {tag} {in_ort} rechnen, und {c}.",
    "Auf etwa {temp} Grad kommst du {tag} {in_ort}, und {c}.",
    "{Tag}: {temp} Grad {in_ort} und {c}.",
    "Die Vorhersage für {tag}: {temp} Grad {in_ort}, {c}.",
)

# ── Einstiege (optional, ca. 30 % der Antworten) ──────────────────────────────
EINSTIEG_NEUTRAL: tuple[str, ...] = (
    "Also: ", "Kurz gesagt: ", "Ganz kurz: ", "Schau mal: ", "Okay: ", "Klar: ", "Kurz zum Wetter: ",
    "Hier das Wetter: ", "Passt: ", "Gern: ", "Zack: ", "Auf den Punkt: ",
)
EINSTIEG_MORGEN: tuple[str, ...] = (
    "Guten Morgen — ", "Moin — ", "Schönen Morgen — ", "Moin, moin — ", "Guten Start in den Tag — ",
    "Morgens-Check: ",
)
EINSTIEG_MITTAG: tuple[str, ...] = (
    "Mahlzeit — ", "Schönen Mittag — ", "Mittags-Check: ", "Hallo zur Mittagszeit — ", "Mittagsblick: ",
    "Kurzer Mittagscheck: ",
)
EINSTIEG_ABEND: tuple[str, ...] = (
    "Guten Abend — ", "Schönen Abend — ", "Abend-Check: ", "Feierabend-Check: ", "Abendblick: ",
    "Hallo am Abend — ",
)
EINSTIEG_NACHT: tuple[str, ...] = (
    "Noch wach — ", "Späte Frage — ", "Nachtblick: ", "Spät dran — ", "Nacht-Check: ", "Mitten in der Nacht: ",
)

# ── Ja/Nein-Vorsatz bei "Regnet es?" / "Schneit es?" (kein Satzende, Doppelpunkt) ─
# Neutrale Listen passen zu jedem Tag; *_JETZT nur fuer heute; NEIN_TROCKEN nur wenn es wirklich trocken ist;
# NEIN_ANDERS wenn es etwas anderes Nasses ist (z. B. Schnee bei "Regnet es?").
LEAD_JA: tuple[str, ...] = (
    "Ja: ", "Ja, leider: ", "Jep: ", "Ja, schon: ", "Ja, es ist so: ", "Ja, das stimmt: ",
)
LEAD_JA_JETZT: tuple[str, ...] = (
    "Ja, im Moment: ", "Ja, gerade jetzt: ", "Ja, aktuell: ", "Ja, jetzt gerade: ", "Ja, im Augenblick: ",
    "Ja, so ist es gerade: ",
)
LEAD_NEIN: tuple[str, ...] = (
    "Nein: ", "Nope: ", "Eher nicht: ", "Nein, eher nicht: ", "Nein, danach sieht es nicht aus: ",
    "Nein, nicht wirklich: ",
)
LEAD_NEIN_JETZT: tuple[str, ...] = (
    "Aktuell nicht: ", "Bisher nicht: ", "Nein, im Moment nicht: ", "Gerade nicht: ", "Im Moment nicht: ",
    "Nein, jetzt gerade nicht: ",
)
NEIN_TROCKEN: tuple[str, ...] = (
    "Nein, trocken: ", "Nein, alles trocken: ", "Nein, kein Tropfen: ", "Nein, es bleibt trocken: ",
    "Nein, kein Regen: ", "Nein, trocken bleibt es: ",
)
NEIN_ANDERS: tuple[str, ...] = (
    "Nein, aber: ", "Nicht ganz: ", "Nein, dafür etwas anderes: ", "Das nicht, aber: ", "Nein, eher so: ",
    "Nein, anders: ",
)
LEAD_VIELLEICHT: tuple[str, ...] = (
    "Vielleicht: ", "Möglich: ", "Kann sein: ", "Gut möglich: ", "Nicht ausgeschlossen: ", "Eher unsicher: ",
)

# ── Zusatzsaetze (hoechstens einer, 1 Satz) ───────────────────────────────────
HINT: dict[str, tuple[str, ...]] = {
    "schirm": (
        "Nimm einen Schirm mit.", "Ein Schirm wäre keine schlechte Idee.", "Pack besser einen Schirm ein.",
        "Ohne Schirm wirst du nass.", "Schirm nicht vergessen.", "Regenjacke oder Schirm einpacken.",
        "Ein Schirm ist Gold wert.", "Besser den Schirm griffbereit halten.",
        "Wasserfeste Schuhe wären klug.", "Nimm lieber den Schirm mit.",
    ),
    "starkregen": (
        "Bei dem Regen lieber drinnen bleiben, wenn es geht.", "Regenjacke ist Pflicht.",
        "Mit Schirm allein wird es knapp — nimm eine Regenjacke.", "Plan etwas mehr Zeit für Wege ein.",
        "Wasserdichte Schuhe sind klug.", "Am besten bleibst du, wo es trocken ist.",
    ),
    "gewitter": (
        "Bleib bei Gewitter besser drinnen.", "Unter Bäumen lieber nicht unterstellen.",
        "Sei draußen vorsichtig.", "Drinnen ist es gerade sicherer.", "Draußen lieber vorsichtig sein.",
        "Bei Blitz und Donner besser reingehen.",
    ),
    "frost": (
        "Zieh dich warm an.", "Handschuhe und Mütze sind eine gute Idee.", "Es kann glatt sein — pass auf.",
        "Vorsicht, es kann Glätte geben.", "Dicke Jacke einpacken.", "Mehrere Schichten helfen.",
        "Pass auf Eis auf dem Boden auf.", "Ein heißes Getränk tut gut.",
    ),
    "kalt": (
        "Zieh dir etwas Warmes über.", "Eine dicke Jacke ist sinnvoll.", "Schal nicht vergessen.",
        "Ohne Jacke wird es frisch.", "Eine warme Jacke schadet nicht.",
        "Zieh dich lieber eine Schicht wärmer an.", "Mütze oder Schal helfen.",
    ),
    "sonne": (
        "Sonnenbrille nicht vergessen.", "Die Sonnenbrille darf mit.", "Ein guter Moment für die Sonnenbrille.",
        "Perfekt für einen Spaziergang.", "Sonne tanken lohnt sich.", "Ein bisschen Sonne tut gut.",
        "Genieß das Licht.",
    ),
    "heiss": (
        "Trink genug Wasser.", "Sonnencreme nicht vergessen.", "Such dir Schatten, wenn es geht.",
        "Leichte Kleidung ist angesagt.", "Denk an genug Flüssigkeit.", "Mittags lieber im Schatten bleiben.",
        "Bleib kühl und trink viel.",
    ),
    "nebel": (
        "Fahr vorsichtig, die Sicht ist schlecht.", "Mit Licht unterwegs sein hilft bei Nebel.",
        "Vorsicht im Verkehr.", "Plane etwas mehr Zeit ein.", "Sichtweite beachten.",
        "Auf der Straße besser langsam.",
    ),
    "schnee": (
        "Zieh dir feste Schuhe an.", "Es kann rutschig werden.", "Mütze und Handschuhe einpacken.",
        "Plane mehr Zeit für Wege ein.", "Vorsicht, es kann glatt werden.", "Warm anziehen lohnt sich.",
        "Ein guter Moment für heißen Tee.",
    ),
    "schneeregen": (
        "Wasserfeste Schuhe helfen.", "Es wird matschig — nimm feste Schuhe.",
        "Schirm und warme Jacke sind sinnvoll.", "Pass auf, es kann glatt werden.",
        "Nasskalt — warm einpacken.", "Plane mehr Zeit ein.",
    ),
    "graupel": (
        "Ein Dach über dem Kopf ist gerade gut.", "Lieber kurz unterstellen.", "Mütze oder Kapuze hilft.",
        "Pass auf, es kann rutschig werden.", "Zieh etwas Wetterfestes an.", "Mit Kapuze bist du gut dran.",
    ),
}

WIND_STARK: tuple[str, ...] = (
    "Dazu weht ein kräftiger Wind.", "Es ist windig, rund {wind} km/h.", "Der Wind legt zu — etwa {wind} km/h.",
    "Dazu kommt Wind mit etwa {wind} km/h.", "Eine Jacke gegen den Wind wäre gut.", "Es pfeift ein bisschen.",
)
WIND_STURM: tuple[str, ...] = (
    "Es stürmt — rund {wind} km/h.", "Der Wind ist sehr stark, etwa {wind} km/h.", "Vorsicht, es ist stürmisch.",
    "Bei dem Sturm lässt du draußen besser nichts Lockeres liegen.", "Halte dich von Bäumen fern.",
    "Es ist ziemlich stürmisch draußen.",
)
FEELS_KAELTER: tuple[str, ...] = (
    "Gefühlt sind es nur {feels} Grad.", "Es fühlt sich kälter an, etwa {feels} Grad.", "Gefühlt eher {feels} Grad.",
    "Gefühlt ist es frischer: {feels} Grad.", "Die Luft fühlt sich nach {feels} Grad an.",
    "Gefühlt nur {feels} Grad.",
)
FEELS_WAERMER: tuple[str, ...] = (
    "Gefühlt sind es {feels} Grad.", "Es fühlt sich wärmer an, etwa {feels} Grad.", "Gefühlt eher {feels} Grad.",
    "Gefühlt ist es milder: {feels} Grad.", "Die Luft fühlt sich nach {feels} Grad an.",
    "Gefühlt schon {feels} Grad.",
)
RANGE_HEUTE: tuple[str, ...] = (
    "Heute liegt es zwischen {tief} und {hoch} Grad.", "Tageshoch {hoch} Grad, Tiefstwert {tief}.",
    "Im Lauf des Tages geht es von {tief} bis {hoch} Grad.", "Maximal werden es {hoch} Grad.",
    "Mindestens {tief}, höchstens {hoch} Grad.", "Die Spanne heute: {tief} bis {hoch} Grad.",
    "Nachts kühlt es bis auf {tief} Grad ab.", "Das Tageshoch liegt bei {hoch} Grad.",
)
RANGE_TAG: tuple[str, ...] = (
    "{Tag} liegt es zwischen {tief} und {hoch} Grad.", "Zwischen {tief} und {hoch} Grad pendelt es {tag}.",
    "{Tag} ist bei {hoch} Grad Schluss nach oben.", "Tiefstwert {tief}, Höchstwert {hoch} Grad.",
    "Im Tagesverlauf {tief} bis {hoch} Grad.", "Das Tageshoch ist {tag} {hoch} Grad.",
    "Nachts geht es auf {tief} Grad runter.", "Die Spanne {tag}: {tief} bis {hoch} Grad.",
)
BAND: dict[str, tuple[str, ...]] = {
    "frost": (
        "Das ist frostig.", "Es ist Frostwetter.", "Eiskalt.", "Das ist richtig kalt.",
        "Frost liegt in der Luft.", "Brrr, das ist eisig.",
    ),
    "kalt": (
        "Das ist ziemlich kalt.", "Eher frisch bis kalt.", "Frisch ist es.", "Kalt, aber aushaltbar.",
        "Es ist spürbar kalt.", "Eher ungemütlich kalt.",
    ),
    "kuehl": (
        "Das ist eher kühl.", "Etwas frisch.", "Ein kühler Eindruck.", "Eher kühl, aber okay.",
        "Leicht frisch.", "Kühl, aber nicht kalt.",
    ),
    "mild": (
        "Das ist mild.", "Angenehm mild.", "Gut auszuhalten.", "Ziemlich angenehm.", "Mild und angenehm.",
        "Das passt.",
    ),
    "warm": (
        "Das ist schön warm.", "Angenehm warm.", "Richtig angenehm.", "Warm, aber nicht zu heiß.",
        "Fast schon sommerlich.", "Eine schöne Temperatur.",
    ),
    "heiss": (
        "Das ist heiß.", "Ziemlich warm.", "Schon richtig heiß.", "Es wird sommerlich heiß.",
        "Hitze liegt in der Luft.", "Das ist ordentlich warm.",
    ),
    "sehr_heiss": (
        "Das ist sehr heiß.", "Richtig brütend.", "Eine echte Hitze.", "Ziemlich drückend heiß.",
        "Extrem warm.", "Das ist Hitze pur.",
    ),
}
SCHWUEL: tuple[str, ...] = (
    "Dazu ist es schwül.", "Die Luft ist drückend feucht.", "Es ist ziemlich schwül.",
    "Es ist schwül, {hum} Prozent Luftfeuchte.", "Die Luft steht, schwül und feucht.", "Schwül ist es auch.",
)
FEUCHT: tuple[str, ...] = (
    "Die Luft ist sehr feucht.", "Es ist klamm und feucht.", "Hohe Luftfeuchte, rund {hum} Prozent.",
    "Es fühlt sich klamm an.", "Feucht ist es auch.", "Die Luft ist ziemlich nass.",
)
REGENCHANCE: tuple[str, ...] = (
    "Später kann es regnen, bis zu {p} Prozent.", "Mit {p} Prozent Regenchance — Schirm griffbereit.",
    "Es kann später nass werden.", "Zwischendurch ist Regen möglich.",
    "Die Regenwahrscheinlichkeit steigt auf {p} Prozent.", "Behalte den Himmel im Auge, Regen ist möglich.",
)
WECHSELHAFT: tuple[str, ...] = (
    "Es bleibt wechselhaft.", "Das Wetter wechselt ein paarmal.", "Mal so, mal so — wechselhaft.",
    "Der Himmel ist launisch.", "Rechne mit einem Wechselspiel aus Sonne, Wolken und Regen.",
    "Es bleibt unbeständig.", "Das Wetter ist ziemlich launisch.", "Es ist ein buntes Wechselspiel.",
)

# Erlaubte Platzhalter (Vertragstest)
PLACEHOLDERS = frozenset({
    "In_ort", "in_ort", "temp", "c", "C", "Tag", "tag", "feels", "hoch", "tief", "wind", "hum", "p",
})
