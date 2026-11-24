# Plan: Automatische Zonen und Profilwerte

Ziel:            Max-Puls, Ruhepuls, Schwellenpuls und die fünf Pulszonen werden aus den Trainings automatisch nachgeführt und lassen sich je Wert von Hand überschreiben.
Nicht-Ziele:     Keine Neuberechnung alter Trainings. Kein Labortest. Keine Änderung an WHOOP-Abruf, Kalorien-Formel oder Design. Kein Push/Deploy ohne Semirs Ok.
Branch:          feature/auto-zonen

## Fachliche Regeln
- **Max-Puls (auto):** nur Anstieg. Neuer Wert = höchster Puls, der in einem Training mindestens 3 Messwerte in Folge gehalten wurde (Ausreißerfilter), Plausibilität 150–225. Sinkt nie automatisch.
- **Ruhepuls (auto):** WHOOP-Ø der letzten 7 Tage (besteht schon). Ohne WHOOP: niedrigster 30-s-Durchschnitt der letzten 14 Tage aus Trainings-Pulsreihen (Ersatz, nur wenn >= 3 Trainings). Plausibilität 30–110.
- **Schwellenpuls (auto, neu `thr`):** Bestes 20-min-Durchschnittspuls-Fenster je Training der letzten 90 Tage (Training >= 25 min), Maximum davon × 0,95. Nur gültig bei >= 3 geeigneten Trainings und 0,75·Max <= thr <= 0,95·Max. Sonst Ersatz `rest + 0,85·(max − rest)`. Änderung pro Update höchstens ±3 bpm (glatt).
- **Zonengrenzen in bpm** (b1..b4, Z1 < b1 … Z5 >= b4):
  - b3 = round(0,95·thr), b4 = round(1,03·thr)
  - b1 = round(rest + 0,55·(b3 − rest)), b2 = round(rest + 0,80·(b3 − rest))
  - Muss streng aufsteigend sein, b4 < max; sonst auf Ersatzwerte klemmen.
- **Manuell:** je Wert (`max`, `rest`, `thr`) ein Auto/Manuell-Schalter; zusätzlich Zonengrenzen manuell (`zbManual: [b1..b4]`) mit „Zonen auf Auto“. Manuelle Werte werden nie überschrieben.
- **Profilfelder neu:** `thr`, `auto: {max, rest, thr, zones}` (Standard alle true), `zb` (aktuelle Grenzen, berechnet oder manuell), `hist` (max. 60 Einträge `{d, rest, max, thr}`).
- **Alte Trainings:** `s.prof` ohne `thr`/`zb` => alte feste Prozentgrenzen (60/70/80/90 % Max). Gespeicherte Zonenzeiten bleiben unverändert. Neue Trainings speichern `thr` und `zb` im `prof`-Snapshot.
- **Auslöser:** nach Speichern/Import eines Trainings und nach WHOOP-Abruf. Bei wesentlicher Änderung (Max, Ruhepuls oder Schwelle >= 2 bpm, oder eine Zonengrenze >= 3 bpm) kurzer Hinweis „Ruhepuls 58 → 55, Zonen angepasst“ (Toast + Eintrag im Profil).

Schritte:
1. Zonen-Kern umbauen
   - Rolle: Entwickler, sonnet
   - Dateien ändern: index.html (Abschnitt Core: `zoneOf`, `zoneGauge`, `zoneGaugeText`, `analyzeSeries`, Live-Aufrufe, Live-Diagramm-Bänder, Sessions-Diagramm, Coach-Text Zeile „Zonen:“), Demo-Modus
   - Nicht anfassen: android-src/, whoop.html, Sync-Logik außer dass `profile.json` die neuen Felder mitträgt
   - Abhängigkeit: zuerst
2. Auto-Logik und Profil-Oberfläche
   - Rolle: Entwickler, sonnet
   - Dateien ändern: index.html (neue Funktionen `autoProfile`, `zoneBoundsFor`, Profil-Karte mit Auto/Manuell-Schaltern, Zonen-Tabelle in bpm, Verlauf), sw.js + `APP_VERSION` hochzählen
   - Abhängigkeit: nach Schritt 1 (gleiche Datei, daher nacheinander vom selben Entwickler)
3. Review
   - Rolle: Reviewer, opus; ändert nichts
4. Verifizieren
   - Orchestrator: App starten (Preview „trainings-app“), Demo-Modus, Konsole, Screenshot 390x844

Fertig-Kriterien:
- JS-Syntax ok (Script extrahieren, `node --check`; sonst Konsole ohne Fehler).
- Profil zeigt je Wert Auto/Manuell; Umschalten auf Manuell hält den Wert nach Neuladen.
- Aus Demo-Daten entstehen plausible Zonen in bpm, aufsteigend, Z5 < Max.
- Alte Trainings (ohne `thr` im `prof`) zeigen unveränderte Zonenzeiten.
- Live-Anzeige, Zonen-Säulen und Diagramme nutzen die neuen Grenzen.
- Hinweis erscheint bei wesentlicher Änderung.
- `APP_VERSION` und `CACHE` gleich und hochgezählt.

Risiken:
- Viele Aufrufer von `zoneOf(hr, p.max)` => Signatur abwärtskompatibel (Zahl = alte Prozentlogik, Objekt = neue Grenzen).
- Zonen springen zwischen Trainings => Glättung ±3 bpm, Hinweis zeigt Änderung.
- Falsche Messspitze hebt Max-Puls => 3-Werte-Filter + Plausibilität + manuell überschreibbar.
- Sync-Konflikt `profile.json` => neue Felder nur additiv.
