#!/bin/bash
# HDR Aduro Home Raspi – Installation auf Raspberry Pi OS (Bookworm, mit Desktop)
#
#   bash install.sh                  -> neueste Version von GitHub laden
#   bash install.sh meine-datei.jar  -> eine vorhandene JAR-Datei installieren
#
# Als normaler Benutzer ausführen (nicht mit sudo) – sudo wird nur dort benutzt, wo es nötig ist.
set -e
JAR_URL="https://github.com/Minibilli/HDR-Aduro/releases/latest/download/hdr-aduro-home-raspi.jar"
DIR=/opt/hdr-aduro-home
ME="$(id -un)"
if [ "$ME" = "root" ]; then echo "Bitte ohne sudo starten: bash install.sh"; exit 1; fi

echo "== Pakete (Java, Chromium)"
sudo apt-get update
sudo apt-get install -y openjdk-17-jre-headless curl
sudo apt-get install -y chromium-browser 2>/dev/null || sudo apt-get install -y chromium

echo "== Programm nach $DIR"
sudo mkdir -p "$DIR"
if [ -n "$1" ] && [ -f "$1" ]; then sudo cp "$1" "$DIR/hdr-aduro-home-raspi.jar"
else sudo curl -fL -o "$DIR/hdr-aduro-home-raspi.jar" "$JAR_URL"; fi

echo "== Dienst (startet beim Booten, startet nach Absturz neu)"
sudo tee /etc/systemd/system/hdr-aduro-home.service >/dev/null <<EOF
[Unit]
Description=HDR Aduro Home Raspi
After=network-online.target
Wants=network-online.target

[Service]
User=$ME
Environment=OFEN_DATA=/home/$ME/.hdr-aduro
Environment=OFEN_PORT=8080
ExecStart=/usr/bin/java -Xmx160m -Dstdout.encoding=UTF-8 -jar $DIR/hdr-aduro-home-raspi.jar
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
EOF
sudo systemctl daemon-reload
sudo systemctl enable hdr-aduro-home
sudo systemctl restart hdr-aduro-home

echo "== Touchscreen: Chromium im Vollbild nach der Anmeldung"
BROWSER="$(command -v chromium-browser || command -v chromium)"
sudo tee "$DIR/kiosk.sh" >/dev/null <<EOF
#!/bin/sh
# wartet, bis der Dienst antwortet, dann Oberfläche im Vollbild
until curl -s -o /dev/null http://localhost:8080/; do sleep 1; done
exec $BROWSER --kiosk --noerrdialogs --disable-infobars --no-first-run --disable-session-crashed-bubble \\
  --overscroll-history-navigation=0 --disable-pinch --check-for-update-interval=31536000 \\
  --password-store=basic http://localhost:8080/
EOF
sudo chmod +x "$DIR/kiosk.sh"
# labwc (Standard bei aktuellem Raspberry Pi OS)
mkdir -p ~/.config/labwc
touch ~/.config/labwc/autostart
grep -q hdr-aduro-home ~/.config/labwc/autostart || echo "$DIR/kiosk.sh &" >> ~/.config/labwc/autostart
# wayfire (ältere Bookworm-Installationen)
if [ -f ~/.config/wayfire.ini ] && ! grep -q hdr-aduro-home ~/.config/wayfire.ini; then
  if grep -q '^\[autostart\]' ~/.config/wayfire.ini; then sed -i "/^\[autostart\]/a hdraduro = $DIR/kiosk.sh" ~/.config/wayfire.ini
  else printf '\n[autostart]\nhdraduro = %s/kiosk.sh\n' "$DIR" >> ~/.config/wayfire.ini; fi
fi

IP="$(hostname -I | awk '{print $1}')"
echo
echo "Fertig. Status:  systemctl status hdr-aduro-home   ·   Protokoll:  journalctl -u hdr-aduro-home -f"
echo "Oberfläche:      am Touchscreen nach dem nächsten Neustart, im Heimnetz unter http://$IP:8080"
echo "Tipp:            Automatische Anmeldung und „Screen Blanking“ in  sudo raspi-config  → System / Display einstellen."
