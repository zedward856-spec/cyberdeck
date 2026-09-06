#!/bin/sh
# cyberdeck-install.sh - install the control-panel backend on the deck and make
# the deck's own AP the boot default. Run on the Pi (needs sudo). Idempotent.
#
#   sh ~/cyberdeck/bin/cyberdeck-install.sh
#
# After this, the deck boots hosting the 'cyberdeck' AP and the control service
# starts automatically. To get the deck back on home WiFi:  cyberdeck-net home
set -eu
REPO="$(cd "$(dirname "$0")/.." && pwd)"

echo "[1/6] install helper scripts to /usr/local/bin"
sudo install -m755 "$REPO/bin/cyberdeck-control"   /usr/local/bin/cyberdeck-control
sudo install -m755 "$REPO/bin/cyberdeck-net"       /usr/local/bin/cyberdeck-net
sudo install -m755 "$REPO/bin/cyberdeck-ap-setup"  /usr/local/bin/cyberdeck-ap-setup

echo "[2/6] install control service unit"
sudo install -m644 "$REPO/services/cyberdeck-control.service" \
     /etc/systemd/system/cyberdeck-control.service

echo "[3/6] install sudoers rule (reboot/poweroff only)"
sudo install -m440 "$REPO/services/cyberdeck-control.sudoers" \
     /etc/sudoers.d/cyberdeck-control
sudo visudo -cf /etc/sudoers.d/cyberdeck-control

echo "[4/6] stop any manual control process, enable the service"
pkill -f 'python3.*cyberdeck-control' 2>/dev/null || true
sudo systemctl daemon-reload
sudo systemctl enable --now cyberdeck-control

echo "[5/6] make the deck AP the boot default (home WiFi stays as fallback)"
nmcli con modify cyberdeck-ap connection.autoconnect yes connection.autoconnect-priority 20

echo "[6/6] status"
systemctl is-active cyberdeck-control && echo "control service: active"
echo "done. Activate the AP now with:  cyberdeck-net ap"
echo "(it will also come up automatically on the next boot)"
