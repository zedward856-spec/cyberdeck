/*
 * DeckDash - ESP8266 (ex-deauther) as a wireless Pi dashboard.
 * - Works OFFLINE: boots straight to the dashboard, auto-reconnects in the
 *   background, keeps last-known readings on screen, always navigable.
 * - Auto-detects the OLED's I2C pins; SH1106 driver (1.3" deauther panels)
 * - 3 pages navigated by the switch: LEFT/RIGHT cycle, DOWN forces a refresh
 *     DECK   : temp / uptime / RAM / IP     (last-known + OFFLINE tag when down)
 *     SYSTEM : CPU load / throttle / SSH / clock
 *     RADIO  : SSID / signal / ESP IP / MAC / free heap  (ESP self-info offline)
 * - Boot splash: Kali dragon; tap any key = continue, hold DOWN = wifi setup.
 *   In dashboard: hold DOWN ~1.5s = (re)open the wifi config portal.
 * Switch pins: LEFT=GPIO12  RIGHT=GPIO13  DOWN=GPIO14  (active-low)
 * Monitoring only. Build: esp8266:esp8266, libs U8g2 + WiFiManager.
 */
#include <ESP8266WiFi.h>
#include <ESP8266HTTPClient.h>
#include <WiFiClient.h>
#include <WiFiManager.h>
#include <U8g2lib.h>
#include <Wire.h>
#include "kali_logo.h"

char teleUrl[96] = "http://192.168.0.29:9000/";
U8G2_SH1106_128X64_NONAME_F_SW_I2C *oled = nullptr;
int SDAp = -1, SCLp = -1;

#define PIN_LEFT  12
#define PIN_RIGHT 13
#define PIN_DOWN  14
#define TZ_OFFSET (8L * 3600L)      // Pi clock is UTC; show UTC+8 (Taiwan)
const int NPAGES = 3;
int page = 0;
unsigned long lastFetch = 0;

// top-right widget region (bars + page indicator)
#define REGX0 96
#define REGY0 0
#define REGW  30
#define REGH  15
uint8_t indBits[(REGW*REGH+7)/8];
uint8_t barBits[(REGW*REGH+7)/8];
const uint8_t bayer4[4][4] = {{0,8,2,10},{12,4,14,6},{3,11,1,9},{15,7,13,5}};

// cached telemetry (kept across dropouts so offline still shows last-known)
bool  gOk = false;         // last fetch this cycle succeeded
bool  gHaveData = false;    // ever fetched successfully
String gTemp = "--";
long  gUp = 0, gUsed = 0, gTotal = 0, gLoad100 = 0, gSSHn = 0, gEpoch = 0;
unsigned long gThr = 0;

// ---- OLED discovery ----
bool i2cHas(uint8_t sda, uint8_t scl, uint8_t addr) {
  Wire.begin(sda, scl); Wire.setClock(400000);
  Wire.beginTransmission(addr); return Wire.endTransmission() == 0;
}
bool detectOLED() {
  int pairs[][2] = {{4,5},{5,4},{2,0},{0,2},{14,12},{12,14},{13,12},{2,14}};
  for (auto &p : pairs)
    for (uint8_t a : {0x3C, 0x3D})
      if (i2cHas(p[0], p[1], a)) { SDAp=p[0]; SCLp=p[1]; return true; }
  return false;
}
void splash(const char *l1, const char *l2) {
  if (!oled) return;
  oled->clearBuffer(); oled->setFont(u8g2_font_6x12_tf);
  oled->drawStr(0,12,l1); if (l2) oled->drawStr(0,30,l2);
  oled->sendBuffer();
}

// ---- input: falling-edge detect w/ light debounce ----
int sL = HIGH, sR = HIGH, sD = HIGH;
bool fell(int pin, int &last) {
  int v = digitalRead(pin);
  bool f = (last == HIGH && v == LOW);
  last = v;
  return f;
}

// ---- telemetry ----
String field(const String &body, const char *key) {
  int p = body.indexOf(key);
  if (p < 0) return "";
  p += strlen(key);
  int e = body.indexOf('\n', p); if (e < 0) e = body.length();
  String v = body.substring(p, e); v.trim(); return v;
}
void fetch() {
  gOk = false;
  if (WiFi.status() != WL_CONNECTED) return;   // offline: no blocking, keep last data
  String body;
  WiFiClient client; HTTPClient http; http.setTimeout(1500);
  if (http.begin(client, teleUrl)) {
    if (http.GET() == 200) { body = http.getString(); gOk = true; }
    http.end();
  }
  if (!gOk) return;
  gTemp = field(body, "TEMP "); if (gTemp == "") gTemp = "--";
  gUp   = field(body, "UP ").toInt();
  gEpoch= field(body, "EPOCH ").toInt();
  gSSHn = field(body, "SSHN ").toInt();
  gLoad100 = (long)(field(body, "LOAD ").toFloat() * 100);
  String ram = field(body, "RAM ");
  int sp = ram.indexOf(' ');
  if (sp > 0) { gUsed = ram.substring(0,sp).toInt(); gTotal = ram.substring(sp+1).toInt(); }
  gThr = strtoul(field(body, "THROTTLED ").c_str(), nullptr, 0);
  gHaveData = true;
}

int rssiBars() {
  if (WiFi.status() != WL_CONNECTED) return 0;
  long r = WiFi.RSSI();
  return r>=-55?4 : r>=-67?3 : r>=-78?2 : r>=-88?1 : 0;
}

// ---- widget drawing (top-right region) ----
void drawBars() {
  for (int i=0;i<4;i++){ int h=(i+1)*2+1; if (i<rssiBars()) oled->drawBox(100+i*6,12-h,4,h); else oled->drawFrame(100+i*6,12-h,4,h); }
}
void drawIndicator() {   // visual order L->C->R = RADIO(2)/DECK(0)/SYSTEM(1); current = big
  int order[3] = {2, 0, 1};
  for (int i=0;i<3;i++) oled->drawDisc(104 + i*8, 9, (order[i]==page) ? 3 : 1);
}

// ---- page body (everything EXCEPT the top-right widget) ----
void titleLine(const char *t) {
  oled->setFont(u8g2_font_7x13B_tf);
  oled->drawStr(0,12,t);
  oled->drawHLine(0,15,128);
  oled->setFont(u8g2_font_6x12_tf);
}
void drawBody() {
  char line[28];
  bool online = (WiFi.status() == WL_CONNECTED);
  if (page == 0) {                              // ---- DECK ----
    titleLine("CYBERDECK");
    if (gHaveData) {
      snprintf(line,sizeof(line),"Temp  %s C", gTemp.c_str()); oled->drawStr(0,30,line);
      snprintf(line,sizeof(line),"Up    %ldh%02ldm", gUp/3600, (gUp%3600)/60); oled->drawStr(0,42,line);
      if (gTotal > 0) {
        snprintf(line,sizeof(line),"RAM %ld/%ldMB", gUsed, gTotal); oled->drawStr(0,54,line);
        int w = (int)(124.0 * gUsed / gTotal); oled->drawFrame(0,56,126,7); oled->drawBox(1,57,(w<124?w:124),5);
      }
      oled->setFont(u8g2_font_5x7_tf);
      oled->drawStr(66,42, gOk ? WiFi.localIP().toString().c_str() : (online ? "no data" : "OFFLINE"));
    } else {
      oled->drawStr(0,32, online ? "connecting to Pi" : "OFFLINE - no wifi");
      oled->setFont(u8g2_font_5x7_tf);
      snprintf(line,sizeof(line),"ESP up %lus  heap %d", millis()/1000, ESP.getFreeHeap());
      oled->drawStr(0,52,line);
      oled->drawStr(0,62, teleUrl);
    }
  }
  else if (page == 1) {                         // ---- SYSTEM ----
    titleLine("SYSTEM");
    if (gHaveData) {
      snprintf(line,sizeof(line),"Load  %ld.%02ld", gLoad100/100, gLoad100%100); oled->drawStr(0,30,line);
      const char *st = "OK";
      if (gThr & 0x1) st = "UNDERVOLT!";
      else if (gThr & 0x4) st = "THROTTLED!";
      else if (gThr & 0x50000) st = "dip since boot";
      snprintf(line,sizeof(line),"Pwr   %s", st); oled->drawStr(0,42,line);
      snprintf(line,sizeof(line),"SSH   %ld sess", gSSHn); oled->drawStr(0,54,line);
      if (gOk && gEpoch > 0) {
        long e = gEpoch + TZ_OFFSET;
        snprintf(line,sizeof(line),"%02ld:%02ld", (e/3600)%24, (e/60)%60);
        oled->drawStr(88,30,line);
      } else {
        oled->setFont(u8g2_font_5x7_tf); oled->drawStr(92,30, online ? "stale" : "OFF");
      }
    } else {
      oled->drawStr(0,32, online ? "connecting..." : "OFFLINE");
      oled->setFont(u8g2_font_5x7_tf);
      snprintf(line,sizeof(line),"ESP heap %d", ESP.getFreeHeap()); oled->drawStr(0,50,line);
    }
  }
  else {                                        // ---- RADIO (self-info works offline) ----
    titleLine("RADIO");
    if (online) {
      snprintf(line,sizeof(line),"SSID %s", WiFi.SSID().c_str()); line[20]=0; oled->drawStr(0,30,line);
      snprintf(line,sizeof(line),"Sig  %ld dBm", WiFi.RSSI()); oled->drawStr(0,42,line);
      snprintf(line,sizeof(line),"IP   %s", WiFi.localIP().toString().c_str()); oled->drawStr(0,54,line);
    } else {
      oled->drawStr(0,30, "WiFi  OFFLINE");
      snprintf(line,sizeof(line),"AP  %s", WiFi.SSID().length()?WiFi.SSID().c_str():"(unset)"); line[20]=0; oled->drawStr(0,42,line);
      oled->drawStr(0,54, "hold DOWN = setup");
    }
    oled->setFont(u8g2_font_5x7_tf);
    snprintf(line,sizeof(line),"%s  heap %d", WiFi.macAddress().c_str(), ESP.getFreeHeap());
    oled->drawStr(0,63,line);
  }
}

// steady frame: body + wifi bars
void render() {
  if (!oled) return;
  oled->clearBuffer(); drawBody(); drawBars(); oled->sendBuffer();
}

// ---- region bit capture (read U8g2 full buffer directly) ----
int getPix(int x, int y) { uint8_t *b = oled->getBufferPtr(); return (b[x + (y>>3)*128] >> (y&7)) & 1; }
void capRegion(uint8_t out[]) {
  memset(out, 0, (REGW*REGH+7)/8);
  for (int yy=0; yy<REGH; yy++) for (int xx=0; xx<REGW; xx++)
    if (getPix(REGX0+xx, REGY0+yy)) { int idx=yy*REGW+xx; out[idx>>3] |= (1<<(idx&7)); }
}
int getBit(uint8_t a[], int xx, int yy) { int idx=yy*REGW+xx; return (a[idx>>3]>>(idx&7))&1; }

// page change: hold the indicator, then dither-crossfade it out / bars in
void playTransition() {
  if (!oled) return;
  oled->clearBuffer(); drawIndicator(); capRegion(indBits);
  oled->clearBuffer(); drawBars();      capRegion(barBits);
  const int STEPS = 7;
  for (int f=0; f<=STEPS; f++) {
    int T = (int)((1.0f - (float)f/STEPS) * 16.0f);
    oled->clearBuffer(); drawBody();
    for (int yy=0; yy<REGH; yy++) for (int xx=0; xx<REGW; xx++) {
      int bit = (bayer4[yy&3][xx&3] < T) ? getBit(indBits,xx,yy) : getBit(barBits,xx,yy);
      if (bit) oled->drawPixel(REGX0+xx, REGY0+yy);
    }
    oled->sendBuffer();
    delay(f==0 ? 380 : 45);
  }
}

// Kali boot splash: dragon + blinking prompt. Returns true if DOWN held (=wifi setup).
bool bootSplash() {
  if (!oled) return false;
  unsigned long bl = 0; bool show = true; bool setupReq = false;
  for (;;) {
    if (millis() - bl > 500) {
      bl = millis(); show = !show;
      oled->clearBuffer();
      oled->drawXBMP((128-KALI_W)/2, 0, KALI_W, KALI_H, kali_bits);
      oled->setFont(u8g2_font_5x7_tf);
      if (show) oled->drawStr(31, 63, "press any key");
      else      oled->drawStr(21, 63, "hold DOWN = setup");
      oled->sendBuffer();
    }
    if (digitalRead(PIN_DOWN) == LOW) {                 // maybe a long-hold for setup
      unsigned long t = millis();
      while (digitalRead(PIN_DOWN) == LOW) { if (millis()-t > 1200) { setupReq = true; break; } delay(10); }
      break;
    }
    if (digitalRead(PIN_LEFT)==LOW || digitalRead(PIN_RIGHT)==LOW) break;
    delay(15);
  }
  delay(60);
  sL = digitalRead(PIN_LEFT); sR = digitalRead(PIN_RIGHT); sD = digitalRead(PIN_DOWN);
  return setupReq;
}

// blocking config portal - only when the user asks or no creds are stored
void startPortal() {
  splash("WiFi setup", "join DeckDash-Setup");
  WiFiManager wm;
  WiFiManagerParameter pUrl("url", "Pi telemetry URL", teleUrl, sizeof(teleUrl)-1);
  wm.addParameter(&pUrl);
  wm.setConfigPortalTimeout(180);
  wm.startConfigPortal("DeckDash-Setup");
  strncpy(teleUrl, pUrl.getValue(), sizeof(teleUrl)-1);
}

void setup() {
  Serial.begin(115200); delay(200);
  pinMode(PIN_LEFT, INPUT_PULLUP);
  pinMode(PIN_RIGHT, INPUT_PULLUP);
  pinMode(PIN_DOWN, INPUT_PULLUP);
  if (detectOLED()) {
    oled = new U8G2_SH1106_128X64_NONAME_F_SW_I2C(U8G2_R0, SCLp, SDAp, U8X8_PIN_NONE);
    oled->begin();
  }
  bool wantSetup = bootSplash();               // Kali logo; hold DOWN = wifi setup
  WiFi.mode(WIFI_STA);
  WiFi.persistent(true);
  WiFi.setAutoReconnect(true);
  if (wantSetup || WiFi.SSID().length() == 0) startPortal();  // only blocks when asked / unconfigured
  else WiFi.begin();                           // saved creds, NON-blocking -> straight to dashboard
  render();
}

void loop() {
  bool changed = false;
  if (fell(PIN_LEFT,  sL)) { page = (page + NPAGES - 1) % NPAGES; playTransition(); }
  if (fell(PIN_RIGHT, sR)) { page = (page + 1) % NPAGES;         playTransition(); }
  if (fell(PIN_DOWN,  sD)) {                    // tap = refresh, hold ~1.5s = wifi setup
    unsigned long t = millis();
    while (digitalRead(PIN_DOWN)==LOW && millis()-t < 1500) delay(10);
    if (digitalRead(PIN_DOWN)==LOW) { startPortal(); WiFi.begin(); }
    else { splash("DeckDash","refreshing..."); }
    lastFetch = 0; delay(80);
    sD = digitalRead(PIN_DOWN);
  }

  if (millis() - lastFetch > 3000) { fetch(); lastFetch = millis(); changed = true; }
  if (changed) render();
  delay(15);
}
