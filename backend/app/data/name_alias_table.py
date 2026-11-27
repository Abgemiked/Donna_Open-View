"""Namens-Tabelle fuer die Spracherkennung (Abnahme #155): richtige Schreibweise -> falsche Varianten.

Gross-/Kleinschreibung egal, nur ganze Woerter. Weitere Namen hier ergaenzen (als Python-Datei, damit der isolierte
Eval-Worker sie als Code lesen darf). Zusaetzlich liest der Server die gleiche Struktur als JSON aus der Datei in
DONNA_NAME_ALIASES_EXTRA (ausserhalb des Images, fuer Pflege ohne Deploy).
"""
ALIASES: dict[str, list[str]] = {
    "Ämi-Li": ["ämi-li", "ami-li", "aemi-li", "amy-li", "emi-li", "ämili", "aemili", "amili", "amyli"],
    "Ämi": ["ämi", "amy", "ami", "aemi", "emi", "ämie", "amie", "aemie", "ämmi", "ammi", "aemmi", "ämy", "aemy"],
    "Yannik": ["yannik", "janik", "jannick", "yannick", "jannik", "yanik", "janick", "yanick", "jannic",
               "yannic", "janic", "yanic"],
}
