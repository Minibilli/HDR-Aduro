# CLAUDE.md – HDR Aduro

Inoffizielle Android-App (Kotlin + WebView-Oberfläche), die einen Aduro-Hybridofen (H1 getestet) direkt im Heimnetz über das NBE-Protokoll steuert. Optional mit Homematic IP: Raumsteuerung und Heizkörperventile.

**Diese App kann ein Feuer starten.** Jede Änderung an der Start-, Stopp- oder Automatiklogik ist sicherheitsrelevant. Im Zweifel nichts automatisch starten.

## Aufbau

| Datei | Aufgabe |
|---|---|
| `app/src/main/java/de/ofen/app/Nbe.kt` | NBE-Protokoll: UDP, Port 8483, Quellport 1901. Frame-Aufbau und Parser nach der Python-Bibliothek *pyduro* |
| `Stove.kt` | Kernlogik: Konfiguration (SharedPreferences „ofen“, JSON unter `config`), Status, Automatiken (Raumsteuerung, Auto-Heizstufe, Pellets früher stoppen/Neustart, Heizkörper, Holz-Warnung, Füllstand), Hauptschalter, Expertenmodus, Handbetrieb |
| `Api.kt` | interne „REST“-Schicht (`GET`/`POST` über Pfadnamen); die Oberfläche ruft nur diese auf |
| `MainActivity.kt` | WebView, lädt `assets/index.html`; JS-Brücke `window.Ofen` (`request`, Foto, QR-Scan, Vibration …) |
| `OfenService.kt` | Foreground-Service: Status-Abfrage alle 10–30 s, Handbetrieb-Tick alle 2 s |
| `Hmip.kt` | Homematic IP über die Cloud (inoffizielle API wie homematicip-rest-api) |
| `Notifier.kt`, `BootReceiver.kt` | Benachrichtigungen, Start nach Neustart oder Update |
| `assets/index.html` | gesamte Oberfläche (HTML/CSS/JS in einer Datei) |

Ofen-Zustände (`state`) und ihre Texte stehen in `Stove.STATE_TEXT`. Wichtig sind `BURNING`, `PELLET_RUN` (5, 6), Holzbetrieb = 9, „Per Taste gestoppt“ = 14, „Durch Timer gestoppt“ = 23.

## Sicherheitsregeln (nicht verletzen)

1. **Expertenmodus serverseitig prüfen.** Alles, was den Ofen selbsttätig startet, Bauteile von Hand schaltet oder Ofenwerte schreibt, muss in `Api.kt` über `Stove.requireExpert()` bzw. `EXPERT_ONLY` laufen, nicht nur in der Oberfläche ausgeblendet werden.
2. **„Ausschalten“ (`halted`) gewinnt immer.** Solange `halted` gesetzt ist, darf keine Automatik starten. Neue Automatiken müssen `halted`, Holzbetrieb (`state == "9"`, `man.wood`, `early.stopped`) und Handbetrieb (`man.active`) prüfen.
3. **Schutzzeiten einhalten:** mindestens 60 min Laufzeit und 30 min Pause bei der Raumsteuerung, mindestens 10 min zwischen Heizstufenwechseln. Die Zeitstempel liegen in den SharedPreferences `ofen_zeiten` (`Stove.timeGet`/`timeSet`), damit sie einen App-Neustart überstehen. Neue Schutzzeiten genauso speichern, nie nur im Arbeitsspeicher.
4. **Nach Alarmen nie automatisch neu starten** (z. B. 13 „Zündung fehlgeschlagen“, 20 „Kein Feuer“). Automatische Starts nur aus 14, 23 und 0 („Warten“).
5. **Handbetrieb:** nur benannte Ausgänge, immer mit Zeitlimit (max. 240 min). Ohne Limit nur das Frischluftgebläse, bei brennendem Ofen kein Handbetrieb.
6. **Nur passende Antworten verwerten.** `Nbe.send` akzeptiert nur Antworten mit gleicher Seriennummer, Funktion und Sequenznummer (`Nbe.matches`). Diese Prüfung nicht entfernen.
7. **Veraltete Daten = keine Entscheidung.** Homematic-Werte älter als 15 min pausieren die Raumsteuerung. Neue Logik genauso behandeln.
8. **Beim Ausschalten, Beenden oder Expertenmodus aus** alles zurückstellen: Handbetrieb aus, Heizkörper wieder auf, gemerkte Ofen-Zieltemperatur (`hm_prev_boiler`) zurück. `hm_prev_boiler` erst nach erfolgreichem Senden löschen, sonst geht der Wert verloren.
9. **Zugangsdaten** (Ofen-PIN, Homematic-Token) nie loggen, nie in Fehlermeldungen oder Benachrichtigungen ausgeben, nie an Dritte senden. `GET config` liefert nur `has_pin`, nicht die PIN.
10. **Oberfläche:** Werte aus Ofen oder Homematic nur über `esc()` bzw. `textContent` einfügen, weil die JS-Brücke den Ofen steuern kann.

## Bauen

- Kein Gradle-Wrapper im Repo. Gebaut wird über GitHub Actions (`.github/workflows/build.yml`, Gradle 8.9, JDK 17): `gradle :app:assembleRelease`.
- Push auf `main` aktualisiert das Release `latest` (die APK, die Nutzer installieren). **Nicht ungetestet auf `main` pushen**, zum Ausprobieren den Branch `raspi` oder einen eigenen Branch nehmen.
- Signatur: `app/release.keystore`, Passwort nur als Secret `OFEN_KEY_PASSWORD`. Keystore nicht ersetzen, sonst lassen sich Updates nicht mehr über die alte Version installieren.
- minSdk 26, targetSdk 34.

## Arbeitsweise

- Sprache in Code-Kommentaren, Oberfläche und Commits: **Deutsch**.
- Stil beibehalten: wenige Dateien, keine neuen Bibliotheken ohne Grund, `org.json` statt Serialisierungs-Frameworks.
- Logik in `Stove.kt` nur testbar ändern: reine Entscheidungen (z. B. „starten ja/nein“, Heizstufe) möglichst als Funktionen ohne Netzwerkzugriff schreiben, damit sie sich ohne echten Ofen testen lassen.
- Getestet wurde nur mit einem Aduro H1. Keine Annahmen über andere Modelle in den Code einbauen.
