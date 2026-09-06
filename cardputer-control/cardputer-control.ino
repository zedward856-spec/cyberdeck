/*
 * Cyberdeck Control - M5Stack Cardputer (ESP32-S3) handheld panel for the Pi deck.
 *
 * Two views, switched with the LEFT/RIGHT arrow keys ( , and / ):
 *   STATUS  - live telemetry from the Pi (cyberdeck-telemetry :9000, read-only)
 *   CONTROL - a menu of actions sent to the Pi (cyberdeck-control :9100, token-gated)
 *
 * Keys:  ;/. up/down   ,// switch view   ENTER run   `  back / cancel   r refresh
 * Reboot / Power off ask for a second ENTER to confirm.
 *
 * Build: esp32:esp32:m5stack_cardputer  (Arduino), libs: M5Cardputer (+M5GFX/M5Unified)
 * Fill config.h from config.example.h first (WiFi + Pi host + control token).
 */
#include <M5Cardputer.h>
#include <WiFi.h>
#include <HTTPClient.h>
#include "config.h"

// ---- palette (RGB565) ----
#define C_BG     0x0861   // near-black #0d0f14
#define C_FG     0xFFFF
#define C_DIM    0x8410   // gray
#define C_BLUE   0x33FD   // kali blue #367bf0
#define C_CYAN   0x5FFF   // #5fd7ff
#define C_GREEN  0x2FEA
#define C_AMBER  0xFD20
#define C_RED    0xF9E7

// ---- views ----
enum View { V_STATUS = 0, V_CONTROL = 1 };
int view = V_STATUS;

// ---- telemetry cache ----
bool  online = false, everData = false;
float gTemp = 0, gLoad = 0;
long  gUp = 0, gRamU = 0, gRamT = 0, gSSH = 0, gEpoch = 0;
unsigned long gThr = 0;
unsigned long lastPoll = 0;
const unsigned long POLL_MS = 2500;

// ---- action menu (keys must match cyberdeck-control ACTIONS) ----
struct Action { const char *key; const char *label; bool confirm; };
Action MENU[] = {
  {"lock",       "Lock screen",     false},
  {"wake",       "Wake screen",     false},
  {"wifi",       "WiFi recon",      false},
  {"ir",         "IR console",      false},
  {"portal",     "Portal test",     false},
  {"netinfo",    "Net info",        false},
  {"screenshot", "Screenshot",      false},
  {"reboot",     "Reboot deck",     true },
  {"poweroff",   "Power off deck",  true },
};
const int NMENU = sizeof(MENU) / sizeof(MENU[0]);
int sel = 0, top = 0;
int armed = -1;                 // index awaiting confirm
unsigned long armedAt = 0;
String toast; unsigned long toastAt = 0;

M5Canvas *cv = nullptr;         // offscreen buffer -> flicker-free redraw

// ---------------------------------------------------------------- networking
String httpGet(const String &url, int timeoutMs) {
  if (WiFi.status() != WL_CONNECTED) return "";
  HTTPClient http; http.setConnectTimeout(timeoutMs); http.setTimeout(timeoutMs);
  if (!http.begin(url)) return "";
  int code = http.GET();
  String body = (code == 200) ? http.getString() : "";
  http.end();
  return body;
}

String field(const String &b, const char *key) {
  int p = b.indexOf(key);
  if (p < 0) return "";
  p += strlen(key);
  int e = b.indexOf('\n', p); if (e < 0) e = b.length();
  String v = b.substring(p, e); v.trim(); return v;
}

void pollTelemetry() {
  String url = String("http://") + PI_HOST + ":" + TELE_PORT + "/?since=0";
  String b = httpGet(url, 1500);
  if (b.length() == 0) { online = false; return; }
  online = true; everData = true;
  gTemp = field(b, "TEMP ").toFloat();
  gLoad = field(b, "LOAD ").toFloat();
  gUp   = field(b, "UP ").toInt();
  gEpoch = field(b, "EPOCH ").toInt();
  gSSH  = field(b, "SSHN ").toInt();
  String ram = field(b, "RAM ");
  int sp = ram.indexOf(' ');
  if (sp > 0) { gRamU = ram.substring(0, sp).toInt(); gRamT = ram.substring(sp + 1).toInt(); }
  gThr = strtoul(field(b, "THROTTLED ").c_str(), nullptr, 0);
}

String sendAction(const char *name) {
  String url = String("http://") + PI_HOST + ":" + CTRL_PORT + "/do/" + name +
               "?t=" + CTRL_TOKEN;
  String b = httpGet(url, 4000);
  b.trim();
  return b.length() ? b : "no reply";
}

// ---------------------------------------------------------------- drawing
int rssiBars() {
  if (WiFi.status() != WL_CONNECTED) return 0;
  long r = WiFi.RSSI();
  return r >= -55 ? 4 : r >= -67 ? 3 : r >= -78 ? 2 : r >= -88 ? 1 : 0;
}

void drawTopBar(const char *title) {
  cv->fillRect(0, 0, 240, 16, C_BG);
  cv->setTextColor(C_BLUE, C_BG); cv->setTextSize(1);
  cv->setCursor(3, 4); cv->print(title);
  // rssi bars top-right
  int bars = rssiBars();
  for (int i = 0; i < 4; i++) {
    int h = 3 + i * 3, x = 210 + i * 6, y = 13 - h;
    if (i < bars) cv->fillRect(x, y, 4, h, C_CYAN);
    else          cv->drawRect(x, y, 4, h, C_DIM);
  }
  cv->setTextColor(online ? C_GREEN : C_RED, C_BG);
  cv->setCursor(150, 4); cv->print(online ? "ONLINE" : "OFFLINE");
  cv->drawFastHLine(0, 16, 240, C_DIM);
}

void drawStatus() {
  drawTopBar("CYBERDECK");
  if (!everData) {
    cv->setTextColor(C_DIM, C_BG); cv->setCursor(6, 60);
    cv->print(online ? "connecting to deck..." : "waiting for WiFi...");
    return;
  }
  char s[40];
  // big temp
  uint16_t tc = gTemp >= 70 ? C_RED : gTemp >= 60 ? C_AMBER : C_CYAN;
  cv->setTextColor(tc, C_BG); cv->setTextSize(3);
  cv->setCursor(6, 24); snprintf(s, sizeof(s), "%.1f", gTemp); cv->print(s);
  cv->setTextSize(1); cv->setCursor(96, 44); cv->print("C");
  // clock from epoch
  if (gEpoch > 0) {
    long e = gEpoch + (long)TZ_HOURS * 3600;
    snprintf(s, sizeof(s), "%02ld:%02ld", (e / 3600) % 24, (e / 60) % 60);
    cv->setTextColor(C_FG, C_BG); cv->setTextSize(2);
    cv->setCursor(150, 24); cv->print(s);
  }
  cv->setTextSize(1);
  cv->setTextColor(C_FG, C_BG);
  cv->setCursor(120, 26); snprintf(s, sizeof(s), "load %.2f", gLoad); cv->print(s);
  cv->setCursor(120, 44); snprintf(s, sizeof(s), "up %ldh%02ldm", gUp / 3600, (gUp % 3600) / 60); cv->print(s);
  // ram bar
  cv->setTextColor(C_DIM, C_BG); cv->setCursor(6, 58); cv->print("RAM");
  if (gRamT > 0) {
    cv->setTextColor(C_FG, C_BG); cv->setCursor(36, 58);
    snprintf(s, sizeof(s), "%ld / %ld MB", gRamU, gRamT); cv->print(s);
    int w = (int)(228.0 * gRamU / gRamT);
    cv->drawRect(6, 70, 228, 8, C_DIM);
    cv->fillRect(7, 71, w > 226 ? 226 : w, 6, C_BLUE);
  }
  // ssh + power
  cv->setTextColor(C_DIM, C_BG); cv->setCursor(6, 86); cv->print("SSH");
  cv->setTextColor(C_FG, C_BG); cv->setCursor(36, 86);
  snprintf(s, sizeof(s), "%ld", gSSH); cv->print(s);
  const char *pw = "OK"; uint16_t pc = C_GREEN;
  if (gThr & 0x1)          { pw = "UNDERVOLT!";   pc = C_RED; }
  else if (gThr & 0x4)     { pw = "THROTTLED!";   pc = C_RED; }
  else if (gThr & 0x50000) { pw = "dip past boot"; pc = C_AMBER; }
  cv->setTextColor(C_DIM, C_BG); cv->setCursor(120, 86); cv->print("PWR");
  cv->setTextColor(pc, C_BG); cv->setCursor(150, 86); cv->print(pw);
  // footer
  cv->setTextColor(C_DIM, C_BG); cv->setCursor(6, 122);
  cv->print("[,/] view  [r] refresh");
}

void drawControl() {
  drawTopBar("CONTROL");
  const int rowH = 15, first = 20, rows = 6;
  if (sel < top) top = sel;
  if (sel >= top + rows) top = sel - rows + 1;
  for (int i = 0; i < rows && (top + i) < NMENU; i++) {
    int idx = top + i, y = first + i * rowH;
    bool cur = (idx == sel);
    if (cur) cv->fillRoundRect(2, y - 1, 236, rowH, 2, C_BLUE);
    cv->setTextColor(cur ? C_FG : C_DIM, cur ? C_BLUE : C_BG);
    cv->setCursor(8, y + 2); cv->print(MENU[idx].label);
    if (MENU[idx].confirm) {
      cv->setTextColor(cur ? C_FG : C_RED, cur ? C_BLUE : C_BG);
      cv->setCursor(200, y + 2); cv->print("!");
    }
  }
  // toast / armed line
  cv->setTextColor(C_AMBER, C_BG); cv->setCursor(6, 110);
  if (armed >= 0) { cv->print("confirm "); cv->print(MENU[armed].label); cv->print("? ENTER"); }
  else if (toast.length() && millis() - toastAt < 3500) { cv->setTextColor(C_CYAN, C_BG); cv->print(toast); }
  cv->setTextColor(C_DIM, C_BG); cv->setCursor(6, 122);
  cv->print("[;/.] move [enter] run [,] status");
}

void render() {
  cv->fillScreen(C_BG);
  if (view == V_STATUS) drawStatus(); else drawControl();
  cv->pushSprite(0, 0);
}

// ---------------------------------------------------------------- input
void activate() {
  Action &a = MENU[sel];
  if (a.confirm && armed != sel) {          // first press arms
    armed = sel; armedAt = millis();
    M5Cardputer.Speaker.tone(1200, 40);
    return;
  }
  armed = -1;
  M5Cardputer.Speaker.tone(2200, 40);
  String r = sendAction(a.key);
  toast = String(a.label) + ": " + r; toastAt = millis();
  M5Cardputer.Speaker.tone(r.startsWith("err") || r.indexOf("no ") >= 0 ? 500 : 2600, 70);
}

void handleKeys() {
  if (!M5Cardputer.Keyboard.isChange() || !M5Cardputer.Keyboard.isPressed()) return;
  auto st = M5Cardputer.Keyboard.keysState();
  for (char c : st.word) {
    if (c == ',') { view = V_STATUS; armed = -1; }
    else if (c == '/') { view = V_CONTROL; }
    else if (c == 'r' || c == 'R') { lastPoll = 0; }
    else if (c == '`') { armed = -1; view = V_STATUS; }
    else if (view == V_CONTROL && c == ';') { sel = (sel + NMENU - 1) % NMENU; armed = -1; }
    else if (view == V_CONTROL && c == '.') { sel = (sel + 1) % NMENU; armed = -1; }
  }
  if (st.enter && view == V_CONTROL) activate();
}

// ---------------------------------------------------------------- lifecycle
void setup() {
  auto cfg = M5.config();
  M5Cardputer.begin(cfg, true);
  M5Cardputer.Display.setRotation(1);
  M5Cardputer.Display.fillScreen(C_BG);
  cv = new M5Canvas(&M5Cardputer.Display);
  cv->createSprite(240, 135);

  WiFi.mode(WIFI_STA);
  WiFi.setSleep(false);
  WiFi.begin(WIFI_SSID, WIFI_PASS);

  cv->fillScreen(C_BG);
  cv->setTextColor(C_CYAN, C_BG); cv->setTextSize(2);
  cv->setCursor(20, 40); cv->print("CYBERDECK");
  cv->setTextSize(1); cv->setTextColor(C_DIM, C_BG);
  cv->setCursor(20, 70); cv->print("joining "); cv->print(WIFI_SSID);
  cv->pushSprite(0, 0);
}

void loop() {
  M5Cardputer.update();
  handleKeys();
  if (armed >= 0 && millis() - armedAt > 3000) armed = -1;   // confirm times out
  if (millis() - lastPoll > POLL_MS) { pollTelemetry(); lastPoll = millis(); }
  render();
  delay(20);
}
