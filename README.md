# CYBERDECK

A portable **Raspberry Pi 3B+** running **Kali Linux**, using a rooted **Nexus 5**
phone as its display over USB. No monitor, no separate keyboard on the deck — just
a Pi, a phone screen, and an NFC card to log in. A repurposed ESP8266 with a small
OLED rides along as a wireless status dashboard.

This repo is the deck's brain: the Pi scripts, services, themes and custom terminal;
the Android **phone launcher** that turns the Nexus 5 into the screen; and the
**ESP8266 dashboard** firmware.

```
                         WiFi (telemetry)
        ┌──────────────┐◄───────────────────┐  ┌──────────────┐
        │  Pi 3B+      │        USB          │  │  ESP8266     │
        │  Kali Linux  │◄───────────────────────│  + SH1106    │  status dashboard
        └──────────────┘   adb + VNC         │  │  OLED        │
              ▲       │                       │  └──────────────┘
              │NFC tap│    ┌─────────────────┐│
        ┌──────────┐  └───►│   Nexus 5       ││
        │  PN532   │       │  (the screen)   ├┘
        │ card=login│      │  phone launcher │
        └──────────┘       └─────────────────┘
```

Three clients, one deck: the **phone** is the screen, the **ESP8266** is a glanceable
status panel, and an **NFC card** is the key.

---

## How the screen works

The Pi has no monitor. The phone *is* the monitor, driven entirely over the USB
cable:

```
lightdm ─► Xorg on a forced HDMI connector (headless framebuffer)
        ─► x11vnc  (127.0.0.1:5901)
        ─► adb reverse tcp:5900 5901   (Pi → phone over USB)
        ─► AVNC on the phone           (com.gaurav.avnc)
```

`cyberdeck-display-guard` watches this chain and repairs it automatically — if the
VNC session drops or the tunnel dies, it re-establishes without user action.
Display is treated as first priority: as long as the Pi is on, the screen is on.

---

## The phone launcher

`phone-launcher/` is a native Android app (`com.cyberdeck.launcher`) registered as
the phone's **HOME** activity, so the Nexus 5 boots straight into the deck HUD
instead of a normal launcher. It is deliberately built against a minimal SDK
(23) and draws everything itself — no support libraries, no XML layouts.

- **Live HUD** — TEMP, LINK, UPTIME, POWER and network RATE cells, plus a scrolling
  **process stream**, all fed from the Pi's telemetry endpoint.
- **Second reverse tunnel** — reaches telemetry at `127.0.0.1:9000` over its own
  `adb reverse tcp:9000`, separate from the VNC tunnel, so the HUD keeps updating
  even before/without a VNC session.
- **Undervoltage banner** — surfaces the Pi's throttle state on the phone.
- **Power controls** — START CYBERDECK (brings up VNC), plus confirm-gated REBOOT /
  POWER OFF that call the telemetry server's control routes; screen rotate is local.
- **Battery-aware** — shows CHARGING / battery state from `BatteryManager`.

Fonts (Chakra Petch, Corpta) live in `assets/`. The **signing keystore is not in
this repo** — build and sign with your own key.

```sh
# with an Android SDK + build-tools on PATH; sign with your own keystore
# (source is plain android.jar APIs, no gradle project committed)
```

The prebuilt `launcher-new.apk` is included for convenience.

---

## The ESP8266 dashboard

`esp-dashboard/` (`deckdash.ino`) turns a retired Wi-Fi-deauther board (ESP8266 +
1.3" SH1106 OLED + a 3-way switch) into a **wireless, glanceable status panel** for
the deck. It polls the same telemetry server the phone uses, but over WiFi.

- **Works offline** — boots straight to the dashboard, auto-reconnects in the
  background, keeps the last-known readings on screen with an `OFFLINE` tag.
- **Three pages**, switch-navigated (LEFT/RIGHT cycle, DOWN refresh):
  - `DECK` — temp / uptime / RAM / IP
  - `SYSTEM` — CPU load / throttle state / SSH sessions / clock
  - `RADIO` — SSID / signal / ESP IP / MAC / free heap (works offline; self-info)
- **Kali dragon boot splash**; hold DOWN at boot or in-app to open a WiFiManager
  captive portal for WiFi + the telemetry URL.
- **Auto-detects** the OLED's I²C pins; SH1106 driver for the 1.3" deauther panels.

It reads the plain-text telemetry (`TEMP / LOAD / UP / RAM / SSHN / EPOCH /
THROTTLED`) at `http://<pi>:9000/`. Set that URL in the captive portal; a DHCP
reservation or a hostname for the Pi is recommended so it survives IP changes.

**Build:** Arduino core `esp8266:esp8266`, libraries **U8g2** + **WiFiManager**.
Monitoring only — it never transmits attack frames. A flashable
`build/deckdash.ino.bin` is included; `mkkali.py` regenerates the 1-bit logo header.

---

## Logging in with a card

Instead of typing a password, you tap an NFC card on a **PN532** reader (I²C, held
open by `cyberdeck-nfc-fast` for ~100 ms reads). A valid card unlocks the deck.

- **`cyberdeck-nfc-fast`** — keeps the PN532 open, publishes card state to a run file
- **`cyberdeck-nfc-watch`** — the login/unlock state machine
- **`cyberdeck-nfc-status`** — the on-screen "SCANNING FOR CARD" panel at the greeter
- **`cyberdeck-nfc-token`** — the PAM check that gates autologin

> **Provisioning:** the card credential (`/etc/cyberdeck-nfc/fast.bin`, a salted
> hash of the UID) is **deliberately excluded** from this repo. See
> `nfc/README-provisioning.txt`. UID matching is door-fob grade — clonable — so
> the password fallback always remains available.

---

## Telemetry

`cyberdeck-telemetry` (a small HTTP server on port `9000`) publishes live system
state — temperature, load, uptime, network speed, throttle status, and a stream of
running commands. It has two consumers:

- the **phone launcher**, over loopback via `adb reverse tcp:9000` (USB only), and
- the **ESP8266 dashboard**, over WiFi.

It also drives the lock (`/lock`), wake, reboot and poweroff endpoints used by the
launcher. Clients poll with `?since=<seq>` and receive only what they have not seen,
so the launcher's own throughput graph doesn't measure its own polling.

---

## The look

The deck runs a cohesive "Cyberdeck" theme: a dark `#0d0f14` ground with a
Kali-blue (`#367bf0`) / cyan (`#5fd7ff`) accent, and **Share Tech Mono** as the
system font.

- **`config/`** — starship prompt, terminal color scheme, the zsh pack
  (autosuggestions, syntax highlighting, fzf, themed `ls`, OSC 7 directory
  reporting), and the Tilix theme export
- **`wallpaper/cyberdeck-mkwallpaper`** — generates the CYBERDECK wallpaper with
  live-ish system info, derived from the same palette
- Window decorations use the `Kali-Dark-xHiDPI` xfwm4 theme (thick title bars)

---

## cyberterm — a custom terminal

`cyberterm/cyberterm.vala` is a small, native terminal built for this deck because
qterminal and Tilix couldn't do what was wanted (rounded tab corners + a "+" button
that follows the last tab), and GPU terminals (kitty, wezterm) don't work over a
software-rendered VNC framebuffer.

It wraps **VTE** — the same terminal engine GNOME Terminal uses — in a GTK3 window
whose tab bar is fully custom-styled:

- Rounded tab corners, distinct tab-bar background
- A squircle **"+"** button that trails the rightmost tab (the "+-as-last-tab" trick)
- Each tab shows the working directory, left-ellipsized so the current folder stays visible
- Cyberdeck palette + Share Tech Mono, runs your `$SHELL`
- `Ctrl+Shift+T/W` new/close · `Ctrl+Shift+C/V` copy/paste

**Build** (native, ~77 KB, no Python runtime):

```sh
sudo apt install valac libvte-2.91-dev libgtk-3-dev
cd cyberterm
valac --pkg gtk+-3.0 --pkg vte-2.91 cyberterm.vala -o cyberterm
sudo install -m755 cyberterm /usr/local/bin/cyberterm
```

---

## Power & thermal (the hard-won lessons)

The 3B+ is old and the deck runs off a power bank, which taught some things worth
writing down:

- **Undervoltage is a cable problem, not a battery problem.** A cheap 28 AWG cable
  drops ~510 mV at 1.2 A — right through the ~470 mV headroom. A thick (20 AWG /
  "5A") short cable fixes it. Check with `vcgencmd get_throttled` (bit `0x1` = under
  voltage now, `0x10000` = occurred this boot).
- **The PN532 must take VCC from pin 1 (3.3 V), never pin 2 (5 V).** On 5 V it
  overheats and its pull-ups drag the I²C lines into the 3.3 V-only GPIO, wedging
  the bus. Diagnose passively with `pinctrl get 2,3` — both should read `hi` at idle.
- **A wedged bus vs. a dead pin:** a pin that reads `lo` in `a0` (I²C) mode but `hi`
  as `ip pu` is a stuck peripheral, not damaged silicon.
- **Thermal is airflow, not heatsinks.** In a sealed case the bottleneck is
  air→outside, not chip→air. Vents (inlet low, outlet high) + a small 5V fan beat
  any number of heatsinks. Idle sits near the 60 °C soft limit; `temp_soft_limit=70`
  (3B+ only) helps *after* airflow is sorted.
- **Add a power switch on the +5V line, upstream of the Pi** (a panel-mount KCD11
  handles the 1.2 A draw at 20% of spec). Switch +5V, not ground, so nothing on the
  Pi side is left live. Short-test the finished cable with a meter before it ever
  touches the board — that one measurement is what saves the board.

Full wiring/thermal bench notes are kept alongside the deck as a separate document.

---

## Repo layout

```
bin/              the cyberdeck-* helper scripts (display, telemetry, session, power)
esp-dashboard/    ESP8266 + SH1106 OLED status dashboard (Arduino sketch + binary)
phone-launcher/   Android HOME-replacement launcher / HUD (source + APK, no keystore)
cyberterm/        the custom terminal (Vala source)
config/           prompt, terminal colors, zsh pack, Tilix theme
nfc/              card-login scripts (credential store excluded)
services/         systemd units for the always-on pieces
wallpaper/        wallpaper generator
```

---

## Notes

- Scripts assume Kali on a Pi 3B+ with the display chain above; they're specific to
  this deck, shared as reference rather than a turnkey install.
- Secrets (card credential, any password pipes, the launcher signing keystore) have
  been stripped for publication.
- The deck accesses the network over the built-in `wlan0`; nexmon firmware on the
  BCM43455 gives it monitor mode + capture on the built-in radio.

Built iteratively, one problem at a time. If you're building something similar,
the power and thermal notes above are the parts I wish I'd known first.
