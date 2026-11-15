# HDR Aduro Home Raspi

Wandgerät mit Touchscreen für den Aduro-Hybridofen: ein Raspberry Pi, der rund um die Uhr läuft, den Ofen überwacht und die Automatiken ausführt. Die Oberfläche ist dieselbe wie in der HDR-Aduro-App. Handys können parallel genutzt werden.

> ⚠️ Es gelten dieselben Sicherheitshinweise und derselbe Haftungsausschluss wie für die App (siehe [README](../README.md)). Der Raspi kann den Ofen selbsttätig starten.

## Hardware

- Raspberry Pi 4 (4 GB) – reicht locker, wird im Gehäuse kaum warm. Pi 5 geht auch (mit Kühler).
- Raspberry Pi Touch Display 2 (7") – hochkant passt die Oberfläche 1:1
- offizielles Netzteil, microSD „High Endurance“ (32 GB)

## Installation

1. Raspberry Pi OS **mit Desktop** (Bookworm) aufspielen, WLAN/LAN einrichten, automatische Anmeldung an.
2. Im Terminal:
   ```bash
   curl -fLO https://github.com/Minibilli/HDR-Aduro/releases/latest/download/hdr-aduro-home-install.sh
   bash hdr-aduro-home-install.sh
   ```
3. Neu starten. Der Touchscreen zeigt die Oberfläche. Unter **Einstellungen → Ofen & Verbindung** den Ofen suchen lassen und Seriennummer + Passwort eintragen, optional Homematic IP verbinden.
4. Im Router dem Raspi eine feste IP geben.

Läuft als Dienst `hdr-aduro-home` (startet beim Booten und nach Abstürzen neu). Einstellungen liegen in `~/.hdr-aduro/config.json`. Protokoll: `journalctl -u hdr-aduro-home -f`.

## Handys parallel nutzen – „Steuerung“

Anzeigen und Bedienen geht auf allen Geräten gleichzeitig. Die **Automatiken** (Raumsteuerung, automatische Heizstufe, Pellets früher stoppen / Neustart, Heizkörper, Überwachung nach „Ausschalten“) laufen aber immer nur auf **einem** Gerät – dem mit der **Steuerung**. So können sich nie zwei Regler gegenseitig den Ofen starten und stoppen.

**Koppeln:** Am Raspi unter *Einstellungen → Raspi & Handys* steht ein 6-stelliger Code. In der App auf dem Handy unter *Einstellungen → Raspi & Handys* die Adresse des Raspi und den Code eintragen. Das Handy übernimmt dabei Ofen-Zugang, Homematic und alle Einstellungen vom Raspi. Im Browser (PC, Tablet) einfach `http://<raspi>:8080` öffnen und den Code eingeben.

**Normalbetrieb:** Der Raspi hat die Steuerung. Die App auf dem Handy zeigt dann einfach den Raspi an – gleiche Werte, gleiche Einstellungen. Meldungen des Raspi (z. B. „Holz nachlegen“, „Pellets nachfüllen“) kommen als Benachrichtigung aufs Handy.

| Aktion | Wo | Was passiert |
|---|---|---|
| **Übernehmen** | Handy | Das Handy führt die Automatiken aus, der Raspi hört sofort auf. Einstellungen und laufender Zustand (Heizkörper zu, ausgeschaltet, Mindestlaufzeiten …) wandern mit. |
| **Übernehmen** | Raspi | Der Raspi holt sich die Steuerung zurück. Das Handy merkt es beim nächsten Abgleich (≤ 30 s) und hört auf. |
| **Freigeben** | Handy | Steuerung geht an den Raspi zurück, er macht nahtlos weiter. |
| **Freigeben** | Raspi | Niemand steuert, keine Automatik läuft. Geschlossene Heizkörper gehen zurück. Ein Handy kann übernehmen. |
| **Notfall-Übernahme** | Handy | Nur wenn der Raspi nicht erreichbar ist. Das Handy steuert allein. |

**Schutz:**
- Das steuernde Handy meldet sich alle 30 s beim Raspi. Ohne Kontakt pausiert es seine Automatik nach 2 min, der Raspi holt sich die Steuerung nach 3 min zurück. (Abschaltbar: dann ist die Steuerung danach freigegeben.)
- Hat ein Handy die Steuerung, kann der Raspi die Automatik-Einstellungen nicht ändern – nur nach „Übernehmen“.
- Nach einem Neustart wartet der Raspi 90 s („Startschutz“), ob ein Handy per Notfall-Übernahme steuert, bevor er selbst schaltet.
- „Funktionen beenden“ in der App gibt die Steuerung vorher an den Raspi zurück.
- Entkoppeln schaltet die Automatiken auf dem Handy aus.

## Selbst bauen

```bash
gradle :raspi:fatJar        # -> raspi/build/libs/hdr-aduro-home-raspi.jar
java -jar raspi/build/libs/hdr-aduro-home-raspi.jar
```

Der Kern (Ofen, Homematic, Steuerung) liegt in `shared/` und wird von App und Raspi gemeinsam genutzt.
