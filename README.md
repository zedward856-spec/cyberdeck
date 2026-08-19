# CYBERDECK

A portable **Raspberry Pi 3B+** running **Kali Linux**, using a rooted **Nexus 5**
phone as its display over USB. No monitor, no separate keyboard on the deck — just
a Pi, a phone screen, and an NFC card to log in.

This repo is the deck's brain: the scripts, services, themes, and a custom
terminal that turn a bare Pi into the cyberdeck.

```
        ┌──────────────┐        USB         ┌─────────────────┐
        │  Pi 3B+      │◄──────────────────►│   Nexus 5       │
        │  Kali Linux  │   adb + VNC         │  (the screen)   │
        └──────────────┘                     └─────────────────┘
              ▲
              │ NFC tap
        ┌──────────┐
        │  PN532   │  card = login
        └──────────┘
```

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

`cyberdeck-telemetry` (a small HTTP server on `127.0.0.1:9000`) publishes live
system state — temperature, load, uptime, network speed, throttle status, and a
stream of running commands — consumed by the phone-side launcher and the desktop
HUD. It also drives the lock (`/lock`) and screen-wake endpoints.

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

Full wiring/thermal bench notes are kept alongside the deck as a separate document.

---

## Repo layout

```
bin/         the cyberdeck-* helper scripts (display, telemetry, session, power)
cyberterm/   the custom terminal (Vala source)
config/      prompt, terminal colors, zsh pack, Tilix theme
nfc/         card-login scripts (credential store excluded)
services/    systemd units for the always-on pieces
wallpaper/   wallpaper generator
```

---

## Notes

- Scripts assume Kali on a Pi 3B+ with the display chain above; they're specific to
  this deck, shared as reference rather than a turnkey install.
- Secrets (card credential, any password pipes) have been stripped for publication.
- The deck accesses the network over the built-in `wlan0`; nexmon firmware on the
  BCM43455 gives it monitor mode + capture on the built-in radio.

Built iteratively, one problem at a time. If you're building something similar,
the power and thermal notes above are the parts I wish I'd known first.
