// Copy this file to config.h and fill in your values. config.h is gitignored
// so your WiFi password and control token never get committed.
#pragma once

// --- WiFi (ESP32-S3 is 2.4 GHz ONLY - use the 2.4 GHz SSID, not a 5 GHz one) ---
#define WIFI_SSID   "wronso-2.4g"
#define WIFI_PASS   "your-2.4ghz-password"

// --- The Pi cyberdeck ---
#define PI_HOST     "192.168.0.29"
#define TELE_PORT   9000            // cyberdeck-telemetry (read-only status feed)
#define CTRL_PORT   9100            // cyberdeck-control  (authenticated actions)
#define CTRL_TOKEN  "paste-token-from-/home/kali/.config/cyberdeck/control-token"

// --- Local clock: telemetry sends UTC epoch; show this offset (hours) ---
#define TZ_HOURS    8               // Taiwan = UTC+8
