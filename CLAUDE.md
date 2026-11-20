# Trainings-App (Semir, K1-/Kickbox-Kämpfer)

Herzfrequenz-Trainings-App für den Garmin HRM 600 Brustgurt. Sprache der App und der Antworten: **Deutsch**, locker und direkt. Semir ist kein Programmierer: Änderungen selbst machen, testen, pushen – ihm nur kurz sagen, was neu ist und dass er die App neu öffnen soll.

## Aufbau
- **`index.html`** – die komplette App (HTML + CSS + JS in einer Datei, ~3.300 Zeilen, kein Build-Schritt, kein Framework). PWA.
- **`sw.js`** – Service Worker (network-first, `cache:'no-cache'`, fremde Origins außer Fonts werden ignoriert).
- **`manifest.webmanifest`**, **`icons/`**, **`whoop.html`** (OAuth-Redirect → `trainingsapp://whoop?...`).
- **`android-src/`** – Capacitor-8-Hülle für die APK (lädt die Pages-URL per `server.url`). Eigenes Java-Plugin `HrNative` (`java/HrPlugin.java`, `java/TrainingService.java`, `java/SuppAlarm.java`): Foreground-Service mit BLE-GATT, WakeLock, Piepen, Deep-Link `trainingsapp://`, Supplement-Erinnerungen (`suppSchedule`, `suppTaken`, `notifPerm`), Kamera-Recht für den Barcode-Scan. `patch.py` patcht Manifest/Gradle beim Build.
- **`.github/workflows/apk.yml`** – baut die APK bei Push auf `android-src/**` (Secret `KEYSTORE_PASS`), Release `apk-N`. Download: https://github.com/semkoq/trainings-app/releases/latest/download/trainings-app.apk

## Deploy
- GitHub Pages: https://semkoq.github.io/trainings-app/ – jeder Push auf `main` ist live.
- **Bei jeder Änderung an `index.html` die Version hochzählen**, sonst lädt das Handy die alte Version:
  - `index.html`: `APP_VERSION = '2026.11.20-1637'`
  - `sw.js`: `const CACHE = 'trainings-app-2026.11.20-1637'`
  - Format `JJJJ.MM.TT-HHMM` (UTC), beide gleich.
- Web-Änderungen brauchen keine neue APK (die APK lädt die Seite). Nur Änderungen in `android-src/` brauchen einen APK-Build.

## Abschnitte in `index.html` (Kommentar-Marker `// =====`)
Core-Parser/Analyse (`toSeries`, `analyzeSeries`, Zonen, TRIMP, Belastungsphasen, `hrr60`) · Speicher (IndexedDB: Stores `sessions`, `kv`) · Upload (.fit/.tcx/.gpx/.csv) · Session-Ansicht · Verlauf · Live-Modus (Web Bluetooth 0x180D/0x2A37, RR-Intervalle, HRV) · Intervall-Timer (Piepen) · **Sync** ins private Daten-Repo (GitHub Contents API, Fine-grained PAT) · Leistung (CTL 42 T / ATL 7 T / TSB) · Wochenübersicht · Bewertung (RPE 1–10, Gefühl 1–5) · Native (APK-Bridge `HN()`) · **WHOOP** (OAuth, `/v2/recovery`, `/activity/sleep`, `/cycle`, `/user/measurement/body`; Zyklus-Tag = Start + 8 h; nur EIN Token-Refresh gleichzeitig) · Bereitschaft heute · **Analyse** (Kategorien `anaCat`, Hauptteil-Erkennung `mainPart`, Vergleich, Coach-Feedback) · Vom Claude-Speicher laden · **Cut** (Gewicht, Kalorienziel Mifflin × Alltag + Training − Defizit, kalibriert an WHOOP, Essen inkl. Open Food Facts) · **Coach** (zeigt `reports/*.md` aus dem Daten-Repo) · **Fight Camp** (Countdown, Kampfgewicht) · **Supplements** (`SP`, kv `supps`: Barcode-Scan mit BarcodeDetector bzw. ZXing von jsDelivr, Produkt über Open Food/Products/Beauty Facts, Abhaken, Reichweite aus echtem Verbrauch, Erinnerungen nativ über `SuppAlarm`) · **Ausdauer-Stufe** (VO2max nach Uth, Normtabellen, angepasst an `hrr60` und CTL) · **Körper** (`BD`, kv `body`: PDF-Import mit pdf.js, abgestimmt auf SCANECA-3D-Scan – Werte, Umfänge, Arm-/Beinmaße `ext`, Haltung `post`; Gewicht geht in `CUT.weights`) · **Überblick als Widgets** (`OV`, kv `ovLayout2`: Reihenfolge, groß/klein, ausgeblendet; Bearbeiten per langem Drücken) · Sicherung · App-Update.

Navigation: `MAIN = ['overview','start','history','analyse','cut','supps']`, `goMain(v)`, `showView(v)`, Wischgesten. Einstellungen über ⚙ (`view-profile`).

## Daten-Repo
Privates Repo **`semkoq/trainings-daten`** – die App lädt dorthin hoch: `sessions/JJJJ/*.json`, `profile.json`, `whoop.json`, `fight.json`, `cut.json`, `supps.json`, `body.json`; Claude schreibt `reports/*.md` (Wochen-/Monatsberichte). Datenformat steht in der `CLAUDE.md` dort. Die App pusht laufend – beim eigenen Push dorthin vorher pullen/rebasen.

## Konventionen
- Stil: kompakte Einzeiler-Funktionen, `$()` = querySelector, `esc()` für HTML, `toast()`, `kvGet/kvSet`, `store.saveSession`.
- Design „Pulsmonitor“ (Stand 18.11.2026): die fünf Pulszonen-Farben `--z1`…`--z5` sind das Erkennungszeichen (Pulskurven in Zonenfarbe, Zonen-Streifen `zoneStrip()` in Verlauf und Training, Belastung als Zonen-Punkt). Schriften: Zahlen `--f-disp` (Chakra Petch), Beschriftungen `--f-mono` (IBM Plex Mono), Text `--f-body` (IBM Plex Sans). Akzent Pink-Rot `--acc`. **Keine Emojis.** Seit 20.11.2026 **immer schwarz** (Block „Schwarz 2026-11“ überschreibt die Tokens, auch bei hellem Handy) – Farben als Tokens am Ende des `<style>`-Blocks (spätere Regeln gewinnen).
- Farben in JS/Canvas nie fest eintragen: `C.mut`, `C.txt`, `C.acc`, `C.good`, `C.bad`, `C.warn`, `C.grid*` und `ZONE_COLORS` kommen aus den CSS-Variablen (`readTheme()`), Canvas-Schrift `MONO`.
- Zahleneingaben als `type="text" inputmode="decimal"` (deutsches Komma), mit `pnum()` parsen.
- Nach Änderungen: JS-Syntax prüfen (Script-Block extrahieren, `node --check`; ohne Node: Seite lokal laden und Konsole auf Fehler prüfen), im Browser im Handy-Format (390×844) hell und dunkel testen, Screenshot anschauen.

## Sicherheit
Keine Tokens, Passwörter oder Secrets in den Code. GitHub-PAT, WHOOP-Client-ID/Secret und der Keystore-Pass werden nur vom Nutzer in der App bzw. als GitHub-Secret eingegeben.
