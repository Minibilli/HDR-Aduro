# HDR Aduro

Inoffizielle Android-App zur Steuerung eines Aduro-Hybrid-Ofens im Heimnetz – direkt über das NBE-Protokoll (UDP), ohne Cloud. Optional mit Homematic IP: Raumtemperaturen anzeigen und den Ofen nach Raumtemperatur starten und stoppen.

> ⚠️ **Wichtiger Sicherheitshinweis – bitte vor der Nutzung lesen**
>
> Diese App kann einen Ofen **automatisch und aus der Ferne starten** (Raumsteuerung, automatischer Pelletstart, Zugriff per VPN von unterwegs). Ein Ofen ist ein Feuer.
>
> - Starte den Ofen nur, wenn du sicher bist, dass Brennraum, Tür, Aschekasten und Umgebung in Ordnung sind und nichts Brennbares in der Nähe liegt.
> - Nutze Fernstart und Automatik nur, wenn das auch nach den Vorgaben des Herstellers und deines Schornsteinfegers zulässig ist.
> - Die App ersetzt **keine** Aufsicht, keinen Rauchmelder und keinen CO-Melder.
> - Verbindungsabbrüche, falsche Messwerte oder Fehler in der App können dazu führen, dass der Ofen anders reagiert als erwartet.
>
> **Die Nutzung erfolgt vollständig auf eigene Gefahr.**

> **Kein Produkt von Aduro.** HDR Aduro ist ein privates Projekt und steht in keiner Verbindung zu Aduro A/S, NBE oder eQ-3. „Aduro“ und „Homematic IP“ sind Marken ihrer jeweiligen Inhaber und werden nur genannt, um zu beschreiben, mit welchen Geräten die App zusammenarbeitet.

## Expertenmodus

Sicherheitsrelevante Funktionen sind ab Werk **gesperrt** und lassen sich nur unter *Einstellungen → Expertenmodus* freischalten – nach Bestätigung eines Sicherheitshinweises und eines Haftungsausschlusses:

- Gebläse und Bauteile (Schnecke, Zünder …) von Hand schalten, Ausgänge benennen
- alle Werte der Ofensteuerung lesen und ändern
- Holzverbrennung Temperatur, Pellets früher stoppen, automatischer Pelletstart
- Raumsteuerung mit Homematic IP (startet den Ofen selbsttätig)

Ohne Expertenmodus bleiben Anzeige, Start/Ausschalten, Heizstufe, Zieltemperatur, Pellet-Füllstand, Holz-Warnung, Räume und Heizkörper-Steuerung nutzbar.

## Haftungsausschluss

Die App wird kostenlos und ohne Gewähr für Funktion, Richtigkeit der Anzeigen oder Verfügbarkeit bereitgestellt. Die Nutzung erfolgt auf eigene Gefahr. Soweit gesetzlich zulässig, wird keine Haftung übernommen für Schäden an Ofen, Gebäude oder sonstigem Eigentum, für Folgeschäden sowie für den Verlust von Garantie- oder Gewährleistungsansprüchen gegenüber dem Hersteller. Unberührt bleibt die Haftung für Vorsatz und grobe Fahrlässigkeit sowie für Schäden an Leben, Körper oder Gesundheit. Siehe auch [LICENSE](LICENSE).

## Welche Öfen?

Gedacht für die **Aduro-Hybridöfen H1, H2, H3 Lux, H4 Lux, H5 und H6 Lux**. Sie haben alle eine Steuerung von **NBE** mit eingebautem WLAN (Aufkleber „NBE – powered by nature“ mit Seriennummer und Passwort; beim Aduro H1 innen in der Tür zum Pelletbehälter).

**Getestet wurde die App nur mit einem Aduro H1.** Bei den anderen Modellen ist sie sehr wahrscheinlich nutzbar, aber ungeprüft. NBE-Pelletkessel und -brenner (RTB, BS+, StokerCloud) sprechen ein ähnliches Protokoll, werden aber nicht unterstützt.

## Funktionen

- Ofen starten und stoppen, Heizstufe einstellen, Zustand und Fehler anzeigen
- Gebläse und Ausgänge steuern
- Hintergrund-Überwachung mit Warnungen (z. B. fallende Rauchgastemperatur im Holzbetrieb)
- Automatischer Pelletstart nach Temperatur
- Homematic IP (optional): Räume, Raumtemperaturen, Raumsteuerung mit Zieltemperatur
- Von unterwegs über eine WireGuard-VPN-App ins Heimnetz

## Wandgerät mit Touchscreen (HDR Aduro Home Raspi)

Dieselbe Oberfläche auf einem Raspberry Pi mit 7"-Touchscreen, der rund um die Uhr läuft. Handys können parallel genutzt werden; die Automatiken laufen immer nur auf dem Gerät mit der **Steuerung**, die sich gegenseitig übernehmen und freigeben lässt. Anleitung: [raspi/README.md](raspi/README.md).

## Installieren

1. Auf dem Handy unter **Releases → latest** die Datei `HDR-Aduro.apk` antippen und herunterladen.
2. Installieren. Android fragt einmalig, ob du Apps aus dieser Quelle erlauben willst.
3. In der App unter **Einstellungen** eintragen:
   - IP-Adresse des Ofens (oder suchen lassen)
   - Seriennummer und PIN (Aufkleber am Ofen) – per QR-Code-Scan bzw. Foto vom Typenschild
   - optional Homematic IP: Access-Point-ID (QR-Code auf dem Access Point scannen) und ggf. dessen PIN

Alle Zugangsdaten bleiben auf deinem Handy. Die App sendet nichts an Dritte (Ausnahme: der Lookup-Server von Homematic, um deinen Access Point zu finden).

### Warnungen bei der Installation

Weil die App nicht aus dem Play Store kommt, zeigt Android einige Warnungen. Das ist bei Apps von GitHub normal.

- **„Aus Sicherheitsgründen ist es nicht erlaubt, unbekannte Apps aus dieser Quelle zu installieren“**
  → *Einstellungen* antippen und **„Dieser Quelle vertrauen“** einschalten (für den Browser oder Dateimanager, mit dem du die APK öffnest). Danach zurück und installieren.
- **Google Play Protect: „App scannen?“**
  → **„App scannen“** antippen und warten. Danach installieren.
- **Google Play Protect: „Unsichere App blockiert“ oder „Unbekannte App“**
  → auf **„Weitere Details“** tippen und **„Trotzdem installieren“** wählen.
  Die Warnung kommt unter anderem, weil die App beim Handystart mitläuft und darum bittet, die Akku-Optimierung abzuschalten. Beides ist nötig, damit die Überwachung im Hintergrund funktioniert.
- **Samsung: „Automatische Sperre“ (Auto Blocker) blockiert die Installation**
  → *Einstellungen → Sicherheit und Datenschutz → Automatische Sperre* vorübergehend ausschalten, App installieren, danach wieder einschalten.
- **„App nicht installiert“ / „Paket steht in Konflikt mit einem vorhandenen Paket“**
  → Es ist noch eine ältere Version mit anderem Schlüssel installiert. Diese zuerst deinstallieren (Einstellungen gehen dabei verloren und müssen neu eingetragen werden).

**Updates:** Neue Version einfach wieder unter *Releases → latest* herunterladen und über die alte installieren – die Einstellungen bleiben erhalten.

> ℹ️ Google führt schrittweise eine Pflicht-Prüfung für Entwickler von Apps außerhalb des Play Stores ein (in Deutschland voraussichtlich ab 2027). Ist die App bis dahin nicht registriert, kann die Installation zusätzliche Schritte erfordern. Dieser Abschnitt wird dann aktualisiert.

## Bekannte Grenzen

- Die App nutzt **inoffizielle, nachgebaute Schnittstellen** (NBE-Protokoll des Ofens, lokale/Cloud-API von Homematic IP). Ein Firmware-Update von Aduro oder eQ-3 kann die App jederzeit unbrauchbar machen.
- Getestet nur mit einem Aduro H1 und der Homematic-Installation (Access Point) des Autors.
- Es gibt **keinen Support**. Fehlermeldungen und Verbesserungen gerne als Issue oder Pull Request – eine Antwort ist aber nicht garantiert.

## Selbst bauen

Jede Änderung an `main` baut automatisch eine signierte APK (GitHub Actions) und aktualisiert das Release `latest`.

Für einen eigenen Fork: eigenen Schlüssel erzeugen (`app/release.keystore`, Alias `ofen`) und das Passwort als Repository-Secret `OFEN_KEY_PASSWORD` hinterlegen. Ohne das Secret bricht der Build mit einem Hinweis ab. Lokal ohne Passwort entsteht eine unsignierte APK.

## Rechtliches

Dieses Projekt steht in **keiner Verbindung** zu Aduro A/S, NBE oder eQ-3 AG. Markennamen werden nur zur Beschreibung der Kompatibilität genannt und gehören ihren jeweiligen Inhabern.

Lizenz: [MIT](LICENSE) – ohne jede Gewährleistung oder Haftung, soweit gesetzlich zulässig.
