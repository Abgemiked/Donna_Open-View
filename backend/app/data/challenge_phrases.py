"""German liveness-challenge phrases for Voice-Auth (Phase 3).

Requirements:
- >= 50 unique phrases
- 4-8 words each
- Natural German sentences that are easy to speak aloud
- Diverse enough to avoid easy pre-recording of all phrases
"""
from __future__ import annotations

PHRASES: list[str] = [
    # --- Alltag ---
    "Die Sonne scheint heute besonders hell.",
    "Ich trinke jeden Morgen eine Tasse Kaffee.",
    "Das Wetter wird morgen wieder besser.",
    "Mein Lieblingsbuch liegt auf dem Tisch.",
    "Die Katze schläft auf dem Sofa.",
    "Heute ist ein guter Tag zum Laufen.",
    "Das Frühstück war sehr lecker heute.",
    "Ich lese gerne Bücher am Abend.",
    "Der Zug fährt um acht Uhr ab.",
    "Wir treffen uns im Park um drei.",
    # --- Natur ---
    "Die Vögel singen im alten Baum.",
    "Im Herbst fallen die Blätter vom Baum.",
    "Der Fluss fließt ruhig durch das Tal.",
    "Die Berge sind mit Schnee bedeckt.",
    "Der Mond leuchtet hell in der Nacht.",
    "Ein Regenbogen erscheint nach dem Regen.",
    "Die Blumen blühen im Frühling wieder.",
    "Das Meer rauscht sanft an den Strand.",
    "Im Garten wachsen viele bunte Blumen.",
    "Die Wolken ziehen langsam über den Himmel.",
    # --- Technik & Alltag ---
    "Mein Telefon braucht wieder einen neuen Akku.",
    "Das Internet ist heute leider sehr langsam.",
    "Ich muss noch schnell eine Nachricht schreiben.",
    "Der Computer startet nach dem Update neu.",
    "Die App funktioniert endlich wieder einwandfrei.",
    "Ich lade mein Handy über Nacht auf.",
    "Das Passwort muss regelmäßig geändert werden.",
    "Meine Kopfhörer haben einen guten Klang.",
    "Der Drucker hat kein Papier mehr drin.",
    "Das Smart-Home-System funktioniert sehr zuverlässig.",
    # --- Essen & Trinken ---
    "Heute Abend koche ich frische Pasta selbst.",
    "Der Kuchen ist für die ganze Familie.",
    "Frisches Brot vom Bäcker schmeckt am besten.",
    "Ich bevorzuge Mineralwasser ohne Kohlensäure.",
    "Die Suppe ist noch zu heiß zum Essen.",
    "Ein gutes Frühstück gibt mir Energie.",
    "Der Salat schmeckt mit Olivenöl am besten.",
    "Wir bestellen heute Abend eine Pizza.",
    "Das Eis am Strand war sehr erfrischend.",
    "Die Äpfel vom Markt sind besonders süß.",
    # --- Bewegung & Sport ---
    "Ich gehe jeden Tag eine Stunde spazieren.",
    "Das Fahrrad muss noch repariert werden.",
    "Schwimmen ist mein liebstes Hobby im Sommer.",
    "Der Marathon beginnt um neun Uhr morgens.",
    "Yoga hilft mir beim Abschalten nach der Arbeit.",
    "Ich trainiere dreimal pro Woche im Fitnessstudio.",
    "Der Weg zum Gipfel war sehr anstrengend.",
    "Laufen im Regen macht mir nichts aus.",
    # --- Reise ---
    "Der Zug nach Berlin fährt pünktlich ab.",
    "Wir fahren nächsten Sommer ans Meer.",
    "Das Hotel war sehr gemütlich und sauber.",
    "Die Reise hat uns allen viel Spaß gemacht.",
    "Am Flughafen gibt es immer viele Menschen.",
    "Ich packe meinen Koffer immer am Abend vorher.",
    # --- Allgemein / neutral ---
    "Die Bibliothek öffnet um neun Uhr morgens.",
    "Ein gutes Buch ist immer eine gute Idee.",
    "Musik macht jeden Moment schöner und leichter.",
    "Das Gespräch hat mir sehr gut gefallen.",
    "Ich freue mich auf das Wochenende.",
    "Die Aufgabe war einfacher als gedacht.",
    "Wir lösen das Problem gemeinsam und schnell.",
]

assert len(PHRASES) >= 50, f"Need at least 50 phrases, got {len(PHRASES)}"
