package com.cyberdeck.launcher;

import android.app.Activity;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.util.ArrayList;
import java.util.Locale;

/**
 * Cyberdeck launcher - home screen for the phone acting as the deck's display.
 *
 * Typography: Corpta DEMO is LETTERS ONLY (no digits, no punctuation), so it
 * carries display text; Chakra Petch renders anything numeric.
 */
public class MainActivity extends Activity {

    private static final String VNC_PKG  = "com.gaurav.avnc.debug";
    private static final String VNC_HOST = "127.0.0.1";
    private static final int    VNC_PORT = 5900;
    private static final String VNC_URI  = "vnc://127.0.0.1:5900/?ViewOnly=false";

    private static final int BG      = 0xFF05080C;
    private static final int GRID    = 0xFF0B1219;
    private static final int KALI    = 0xFF367BF0;
    private static final int KALI_HI = 0xFF5B96F5;
    private static final int CYAN    = 0xFF22D3EE;
    private static final int GREEN_LED = 0xFF35E06B;
    private static final int MAGENTA = 0xFFFF2E88;
    private static final int DIM     = 0xFF3D5468;
    private static final int FG      = 0xFFE8F1FA;
    private static final int GREEN   = 0xFF39E88A;
    private static final int AMBER   = 0xFFFFB020;
    private static final int AMBER_HI = 0xFFFFC94D;
    private static final int RED     = 0xFFFF3B54;
    private static final int RED_HI   = 0xFFFF6478;
    private static final int RING       = 0xFF0E1B29;
    private static final int KALI_FAINT = 0xFF1B4A8C;
    private static final int CYAN_FAINT = 0xFF14464F;
    private static final int GRAPH_GRID = 0xFF10202E;
    private static final int GRAPH_FILL = 0xFF16345F;

    private static final int SAMPLE_MS  = 250;   // graph cadence
    private static final int SLOW_EVERY = 12;    // battery/link every 12th = 3s
    private static final int FRAME_MS   = 40;    // ring animation ~25fps
    private static final int TELEM_MS   = 2000;  // pi telemetry poll

    // Second reverse tunnel, separate from the VNC one: adb reverse tcp:9000.
    private static final String TELEM_URL   = "http://127.0.0.1:9000/?since=";
    private static final String LOCK_URL    = "http://127.0.0.1:9000/lock";
    private static final String WAKE_URL    = "http://127.0.0.1:9000/wake";
    private static final String POWEROFF_URL = "http://127.0.0.1:9000/poweroff";
    private static final String REBOOT_URL   = "http://127.0.0.1:9000/reboot";
    private static final long   TELEM_STALE = 9000;
    private static final float  TEMP_WARM   = 62f;
    private static final float  TEMP_HOT    = 72f;
    private static final String NO_TEMP     = "--.-°C";

    private TextView status, powerVal, powerState, linkVal, speedVal, tempVal, ramVal;
    private ClockView clock, uptimeVal;
    private long uptimeBaseSec = -1, uptimeBaseElapsed;
    private long clockOffset;            // Pi epoch minus phone epoch, so the ticking phone clock reads Pi time
    private boolean clockSynced;
    private final java.text.SimpleDateFormat clockFmt =
            new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US);
    private Typeface corpta, numeral;
    private SpeedGraph graph;
    private Rings rings;
    private Stream stream;
    private SshPanel sshPanel;
    private LinearLayout uvBadge;
    private TextView uvLabel;
    private TextView battVolts, battAmps;
    private volatile boolean piUnderVolt = false;
    private int sshVer = -1;
    private int cmdVer = -1;
    private final Handler poll  = new Handler();
    private final Handler anim  = new Handler();
    private final Handler telem = new Handler();
    private int ticks = 0;

    private int  telemSeq = 0;
    private long telemAt  = 0L;    // elapsedRealtime of the last good fetch
    private boolean telemUp = false;
    private boolean telemNotedDown = false;
    private volatile float piTemp = -1f;   // last reading, held between polls
    private volatile float battMa = Float.NaN;  // NaN = no reading; 0 is a real value

    private long lastRx = -1L, lastTs = 0L;
    private float ema = -1f;                     // smoothed sample
    private long lastLiveMs = 0L;
    private long connectingSince = 0L;
    private Button startBtn;
    // Anti-flicker only. This was 30s, which meant the launcher kept claiming
    // CONNECTED for half a minute after the session was genuinely gone - and
    // while lightdm was being restarted every ~50s, that grace covered most of
    // each outage. Long enough to ride out socket churn, short enough not to lie.
    private static final long LIVE_GRACE_MS  = 4000;
    private static final long CONNECT_MAX_MS = 22000;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            sampleGraph();
            if (ticks % SLOW_EVERY == 0) refreshStats();
            ticks++;
            poll.postDelayed(this, SAMPLE_MS);
        }
    };

    private final Runnable frame = new Runnable() {
        @Override public void run() {
            if (rings != null) rings.step();
            anim.postDelayed(this, FRAME_MS);
        }
    };

    private final Runnable telemTick = new Runnable() {
        @Override public void run() {
            fetchTelemetry();
            telem.postDelayed(this, TELEM_MS);
        }
    };

    // ------------------------------------------------------------------ chip

    private static class Chip extends Drawable {
        static final int BR = 0, BL = 1;               // which bottom corner is chamfered
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final int chip;
        private final int corner;
        Chip(int colour, int chip) { this(colour, chip, BR); }
        Chip(int colour, int chip, int corner) { p.setColor(colour); this.chip = chip; this.corner = corner; }
        @Override public void draw(Canvas c) {
            Rect b = getBounds();
            path.reset();
            if (corner == BL) {                        // cut the bottom-LEFT corner
                path.moveTo(b.left,  b.top);
                path.lineTo(b.right, b.top);
                path.lineTo(b.right, b.bottom);
                path.lineTo(b.left + chip, b.bottom);
                path.lineTo(b.left,  b.bottom - chip);
            } else {                                   // cut the bottom-RIGHT corner
                path.moveTo(b.left,  b.top);
                path.lineTo(b.right, b.top);
                path.lineTo(b.right, b.bottom - chip);
                path.lineTo(b.right - chip, b.bottom);
                path.lineTo(b.left,  b.bottom);
            }
            path.close();
            c.drawPath(path, p);
        }
        @Override public void setAlpha(int a) { p.setAlpha(a); }
        @Override public void setColorFilter(ColorFilter f) { p.setColorFilter(f); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /** Power symbol (IEC 5009) drawn as a path - Android 6's fonts have no U+23FB glyph. */
    private static class PowerIcon extends Drawable {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        PowerIcon() {
            p.setColor(Color.WHITE);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
        }
        @Override public void draw(Canvas c) {
            Rect b = getBounds();
            float cx = b.exactCenterX(), cy = b.exactCenterY();
            float r = Math.min(b.width(), b.height()) * 0.24f;
            p.setStrokeWidth(r * 0.36f);
            float gap = 66f;                        // opening at the top, where the bar passes
            c.drawArc(cx - r, cy - r, cx + r, cy + r, -90f + gap / 2f, 360f - gap, false, p);
            c.drawLine(cx, cy - r * 1.34f, cx, cy + r * 0.02f, p);   // the vertical bar
        }
        @Override public void setAlpha(int a) { p.setAlpha(a); }
        @Override public void setColorFilter(ColorFilter f) { p.setColorFilter(f); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /** Reboot glyph - a bold circular arrow with a solid arrowhead, drawn so it reads clearly
        different from the thin rotate arrow and needs no font glyph. */
    private static class RebootIcon extends Drawable {
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path head = new Path();
        private final float rot;
        private final boolean bar;
        private final float arcExtra;
        RebootIcon(float rot, boolean bar, float arcExtra) {
            this.rot = rot;
            this.bar = bar;
            this.arcExtra = arcExtra;
            stroke.setColor(Color.WHITE);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeCap(Paint.Cap.BUTT);
            fill.setColor(Color.WHITE);
            fill.setStyle(Paint.Style.FILL);
        }
        @Override public void draw(Canvas c) {
            Rect b = getBounds();
            float cx = b.exactCenterX(), cy = b.exactCenterY();
            c.save();
            c.rotate(rot, cx, cy);
            float r = Math.min(b.width(), b.height()) * 0.23f;
            float sw = r * 0.36f;
            stroke.setStrokeWidth(sw);
            stroke.setStrokeCap(Paint.Cap.BUTT);
            c.save();
            c.rotate(arcExtra, cx, cy);                       // rotate the ARROW only; the bar stays put
            float sweep = bar ? 236f : 288f;                  // wider gap with the bar so they don't touch
            float start = 450f - sweep / 2f;                  // keep the gap centred on the top
            c.drawArc(cx - r, cy - r, cx + r, cy + r, start, sweep, false, stroke);
            double a = Math.toRadians(start + sweep);         // arrowhead at the arc end
            float px = cx + (float)(r * Math.cos(a));
            float py = cy + (float)(r * Math.sin(a));
            float tx = -(float)Math.sin(a), ty = (float)Math.cos(a);   // clockwise tangent
            float nx =  (float)Math.cos(a), ny = (float)Math.sin(a);   // outward radial
            float al = sw * 2.6f, aw = sw * 1.9f;
            head.reset();
            head.moveTo(px + tx * al, py + ty * al);          // tip, along the tangent
            head.lineTo(px + nx * aw, py + ny * aw);          // outer base
            head.lineTo(px - nx * aw, py - ny * aw);          // inner base
            head.close();
            c.drawPath(head, fill);
            c.restore();                              // end arrow-only rotation; bar is drawn upright
            if (bar) {                                // power bar through the top gap -> restart glyph
                stroke.setStrokeCap(Paint.Cap.ROUND);
                c.drawLine(cx, cy - r * 1.34f, cx, cy + r * 0.02f, stroke);
            }
            c.restore();
        }
        @Override public void setAlpha(int a) { stroke.setAlpha(a); fill.setAlpha(a); }
        @Override public void setColorFilter(ColorFilter f) { stroke.setColorFilter(f); fill.setColorFilter(f); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    // ------------------------------------------------------- static backdrop

    /** Grid and corner brackets. Static, so it never repaints. */
    private class Hud extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        Hud() { super(MainActivity.this); }
        @Override protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1f);
            p.setColor(GRID);
            for (int x = 0; x < w; x += 64) c.drawLine(x, 0, x, h, p);
            for (int y = 0; y < h; y += 64) c.drawLine(0, y, w, y, p);

            int m = 28, L = 60;
            int[][] corners = {{m, m, 1, 1}, {w - m, m, -1, 1},
                               {m, h - m, 1, -1}, {w - m, h - m, -1, -1}};
            for (int[] k : corners) {
                p.setColor(CYAN);
                p.setStrokeWidth(4f);
                c.drawLine(k[0], k[1], k[0] + k[2] * L, k[1], p);
                c.drawLine(k[0], k[1], k[0], k[1] + k[3] * L, p);
                p.setColor(MAGENTA);
                p.setStrokeWidth(3f);
                c.drawLine(k[0] + k[2] * 10, k[1] + k[3] * 18,
                           k[0] + k[2] * 10, k[1] + k[3] * 40, p);
            }
        }
    }

    /**
     * Rotating arc rings. Kept in their own view so the animation repaints a
     * handful of arcs rather than the whole grid every frame.
     */
    private class Rings extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private float a = 0f;
        // radius, sweep, degrees per frame (sign = direction), colour, thickness
        // The moving arcs are deliberately much heavier than the static circles
        // they ride on, so the rotation reads at a glance.
        private final float[][] spec = {
            {110,  70, +1.30f, 0, 11f}, {165, 120, -0.85f, 1, 9f},
            {225,  95, +0.55f, 0, 13f}, {300, 140, -0.35f, 1, 8f},
            {225, 200, +0.18f, 2,  5f}, {300,  40, +1.90f, 2, 10f},
        };
        Rings() { super(MainActivity.this); }

        void step() { a += 1f; invalidate(); }

        @Override protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            float cx = w * 0.74f, cy = h * 0.42f;

            // thin static circles first, so the heavy arcs sit on top of them
            p.setStyle(Paint.Style.STROKE);
            p.setColor(RING);
            p.setStrokeWidth(1.5f);
            for (float rad : new float[]{110, 165, 225, 300}) c.drawCircle(cx, cy, rad, p);

            p.setStrokeCap(Paint.Cap.ROUND);
            for (float[] s : spec) {
                float rad = s[0], sweep = s[1], spin = s[2];
                int kind = (int) s[3];
                p.setColor(kind == 0 ? KALI_FAINT : kind == 1 ? CYAN_FAINT : RING);
                p.setStrokeWidth(s[4]);
                r.set(cx - rad, cy - rad, cx + rad, cy + rad);
                c.drawArc(r, (a * spin) % 360f, sweep, false, p);
            }
            p.setStrokeCap(Paint.Cap.BUTT);
        }
    }

    // ----------------------------------------------------------- speed graph

    /**
     * Rolling link graph.
     *
     * Fills left-to-right while the buffer is filling, then scrolls with the
     * newest sample pinned to the right edge. The vertical scale follows the
     * visible peak so a quiet link still shows detail and a burst cannot clip.
     *
     * The trace is drawn as a quadratic spline through sample midpoints rather
     * than straight segments, so it reads as a curve instead of a zigzag.
     */
    private class SpeedGraph extends View {
        private static final int N = 170;
        /** Full graph height is never less than this, so idle reads as idle. */
        private static final float MIN_SCALE = 48f;   // KB/s
        private final float[] s = new float[N];
        /** Pi temperature on the same x-axis as the rate, so they overlay. */
        private final float[] t = new float[N];
        /**
         * Adaptive window rather than a fixed 30-85C axis. On a fixed axis the
         * SoC's real behaviour - drifting a couple of degrees under load - was
         * about 3% of the box height and read as a dead straight line.
         *
         * The span floor is what keeps it honest: the window never closes
         * tighter than 6C, so sensor noise of a tenth of a degree cannot be
         * stretched to full height. Flat still looks flat; a 2C move now reads
         * as a third of the box.
         */
        private static final float T_MIN_SPAN = 6f;
        private float tLo = 45f, tHi = 65f;

        /**
         * Phone battery current, third trace in the same box.
         *
         * NaN means "no reading" - unlike temperature, 0 mA is a perfectly
         * valid measurement here, so zero cannot double as the missing marker.
         */
        private final float[] a = new float[N];
        private static final float A_MIN_SPAN = 60f;     // mA
        private float aLo = -50f, aHi = 50f;
        private int count = 0;
        private float scale = 32f;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final Path tpath = new Path();

        SpeedGraph() { super(MainActivity.this); }

        void push(float v, float temp, float ma) {
            if (count < N) { s[count] = v; t[count] = temp; a[count] = ma; count++; }
            else {
                System.arraycopy(s, 1, s, 0, N - 1); s[N - 1] = v;
                System.arraycopy(t, 1, t, 0, N - 1); t[N - 1] = temp;
                System.arraycopy(a, 1, a, 0, N - 1); a[N - 1] = ma;
            }
            float peak = 0f;
            for (int i = 0; i < count; i++) peak = Math.max(peak, s[i]);
            float target = Math.max(MIN_SCALE, peak * 1.30f);
            scale += (target - scale) * 0.12f;      // gentle: no axis snapping
            // Floor the axis. Without this, an idle link's few hundred bytes of
            // keepalive chatter got stretched to full height and looked like a
            // busy signal - the graph was drawing noise as if it were data.
            if (scale < MIN_SCALE) scale = MIN_SCALE;
            retrack();
            invalidate();
        }

        private float yOf(int i, int h) {
            return h - Math.min(1f, s[i] / scale) * (h - 4) - 2;
        }

        /**
         * Temperature gets a fixed 30-85C axis rather than the rate's adaptive
         * one. An auto-scaled temperature trace would swing wildly over a two
         * degree drift and read as a crisis; on a fixed axis, flat means flat.
         */
        private float tOf(int i, int h) {
            float span = Math.max(1f, tHi - tLo);
            float v = Math.max(tLo, Math.min(tHi, t[i]));
            return h - ((v - tLo) / span) * (h - 4) - 2;
        }

        private float aOf(int i, int h) {
            float span = Math.max(1f, aHi - aLo);
            float v = Math.max(aLo, Math.min(aHi, a[i]));
            return h - ((v - aLo) / span) * (h - 4) - 2;
        }

        /** Re-aim the temperature window at what is actually on screen. */
        private void retrack() {
            float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
            for (int i = 0; i < count; i++) {
                if (t[i] <= 0f) continue;               // no reading, not a zero
                if (t[i] < lo) lo = t[i];
                if (t[i] > hi) hi = t[i];
            }
            if (hi < lo) return;                        // nothing valid yet
            float mid  = (lo + hi) / 2f;
            float span = Math.max(T_MIN_SPAN, (hi - lo) * 1.6f);
            // Eased, like the rate axis: snapping the window would make the
            // trace jump sideways every time a new min or max arrived.
            tLo += ((mid - span / 2f) - tLo) * 0.10f;
            tHi += ((mid + span / 2f) - tHi) * 0.10f;

            float alo = Float.MAX_VALUE, ahi = -Float.MAX_VALUE;
            for (int i = 0; i < count; i++) {
                if (Float.isNaN(a[i])) continue;
                if (a[i] < alo) alo = a[i];
                if (a[i] > ahi) ahi = a[i];
            }
            if (ahi < alo) return;
            float amid  = (alo + ahi) / 2f;
            float aspan = Math.max(A_MIN_SPAN, (ahi - alo) * 1.6f);
            aLo += ((amid - aspan / 2f) - aLo) * 0.10f;
            aHi += ((amid + aspan / 2f) - aHi) * 0.10f;
        }

        @Override protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;

            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1f);
            p.setColor(GRAPH_GRID);
            for (int i = 0; i <= 4; i++) c.drawLine(0, h * i / 4f, w, h * i / 4f, p);
            for (float x = 0; x <= w; x += 48) c.drawLine(x, 0, x, h, p);

            if (count < 3) return;

            float step = w / (float) (N - 1);
            float x0 = w - (count - 1) * step;

            // ---- smooth spline through midpoints ----
            path.reset();
            path.moveTo(x0, yOf(0, h));
            for (int i = 1; i < count; i++) {
                float px = x0 + (i - 1) * step, py = yOf(i - 1, h);
                float cxm = x0 + (i - 0.5f) * step, cym = (py + yOf(i, h)) / 2f;
                path.quadTo(px, py, cxm, cym);
            }
            path.lineTo(x0 + (count - 1) * step, yOf(count - 1, h));

            // fill under the curve
            Path fill = new Path(path);
            fill.lineTo(x0 + (count - 1) * step, h);
            fill.lineTo(x0, h);
            fill.close();
            p.setStyle(Paint.Style.FILL);
            p.setColor(GRAPH_FILL);
            c.drawPath(fill, p);

            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2.5f);
            p.setColor(KALI);
            c.drawPath(path, p);

            // ---- battery current, overlaid ----
            tpath.reset();
            boolean aopen = false;
            for (int i = 0; i < count; i++) {
                if (Float.isNaN(a[i])) { aopen = false; continue; }
                float x = x0 + i * step, y = aOf(i, h);
                if (!aopen) { tpath.moveTo(x, y); aopen = true; } else tpath.lineTo(x, y);
            }
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2f);
            p.setColor(AMBER);
            p.setAlpha(150);
            c.drawPath(tpath, p);
            p.setAlpha(255);

            // ---- temperature, overlaid on the same box ----
            tpath.reset();
            boolean open = false;
            for (int i = 0; i < count; i++) {
                if (t[i] <= 0f) { open = false; continue; }   // no reading, no line
                float x = x0 + i * step, y = tOf(i, h);
                if (!open) { tpath.moveTo(x, y); open = true; } else tpath.lineTo(x, y);
            }
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2f);
            p.setColor(RED);
            p.setAlpha(165);
            c.drawPath(tpath, p);
            p.setAlpha(255);

            float lx = x0 + (count - 1) * step, ly = yOf(count - 1, h);
            p.setStyle(Paint.Style.FILL);
            p.setColor(CYAN);
            c.drawRect(lx - 2, ly - 2, lx + 2, h, p);
        }
    }

    // ----------------------------------------------------- pi process stream

    /**
     * Live column of processes starting on the Pi, newest at the bottom,
     * fading out as they scroll up.
     *
     * Counterweight to the rings: they are round, centred and to the right, so
     * this is linear, edge-hugging and to the left rather than a mirror image.
     *
     * Set in Corpta like the rest of the deck. Corpta DEMO carries letters
     * only, so digits and punctuation drop to the system fallback - and since
     * it has no lowercase either, commands are upper-cased on the way in.
     */
    private class Stream extends View {
        private static final int MAX = 24;
        private final ArrayList<String> lines = new ArrayList<String>();
        private final Paint p    = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint rule = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float lh;
        private final float indent;

        Stream() {
            super(MainActivity.this);
            float sd = getResources().getDisplayMetrics().scaledDensity;
            p.setTypeface(corpta);
            p.setTextSize(8f * sd);
            p.setLetterSpacing(0.06f);
            lh = p.getTextSize() * 1.62f;
            indent = 22f;
            rule.setColor(0xFF17293C);
            rule.setStrokeWidth(2f);
        }

        void push(String s) {
            lines.add(s.toUpperCase(Locale.US));
            while (lines.size() > MAX) lines.remove(0);
            invalidate();
        }

        @Override protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();

            // spine down the left edge, with a tick per line slot
            c.drawLine(0, 0, 0, h, rule);
            for (float y = h - 4; y > 0; y -= lh) c.drawLine(0, y, 7, y, rule);

            int n = lines.size();
            float y = h - 6;
            float textW = w - indent;
            for (int i = n - 1; i >= 0 && y > lh; i--) {
                int age = n - 1 - i;
                float f = 1f - age / (float) MAX;              // 1 = newest
                int alpha = (int) (255f * (0.06f + 0.72f * f * f));
                p.setColor(age == 0 ? CYAN : DIM);
                p.setAlpha(alpha);
                String s = lines.get(i);
                int fits = p.breakText(s, true, textW, null);  // clip, never wrap
                c.drawText(s, 0, fits, indent, y, p);
                if (age == 0) {
                    p.setColor(CYAN);
                    c.drawRect(9, y - p.getTextSize() * 0.55f, 15,
                               y - p.getTextSize() * 0.15f, p);
                }
                y -= lh;
            }
        }
    }

    // ------------------------------------------------------- ssh session panel

    /**
     * What is running over SSH on the Pi right now, in the band between the
     * settings link and the graph. Sessions are found by walking /proc for
     * sshd's per-session processes and collecting their descendants, so this is
     * live fact rather than a log.
     */
    private class SshPanel extends View {
        private static final int MAX = 3;
        private final ArrayList<String> lines = new ArrayList<String>();
        private int sessions = -1;                 // -1 = telemetry unavailable
        private int hidden = 0;
        private final Paint p    = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint rule = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float lh, tag = 92f, gap = 18f;

        SshPanel() {
            super(MainActivity.this);
            float sd = getResources().getDisplayMetrics().scaledDensity;
            p.setTypeface(corpta);
            p.setTextSize(7f * sd);
            p.setLetterSpacing(0.08f);
            lh = p.getTextSize() * 1.32f;
            rule.setColor(0xFF17293C);
            rule.setStrokeWidth(2f);
        }

        /**
         * @param total how many are actually running, which can exceed cmds -
         *              the payload caps the list, and counting overflow off the
         *              capped list would quietly under-report it.
         */
        void set(int sessions, ArrayList<String> cmds, int total) {
            this.sessions = sessions;
            if (total < cmds.size()) total = cmds.size();
            lines.clear();
            // leave a row free for the overflow note rather than silently
            // truncating - a hidden command is worse than a shorter list
            int show = Math.min(cmds.size(), total > MAX ? MAX - 1 : MAX);
            for (int i = 0; i < show; i++) lines.add(cmds.get(i).toUpperCase(Locale.US));
            hidden = total - show;
            invalidate();
        }

        @Override protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            float ts = p.getTextSize();
            c.drawLine(tag, 0, tag, h, rule);

            p.setColor(DIM);
            c.drawText("CMD", 0, ts, p);
            p.setColor(sessions > 0 ? CYAN : DIM);
            c.drawText(sessions < 0 ? "--" : String.valueOf(sessions), 0, ts + lh, p);

            float x = tag + gap, textW = w - x;
            if (lines.isEmpty()) {
                p.setColor(DIM);
                c.drawText(sessions < 0 ? "NO TELEMETRY" : "IDLE", x, ts, p);
                return;
            }
            float y = ts;
            for (int i = 0; i < lines.size(); i++) {
                p.setColor(i == 0 ? CYAN : DIM);
                p.setAlpha(i == 0 ? 235 : 190);
                String s = lines.get(i);
                c.drawText(s, 0, p.breakText(s, true, textW, null), x, y, p);
                y += lh;
            }
            if (hidden > 0 && y <= h) {
                p.setColor(DIM);
                p.setAlpha(150);
                c.drawText("+" + hidden + " MORE", x, y, p);
            }
        }
    }

    // ------------------------------------------------------------- lifecycle

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // Lock auto-rotate off and apply the SAVED rotation. Safe to run on every create: the
        // button keeps the saved value in sync with the live rotation (it writes sp AND sets the
        // system value together), so at runtime this just re-applies the current value = a no-op,
        // never a revert. But on boot - where the phone comes up in the wrong rotation - it
        // corrects it back to what the user last chose. No running checker.
        try {
            int saved = getSharedPreferences("deck", MODE_PRIVATE).getInt("rot", 3);
            Settings.System.putInt(getContentResolver(),
                    Settings.System.ACCELEROMETER_ROTATION, 0);
            Settings.System.putInt(getContentResolver(),
                    Settings.System.USER_ROTATION, saved);
        } catch (Exception e) { }

        corpta  = load("CorptaDEMO.otf",       Typeface.DEFAULT_BOLD);
        numeral = load("ChakraPetch-Bold.ttf", Typeface.MONOSPACE);

        FrameLayout stack = new FrameLayout(this);
        stack.setBackgroundColor(BG);
        FrameLayout.LayoutParams full = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        stack.addView(new Hud(), full);
        rings = new Rings();
        stack.addView(rings, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Top row in three zones - power at the left corner, link state dead
        // centre because it is the one thing worth reading from across the
        // room, temperature at the right corner. Splitting them means each is
        // anchored to something fixed, so none of them can push the others
        // around. Rate moved down to sit with the graph it feeds.
        LinearLayout lz = row();
        lz.addView(cell("TEMP", DIM, 13, corpta, 0.16f, 0));
        tempVal = cell(NO_TEMP, DIM, 16, numeral, 0f, 12);       lz.addView(tempVal);
        metricAnchor(lz);
        stack.addView(lz, topLp(Gravity.TOP | Gravity.START, 48, 0));

        LinearLayout cz = row();
        cz.addView(cell("LINK", DIM, 13, corpta, 0.16f, 0));
        linkVal = cell("", DIM, 16, corpta, 0.14f, 14);          cz.addView(linkVal);
        // fixed box, centred text: the zone stays put whichever word is in it
        linkVal.setGravity(Gravity.CENTER);
        // CONNECTED itself is what should sit on the screen's centre line, not
        // the LINK label plus the word. Centring the zone as a whole pushed the
        // word right by half the label. A transparent copy of the label on the
        // far side balances it, so the value lands dead centre and stays there
        // whatever the label is.
        cz.addView(cell("LINK", 0x00000000, 13, corpta, 0.16f, 14));
        metricAnchor(cz);
        stack.addView(cz, topLp(Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, 0));

        // UPTIME above LINK/CONNECTED; value dead-centred like CONNECTED (transparent label balances
        // the visible one), and a ClockView so the ticking seconds don't jitter.
        LinearLayout uz = row();
        uz.addView(cell("UPTIME", DIM, 13, corpta, 0.16f, 0));
        uptimeVal = new ClockView(16 * getResources().getDisplayMetrics().scaledDensity, CYAN);
        LinearLayout.LayoutParams uvLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        uvLp.leftMargin = 14;
        uz.addView(uptimeVal, uvLp);
        uz.addView(cell("UPTIME", 0x00000000, 13, corpta, 0.16f, 14));   // transparent copy -> value centred
        metricAnchor(uz);
        FrameLayout.LayoutParams uzLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        uzLp.setMargins(0, 44, 0, 0);
        stack.addView(uz, uzLp);

        LinearLayout rz = row();
        rz.addView(cell("POWER", DIM, 13, corpta, 0.16f, 0));
        powerVal   = cell("", FG, 16, numeral, 0f, 12);          rz.addView(powerVal);
        powerState = cell("", DIM, 13, corpta, 0.14f, 12);       rz.addView(powerState);
        metricAnchor(rz);
        stack.addView(rz, topLp(Gravity.TOP | Gravity.END, 0, 48));

        // Pi-synced clock in the top-left corner (fed by the telemetry TIME line, so it reads the
        // Pi's time, not the phone's - the Nexus 5 has no network to keep its own clock right).
        clock = new ClockView(22 * getResources().getDisplayMetrics().scaledDensity, Color.WHITE);
        clock.setOnClickListener(new View.OnClickListener() {   // tap the clock -> big centred clock
            @Override public void onClick(View v) { showBigClock(stack); }
        });
        FrameLayout.LayoutParams clkLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.START);
        clkLp.setMargins(58, 26, 0, 0);
        stack.addView(clock, clkLp);
        // tick every second: the phone's clock ticks accurately, we just offset it to Pi time
        final Handler clockH = new Handler();
        clockH.post(new Runnable() {
            @Override public void run() {
                // always show a time: Pi's when synced, otherwise the phone's own clock as fallback
                clock.setTime(clockFmt.format(new java.util.Date(
                        System.currentTimeMillis() + (clockSynced ? clockOffset : 0))));
                if (uptimeBaseSec >= 0) {
                    long s = uptimeBaseSec + (SystemClock.elapsedRealtime() - uptimeBaseElapsed) / 1000;
                    uptimeVal.setTime(String.format(java.util.Locale.US, "%02d:%02d:%02d",
                            s / 3600, (s % 3600) / 60, s % 60));
                }
                clockH.postDelayed(this, 1000);
            }
        });

        // Undervoltage badge, in the gap ABOVE the power row. Anchored on its
        // own rather than stacked into rz, so appearing and disappearing never
        // shifts POWER off the baseline it shares with TEMP and LINK.
        //
        // The triangle is android.R.drawable.ic_dialog_alert - the platform's
        // own alert icon, already on the device and drawn by people who draw
        // icons, rather than something approximated with canvas primitives.
        uvBadge = new LinearLayout(this);
        uvBadge.setOrientation(LinearLayout.HORIZONTAL);
        uvBadge.setGravity(Gravity.CENTER_VERTICAL);
        uvBadge.setBackgroundColor(RED);
        uvBadge.setPadding(14, 8, 18, 8);
        ImageView warn = new ImageView(this);
        warn.setImageResource(android.R.drawable.ic_dialog_alert);
        warn.setColorFilter(0xFF000000);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(38, 38);
        wlp.rightMargin = 12;
        uvBadge.addView(warn, wlp);
        uvLabel = new TextView(this);
        uvLabel.setText("UNDERVOLTAGE");
        uvLabel.setTextColor(0xFF000000);
        uvLabel.setTextSize(13);
        uvLabel.setTypeface(corpta);
        uvLabel.setLetterSpacing(0.14f);
        uvBadge.addView(uvLabel);
        uvBadge.setVisibility(View.GONE);
        FrameLayout.LayoutParams ulp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        ulp.setMargins(0, 26, 48, 0);
        stack.addView(uvBadge, ulp);

        // Volts / amps, right-aligned under the power row.
        LinearLayout vz = row();
        vz.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        battVolts = cell("", DIM, 13, numeral, 0f, 0);           vz.addView(battVolts);
        battAmps  = cell("", DIM, 13, numeral, 0f, 16);          vz.addView(battAmps);
        FrameLayout.LayoutParams vlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        vlp.setMargins(0, 150, 48, 0);
        stack.addView(vz, vlp);

        // Three square HUD buttons, top-right, same recipe/height as START CYBERDECK.
        // Left->right: POWER OFF (red), REBOOT (amber), ROTATE (blue). Power and reboot
        // confirm first and reach the Pi over the :9000 telemetry channel; rotate is local.
        // Chip corners: the two action buttons cut bottom-right, rotate cuts bottom-left.
        final FrameLayout host = stack;
        Button rotate = squareBtn("", KALI, KALI_HI, Chip.BL, 0, new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    android.content.SharedPreferences sp =
                            getSharedPreferences("deck", MODE_PRIVATE);
                    // toggle from the ACTUAL current rotation, not the saved value - otherwise if
                    // they drift out of sync the button flips from the wrong start and looks like
                    // it did nothing / went back.
                    int cur = Settings.System.getInt(getContentResolver(),
                            Settings.System.USER_ROTATION, 3);
                    int next = (cur == 3) ? 1 : 3;
                    sp.edit().putInt("rot", next).commit();  // sync write: keep saved in sync for boot restore
                    Settings.System.putInt(getContentResolver(),
                            Settings.System.ACCELEROMETER_ROTATION, 0);
                    Settings.System.putInt(getContentResolver(),
                            Settings.System.USER_ROTATION, next);
                } catch (Exception e) { toast("grant WRITE_SETTINGS to rotate"); }
            }
        });
        rotate.setForeground(new RebootIcon(130, false, 0));   // back to the plain circular arrow
        rotate.setForegroundGravity(Gravity.FILL);
        Button reboot = squareBtn("", AMBER, AMBER_HI, Chip.BL, 0, new View.OnClickListener() {
            @Override public void onClick(View v) {
                confirm(host, "REBOOT", AMBER, new Runnable() {
                    @Override public void run() { poke(REBOOT_URL); toast("rebooting deck…"); }
                });
            }
        });
        reboot.setForeground(new RebootIcon(0, true, -25));   // restart glyph, arrow tuned a bit CCW
        reboot.setForegroundGravity(Gravity.FILL);
        Button power = squareBtn("", RED, RED_HI, Chip.BL, 0, new View.OnClickListener() {
            @Override public void onClick(View v) {
                confirm(host, "POWER OFF", RED, new Runnable() {
                    @Override public void run() { poke(POWEROFF_URL); toast("powering off deck…"); }
                });
            }
        });
        power.setForeground(new PowerIcon());        // drawn power symbol (no font glyph)
        power.setForegroundGravity(Gravity.FILL);    // full bounds; the icon centres itself
        addSquare(stack, rotate, 299);    // stacked vertically, centred between volts and rate
        addSquare(stack, reboot, 435);    // 136 step = 120 button + 16 gap (spacing unchanged)
        addSquare(stack, power,  571);

        LinearLayout rate = row();
        rate.addView(cell("RATE", DIM, 13, corpta, 0.16f, 0));
        speedVal = cell("", CYAN, 16, numeral, 0f, 12);          rate.addView(speedVal);
        FrameLayout.LayoutParams rlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.END);
        // clears the graph: 132 tall on a 62 bottom margin, plus a little air
        rlp.setMargins(0, 0, 58, 62 + 132 + 12);
        stack.addView(rate, rlp);

        // RAM usage, sitting just above the RATE readout
        LinearLayout mz = row();
        mz.addView(cell("RAM", DIM, 13, corpta, 0.16f, 0));
        ramVal = cell("--", CYAN, 16, numeral, 0f, 12);          mz.addView(ramVal);
        FrameLayout.LayoutParams mzLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.END);
        mzLp.setMargins(0, 0, 58, 62 + 132 + 12 + 58);
        stack.addView(mz, mzLp);

        // Every value that changes gets pinned to the width of its widest
        // possible reading. Otherwise "2.3 KB/s" -> "12 KB/s" shoved the
        // readout beside it sideways on every sample.
        fixWidth(powerVal,   "100%");
        fixWidth(powerState, "CHARGING");
        fixWidth(linkVal,    "DISCONNECTED");
        fixWidth(speedVal,   "1023 KB/s");
        fixWidth(tempVal,    "88.8°C");

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(48, 32, 48, 90);
        final FrameLayout defHost = stack;
        TextView brand = text("CYBERDECK", FG, 46, corpta, 0, 0.18f);
        brand.setOnClickListener(new View.OnClickListener() {   // tap the title -> defuser
            @Override public void onClick(View v) { showDefuser(defHost); }
        });
        // WRAP_CONTENT + centred so only the "CYBERDECK" text is tappable, not the whole row -
        // a MATCH_PARENT row ran its hit box under the top-right ROTATE button (same fix as settings).
        brand.setPadding(12, 0, 12, 0);
        LinearLayout.LayoutParams brandLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        brandLp.gravity = Gravity.CENTER_HORIZONTAL;
        root.addView(brand, brandLp);
        root.addView(text("DISPLAY TERMINAL", DIM, 16, corpta, 14, 0.30f));

        StateListDrawable sl = new StateListDrawable();
        sl.addState(new int[]{ android.R.attr.state_pressed }, new Chip(KALI_HI, 26));
        sl.addState(new int[]{}, new Chip(KALI, 26));
        Button start = new Button(this);
        start.setText("START CYBERDECK");
        start.setTextSize(24);
        start.setTypeface(corpta);
        start.setLetterSpacing(0.10f);
        start.setTextColor(Color.WHITE);
        start.setAllCaps(false);
        start.setBackground(sl);
        start.setPadding(80, 30, 80, 30);
        start.setStateListAnimator(null);
        startBtn = start;
        start.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                // Cold start has to open the socket, do the RFB handshake and
                // pull a full 1080p frame - several seconds on a Pi 3. Say so,
                // in case AVNC is slow to take the screen or bounces back here.
                if (ema < LIVE_KBPS) {
                    connectingSince = SystemClock.elapsedRealtime();
                    startBtn.setText("CONNECTING");
                    startBtn.setAlpha(0.55f);
                }
                // fired first, so the prompt is already drawn by the time the
                // RFB handshake finishes and the first frame arrives
                requestWake();
                launchViewer();
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = 46;
        root.addView(start, lp);

        status = text("", CYAN, 12, numeral, 34, 0f);
        root.addView(status);
        TextView settings = text("ANDROID SETTINGS", DIM, 12, corpta, 38, 0.22f);
        settings.setPadding(12, 0, 12, 0);   // hit box hugs the text (small H-pad so letterSpacing can't clip)
        settings.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try { startActivity(new Intent(Settings.ACTION_SETTINGS)); }
                catch (Exception e) { toast("settings unavailable"); }
            }
        });
        // WRAP_CONTENT + centred so only the text is tappable, not the whole row; the vertical
        // gap moves from padding (clickable) to a margin (not clickable).
        LinearLayout.LayoutParams setLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        setLp.gravity = Gravity.CENTER_HORIZONTAL;
        setLp.topMargin = 38;
        root.addView(settings, setLp);

        stack.addView(root, full);

        graph = new SpeedGraph();
        FrameLayout.LayoutParams glp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 132, Gravity.BOTTOM);
        glp.setMargins(58, 0, 58, 62);
        stack.addView(graph, glp);

        // Left column. Kept narrow enough to clear the centred CYBERDECK block,
        // which starts around x=410 on this panel.
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        TextView head = cell("PROCESS STREAM", DIM, 8, corpta, 0.22f, 8);
        left.addView(head);
        stream = new Stream();
        LinearLayout.LayoutParams stlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        stlp.topMargin = 16;
        left.addView(stream, stlp);
        FrameLayout.LayoutParams llp = new FrameLayout.LayoutParams(
                348, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.TOP | Gravity.START);
        llp.setMargins(58, 172, 0, 232);
        stack.addView(left, llp);

        // the band between ANDROID SETTINGS and the graph
        sshPanel = new SshPanel();
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(
                940, 84, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        slp.setMargins(0, 0, 0, 200);
        stack.addView(sshPanel, slp);

        setContentView(stack);
        immersive();
    }

    private Typeface load(String asset, Typeface fb) {
        try { return Typeface.createFromAsset(getAssets(), asset); }
        catch (Exception e) { return fb; }
    }

    /**
     * TOP gravity, not CENTER_VERTICAL - that is what puts a label and its
     * value on one baseline. LinearLayout only applies baseline alignment for
     * TOP and BOTTOM; under CENTER_VERTICAL each child is merely centred on its
     * own height, so a 13sp label and a 16sp value in different faces sat at
     * visibly different levels.
     */
    private LinearLayout row() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.TOP | Gravity.START);
        return l;
    }

    /**
     * A row's baseline is driven by its tallest glyph, so a zone holding only
     * Corpta sat higher than one holding a numeral - and the degree sign, which
     * Chakra Petch does not carry, drops to a fallback face taller than either.
     * Every zone gets the same invisible zero-width sample so all three measure
     * identically and land on one baseline.
     */
    private void metricAnchor(LinearLayout zone) {
        TextView t = cell("0°C", 0x00000000, 16, numeral, 0f, 0);
        t.setWidth(0);
        zone.addView(t);
    }

    /**
     * Fixed height, not wrap-content. Corpta and Chakra Petch have different
     * line heights, so a zone holding only Corpta wrapped shorter than one
     * holding a numeral and its text sat 25px higher than its neighbours.
     */
    private FrameLayout.LayoutParams topLp(int gravity, int leftM, int rightM) {
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, gravity);
        p.setMargins(leftM, 92, rightM, 0);
        return p;
    }

    private TextView cell(String s, int colour, int size, Typeface tf, float spacing, int leftPad) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextColor(colour); t.setTextSize(size); t.setTypeface(tf);
        if (spacing > 0f) t.setLetterSpacing(spacing);
        t.setSingleLine(true);
        t.setPadding(leftPad, 0, 0, 0);
        return t;
    }

    /**
     * Pin a readout to the width of its widest reading so the row never moves.
     * measureText already accounts for letter spacing here - adding it again
     * oversized every cell and pushed TEMP off the right edge of the panel.
     */
    private void fixWidth(TextView t, String widest) {
        float w = t.getPaint().measureText(widest);
        t.setWidth((int) Math.ceil(w) + t.getPaddingLeft() + 8);
    }

    private TextView text(String s, int colour, int size, Typeface tf, int topPad, float spacing) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextColor(colour); t.setTextSize(size); t.setTypeface(tf);
        if (spacing > 0f) t.setLetterSpacing(spacing);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, topPad, 0, 0);
        return t;
    }

    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(
                  View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    // -------------------------------------------------------------- sampling

    private long loopbackRx() {
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader("/proc/net/dev"));
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (!line.startsWith("lo:")) continue;
                String[] f = line.substring(line.indexOf(':') + 1).trim().split("\\s+");
                return Long.parseLong(f[0]);
            }
        } catch (Exception e) {
            return -1L;
        } finally {
            if (r != null) { try { r.close(); } catch (Exception ignored) { } }
        }
        return -1L;
    }

    /**
     * Two live metrics, never a static placeholder.
     *
     * Preferred: loopback throughput, which is the real VNC data rate through
     * the adb tunnel. If that counter is unreadable, fall back to round-trip
     * connect time to the tunnel - still a genuine, moving measurement of the
     * link rather than a decorative bar.
     */
    private void sampleGraph() {
        long rx = loopbackRx();
        if (rx >= 0) {
            long now = SystemClock.elapsedRealtime();
            if (lastRx < 0) { lastRx = rx; lastTs = now; return; }
            float dt = (now - lastTs) / 1000f;
            float kbps = dt > 0.02f ? (rx - lastRx) / 1024f / dt : 0f;
            lastRx = rx; lastTs = now;
            if (kbps < 0) kbps = 0;
            ema = ema < 0 ? kbps : ema + (kbps - ema) * 0.30f;   // smooth the jitter
            graph.push(ema, piTemp, battMa);
            speedVal.setText(fmtRate(ema));
            speedVal.setTextColor(ema > 1f ? CYAN : DIM);
            return;
        }

        // throughput unavailable -> graph latency instead, off the main thread
        new Thread(new Runnable() {
            @Override public void run() {
                long t0 = SystemClock.elapsedRealtime();
                boolean ok = linkState() != LINK_DOWN;
                final float ms = ok ? (SystemClock.elapsedRealtime() - t0) : 0f;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        ema = ema < 0 ? ms : ema + (ms - ema) * 0.30f;
                        graph.push(ema, piTemp, battMa);
                        speedVal.setText(String.format("%.1f ms", ema));
                        speedVal.setTextColor(ms > 0 ? CYAN : DIM);
                    }
                });
            }
        }).start();
    }

    /**
     * Pull the Pi's temperature and whatever processes have started since the
     * last poll, over the second reverse tunnel.
     *
     * Incremental by sequence number on purpose: a full buffer every two
     * seconds would be a couple of KB/s on loopback, which is the exact counter
     * the speed graph reads - the telemetry would have shown up as link traffic
     * and made the graph lie. A warm poll is ~34 bytes.
     */
    /**
     * Leaving the viewer for this screen locks the deck behind you.
     *
     * Fire and forget: if the Pi is unreachable there is nothing to lock, and
     * if it is already locked the request is a no-op, so there is no answer
     * worth waiting for and nothing to report on screen.
     */
    private void requestLock() { poke(LOCK_URL); }

    /**
     * Ask the Pi to draw its unlock prompt. The locked screen is blank until
     * something generates input, so without this START lands you on a black
     * rectangle that looks like the deck is asleep.
     */
    private void requestWake() { poke(WAKE_URL); }

    private void poke(final String url) {
        new Thread(new Runnable() {
            @Override public void run() {
                HttpURLConnection c = null;
                try {
                    c = (HttpURLConnection) new URL(url).openConnection();
                    c.setConnectTimeout(900);
                    c.setReadTimeout(2500);
                    c.getInputStream().close();
                } catch (Exception ignored) {
                } finally {
                    if (c != null) c.disconnect();
                }
            }
        }).start();
    }

    private void fetchTelemetry() {
        new Thread(new Runnable() {
            @Override public void run() {
                final ArrayList<String> fresh = new ArrayList<String>();
                final ArrayList<String> ssh = new ArrayList<String>();
                final ArrayList<String> cmds = new ArrayList<String>();
                HttpURLConnection c = null;
                BufferedReader r = null;
                float t = -1f;
                int seq = -1, sv = -1, sn = -1, sc = -1, cv = -1, ct = -1;
                String thr = null, tm = null, up = null, ram = null;
                try {
                    c = (HttpURLConnection) new URL(
                            TELEM_URL + telemSeq + "&sshv=" + sshVer + "&cmdv=" + cmdVer).openConnection();
                    c.setConnectTimeout(900);
                    c.setReadTimeout(900);
                    r = new BufferedReader(new InputStreamReader(c.getInputStream()));
                    String line;
                    while ((line = r.readLine()) != null) {
                        if (line.startsWith("L ")) fresh.add(line.substring(2));
                        else if (line.startsWith("S ")) ssh.add(line.substring(2));
                        else if (line.startsWith("TEMP ")) t = Float.parseFloat(line.substring(5));
                        else if (line.startsWith("SEQ ")) seq = Integer.parseInt(line.substring(4));
                        else if (line.startsWith("SSHV ")) sv = Integer.parseInt(line.substring(5));
                        else if (line.startsWith("SSHN ")) sn = Integer.parseInt(line.substring(5));
                        else if (line.startsWith("SSHC ")) sc = Integer.parseInt(line.substring(5));
                        else if (line.startsWith("THROTTLED ")) thr = line.substring(10).trim();
                        else if (line.startsWith("M ")) cmds.add(line.substring(2));
                        else if (line.startsWith("CMDV ")) cv = Integer.parseInt(line.substring(5));
                        else if (line.startsWith("CMDT ")) ct = Integer.parseInt(line.substring(5));
                        else if (line.startsWith("EPOCH ")) tm = line.substring(6).trim();
                        else if (line.startsWith("UP ")) up = line.substring(3).trim();
                        else if (line.startsWith("RAM ")) ram = line.substring(4).trim();
                    }
                } catch (Exception ignored) {
                } finally {
                    if (r != null) { try { r.close(); } catch (Exception ignored) { } }
                    if (c != null) c.disconnect();
                }

                final float temp = t;
                final int newSeq = seq, newSv = sv, newSn = sn, newSc = sc;
                final String newThr = thr, newTime = tm, newUp = up, newRam = ram;
                final int newCv = cv, newCt = ct;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (newTime != null) {
                            try {
                                clockOffset = Long.parseLong(newTime) * 1000L - System.currentTimeMillis();
                                clockSynced = true;
                            } catch (Exception ignored) { }
                        }
                        if (newUp != null) {
                            try {
                                uptimeBaseSec = Long.parseLong(newUp);
                                uptimeBaseElapsed = SystemClock.elapsedRealtime();
                            } catch (Exception ignored) { }
                        }
                        if (newRam != null) {
                            int sp = newRam.indexOf(' ');
                            if (sp > 0) ramVal.setText(newRam.substring(0, sp) + " / "
                                    + newRam.substring(sp + 1) + " MB");
                        }
                        applyTelemetry(temp, newSeq, fresh, newSv, newSn, newSc, ssh, newThr,
                                       newCv, newCt, cmds);
                    }
                });
            }
        }).start();
    }

    /**
     * The Pi's own undervoltage detector, straight from vcgencmd get_throttled.
     * There is no rail-voltage number to show instead - the 3B+ has no ADC on
     * its 5V input - so this flag IS the power-health signal.
     */
    private void applyThrottle(String hex) {
        if (hex == null) return;
        long v;
        try {
            String h = hex.trim();
            if (h.startsWith("0x") || h.startsWith("0X")) h = h.substring(2);
            v = Long.parseLong(h, 16);
        } catch (Exception e) {
            return;                       // "unknown" when vcgencmd is unhappy
        }
        // ONLY bit 0 - under-voltage happening right now.
        //
        // This used to light up for bit 16 as well, the sticky "it happened at
        // some point since boot" flag. That was wrong in practice: a cold start
        // pulls an inrush spike that dips the rail for milliseconds and latches
        // bit 16 on almost every boot, even on a supply that is completely
        // healthy afterwards. The badge was therefore on permanently and meant
        // nothing. A red warning has to mean "this is happening now" or it
        // trains you to ignore it.
        //
        // The sticky history is still in the payload, and in get_throttled.
        boolean now = (v & 0x1L) != 0;
        piUnderVolt = now;
        uvBadge.setVisibility(now ? View.VISIBLE : View.GONE);
        uvLabel.setText("UNDERVOLTAGE");
        uvBadge.setAlpha(1f);
    }

    /** Phone battery, in millivolts. Not the Pi's rail - that is unreadable. */
    private void refreshBatteryDetail(Intent bat) {
        try {
            int mv = bat.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1);
            if (mv > 0) {
                battVolts.setText(String.format("%.2f V", mv / 1000f));
                battVolts.setTextColor(DIM);
            } else {
                battVolts.setText("");
            }
        } catch (Exception e) {
            battVolts.setText("");
        }

        // BatteryManager.BATTERY_PROPERTY_CURRENT_NOW is not usable on this
        // phone: measured, it returned 6109 while the cell sat at 100% Full.
        // That is 6A into a 2300mAh battery, i.e. meaningless. The kernel's
        // sysfs value agrees with dumpsys and with physics, so trust that only.
        int ma = sysfsCurrentMa();
        battMa = (ma == 0 && !sysfsCurrentReadable()) ? Float.NaN : (float) ma;

        if (ma != 0) {
            battAmps.setText(String.format("%+d mA", ma));
            battAmps.setTextColor(ma > 0 ? CYAN : DIM);
        } else {
            battAmps.setText("");
        }
    }

    /**
     * Battery current in mA, or 0 if it cannot be trusted.
     *
     * hammerhead's qpnp driver reports MICROamps: -4299 with the cell Full,
     * which is a 4.3mA trickle and entirely plausible. Guessing the unit from
     * magnitude - which is what I did first - turns that into "-4299 mA".
     */
    /** Distinguishes "the file says 0" from "there is no file". */
    private boolean sysfsCurrentReadable() {
        return new java.io.File("/sys/class/power_supply/battery/current_now").canRead();
    }

    private int sysfsCurrentMa() {
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader(
                    "/sys/class/power_supply/battery/current_now"));
            int ma = Integer.parseInt(r.readLine().trim()) / 1000;
            // This cell cannot do 3A in either direction; anything past that is
            // a driver quirk, and a wrong number is worse than no number.
            return Math.abs(ma) > 3000 ? 0 : ma;
        } catch (Exception e) {
            return 0;
        } finally {
            if (r != null) { try { r.close(); } catch (Exception ignored) { } }
        }
    }

    private void applyTelemetry(float temp, int seq, ArrayList<String> fresh,
                                int sshv, int sshn, int sshc, ArrayList<String> ssh,
                                String throttled, int cmdv, int cmdTotal,
                                ArrayList<String> cmds) {
        long now = SystemClock.elapsedRealtime();

        if (seq < 0) {                                  // unreachable
            boolean drop = telemUp && now - telemAt > TELEM_STALE;
            if (drop || (!telemUp && !telemNotedDown)) {
                telemUp = false;
                telemNotedDown = true;
                piTemp = -1f;                     // trace breaks rather than flatlines
                stream.push(drop ? "-- telemetry link down --" : "-- no telemetry link --");
                tempVal.setText(NO_TEMP);
                tempVal.setTextColor(DIM);
                sshVer = -1; cmdVer = -1;         // resync the panel on reconnect
                sshPanel.set(-1, new ArrayList<String>(), 0);
            }
            return;
        }
        telemNotedDown = false;
        applyThrottle(throttled);

        // The panel is only sent when it changed, so a matching version means
        // "still exactly as you have it" rather than "nothing running".
        // The panel now shows what was actually TYPED, from the shell hook -
        // not sshd's descendants. A builtin like `pwd` forks nothing, so the
        // old process-sampling view could never have shown it.
        if (cmdv >= 0 && cmdv != cmdVer) {
            cmdVer = cmdv;
            sshPanel.set(Math.max(cmdTotal, 0), cmds, cmds.size());
        }

        if (!telemUp) {
            telemUp = true;
            stream.push("-- telemetry link up --");
        }
        telemAt = now;
        // the daemon restarting rewinds its counter; resync instead of going deaf
        telemSeq = seq < telemSeq ? 0 : seq;

        for (int i = 0; i < fresh.size(); i++) stream.push(fresh.get(i));

        piTemp = temp;
        if (temp >= 0f) {
            tempVal.setText(String.format("%.1f°C", temp));
            tempVal.setTextColor(temp >= TEMP_HOT ? RED : temp >= TEMP_WARM ? AMBER : CYAN);
        } else {
            tempVal.setText(NO_TEMP);
            tempVal.setTextColor(DIM);
        }
    }

    private String fmtRate(float kbps) {
        if (kbps >= 1024f) return String.format("%.1f MB/s", kbps / 1024f);
        if (kbps >= 10f)   return String.format("%d KB/s", (int) kbps);
        return String.format("%.1f KB/s", kbps);
    }

    private void refreshStats() {
        try {
            Intent bat = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (bat != null) {
                int level = bat.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = bat.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                int st    = bat.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                if (level >= 0 && scale > 0) powerVal.setText((level * 100 / scale) + "%");
                boolean charging = st == BatteryManager.BATTERY_STATUS_CHARGING
                                || st == BatteryManager.BATTERY_STATUS_FULL;
                powerState.setText(charging ? "CHARGING" : "BATTERY");
                powerState.setTextColor(charging ? CYAN : DIM);
                refreshBatteryDetail(bat);
            }
        } catch (Exception ignored) { }

        new Thread(new Runnable() {
            @Override public void run() {
                final int st = linkState();
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        // Two states only. READY is an internal distinction: the
                        // tunnel is up but nothing is streaming, which is not
                        // "connected" from where you are sitting.
                        boolean live = st == LINK_LIVE;
                        linkVal.setText(live ? "CONNECTED" : "DISCONNECTED");
                        linkVal.setTextColor(live ? GREEN : RED);
                        if (connectingSince > 0 && (live
                                || SystemClock.elapsedRealtime() - connectingSince > CONNECT_MAX_MS)) {
                            connectingSince = 0;
                            startBtn.setText("START CYBERDECK");
                            startBtn.setAlpha(1f);
                        }
                        // No auto-connect: START is the only thing that opens
                        // the viewer. LINK_READY is still tracked internally,
                        // it just does not act on its own.
                    }
                });
            }
        }).start();
    }


    private static final int LINK_DOWN  = 0;
    private static final int LINK_READY = 1;   // tunnel exists, viewer not attached
    private static final int LINK_LIVE  = 2;   // viewer actually streaming the deck

    /** Only used to colour the RATE readout, never to decide connectedness. */
    private static final float LIVE_KBPS = 1.0f;

    /**
     * Is there a real VNC session on port 5900? Reads the kernel's socket table
     * directly and disturbs nothing - no probe socket, so this cannot pollute
     * x11vnc's accept queue the way the old connect-based check did.
     */
    private boolean establishedOn5900() {
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader("/proc/net/tcp"));
            r.readLine();                                   // header
            String line;
            while ((line = r.readLine()) != null) {
                String[] f = line.trim().split("\\s+");
                if (f.length < 4 || !"01".equals(f[3])) continue;   // 01 = ESTABLISHED
                if (port(f[1]) == VNC_PORT || port(f[2]) == VNC_PORT) return true;
            }
        } catch (Exception ignored) {
        } finally {
            if (r != null) { try { r.close(); } catch (Exception ignored) { } }
        }
        return false;
    }

    private int port(String hexAddr) {
        int i = hexAddr.indexOf(':');
        if (i < 0) return -1;
        try { return Integer.parseInt(hexAddr.substring(i + 1), 16); }
        catch (Exception e) { return -1; }
    }

    /**
     * Three honest states, judged on DATA FLOW rather than socket state.
     *
     * Two earlier versions of this lied. A plain connect to 5900 only proves
     * adbd is listening - that port answers even when the Pi end is dead.
     * Checking /proc/net/tcp for an established socket was no better, because
     * this app's own probe shows up there, and a stalled viewer leaves a socket
     * that is established but carrying nothing (measured: 12 bytes, then idle
     * for four minutes).
     *
     * Actual throughput cannot be faked by either. If bytes are moving, a screen
     * is on the wire.
     */
    private int linkState() {
        long now = SystemClock.elapsedRealtime();
        // An ESTABLISHED socket on 5900 is the honest signal. Measured: pressing
        // home keeps all four sockets up and data still flowing at ~1.9 KB/s, so
        // the session plainly survives backgrounding.
        //
        // I previously gated this on throughput >= 3 KB/s, which was simply a
        // bad number: a live session on a static desktop sits at 1.6-2.4 KB/s,
        // so the launcher called a perfectly healthy connection DISCONNECTED.
        // No single rate threshold can separate "connected" from "not" - socket
        // state can. Throughput is now only used for the graph and RATE.
        if (establishedOn5900()) { lastLiveMs = now; return LINK_LIVE; }
        // brief grace so a momentary socket churn does not flicker the label
        if (lastLiveMs > 0 && now - lastLiveMs < LIVE_GRACE_MS) return LINK_LIVE;
        Socket s = null;
        try {
            s = new Socket();
            s.connect(new InetSocketAddress(VNC_HOST, VNC_PORT), 500);
            return LINK_READY;
        } catch (Exception e) {
            return LINK_DOWN;
        } finally {
            if (s != null) { try { s.close(); } catch (Exception ignored) { } }
        }
    }

    @Override protected void onResume() {
        super.onResume();
        immersive();
        boolean have = true;
        try { getPackageManager().getPackageInfo(VNC_PKG, 0); }
        catch (PackageManager.NameNotFoundException e) { have = false; }
        status.setText(have ? "VIEWER READY  //  127.0.0.1:5900" : "VIEWER MISSING: " + VNC_PKG);
        status.setTextColor(have ? CYAN : MAGENTA);
        // Deliberately does NOT lock the deck. Leaving the viewer used to lock
        // Kali behind you; going to the launcher to glance at the stats is a
        // normal thing to do here and having to card back in every time is not
        // worth it. The lock endpoint is still there if it is ever wanted.
        lastRx = -1L; ema = -1f;
        poll.removeCallbacks(tick); poll.post(tick);
        anim.removeCallbacks(frame); anim.post(frame);
        telem.removeCallbacks(telemTick); telem.post(telemTick);
    }

    @Override protected void onPause() {
        super.onPause();
        poll.removeCallbacks(tick);
        anim.removeCallbacks(frame);
        telem.removeCallbacks(telemTick);
    }

    private void launchViewer() {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(VNC_URI));
            i.setPackage(VNC_PKG);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) { toast("could not start viewer: " + e); }
    }

    /** Screen-rotation glyph: a tilted rounded-rect screen with a curved arrow sweeping over it.
        Deliberately a different shape from the reboot/power glyphs so the rotate button reads at
        a glance (a tilted arrow just looked like the others). */
    private static class ScreenRotateIcon extends Drawable {
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path head = new Path();
        ScreenRotateIcon() {
            stroke.setColor(Color.WHITE);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeCap(Paint.Cap.ROUND);
            stroke.setStrokeJoin(Paint.Join.ROUND);
            fill.setColor(Color.WHITE);
            fill.setStyle(Paint.Style.FILL);
        }
        @Override public void draw(Canvas c) {
            Rect b = getBounds();
            float cx = b.exactCenterX(), cy = b.exactCenterY();
            float s = Math.min(b.width(), b.height());
            float sw = s * 0.072f;
            stroke.setStrokeWidth(sw);
            // the screen: a rounded rectangle tilted ~30 deg, sitting low so the arrow clears the top
            c.save();
            c.rotate(-30f, cx, cy + s * 0.08f);
            float pw = s * 0.34f, ph = s * 0.50f, rad = s * 0.07f;
            c.drawRoundRect(cx - pw / 2f, cy + s * 0.08f - ph / 2f,
                            cx + pw / 2f, cy + s * 0.08f + ph / 2f, rad, rad, stroke);
            c.restore();
            // the rotation arrow: an arc sweeping over the top, arrowhead on the right
            float rr = s * 0.40f;
            float ay = cy - s * 0.04f;                  // arc centre lifted above the screen
            float start = 202f, sweep = 132f;           // upper-left, over the top, to the right
            c.drawArc(cx - rr, ay - rr, cx + rr, ay + rr, start, sweep, false, stroke);
            double a = Math.toRadians(start + sweep);
            float px = cx + (float)(rr * Math.cos(a)), py = ay + (float)(rr * Math.sin(a));
            float tx = -(float)Math.sin(a), ty = (float)Math.cos(a);   // clockwise tangent
            float nx =  (float)Math.cos(a), ny = (float)Math.sin(a);
            float al = sw * 2.3f, aw = sw * 1.7f;
            head.reset();
            head.moveTo(px + tx * al, py + ty * al);
            head.lineTo(px + nx * aw, py + ny * aw);
            head.lineTo(px - nx * aw, py - ny * aw);
            head.close();
            c.drawPath(head, fill);
        }
        @Override public void setAlpha(int a) { stroke.setAlpha(a); fill.setAlpha(a); }
        @Override public void setColorFilter(ColorFilter f) { stroke.setColorFilter(f); fill.setColorFilter(f); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    // ---------------------------------------------------- top-right HUD buttons

    /** Square chipped HUD button, same recipe as START CYBERDECK. corner = Chip.BR / Chip.BL. */
    private Button squareBtn(String glyph, int base, int hi, int corner, int padBottom,
                             View.OnClickListener click) {
        StateListDrawable sl = new StateListDrawable();
        sl.addState(new int[]{ android.R.attr.state_pressed }, new Chip(hi, 22, corner));
        sl.addState(new int[]{}, new Chip(base, 22, corner));
        Button b = new Button(this);
        b.setText(glyph);
        b.setTextSize(30);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextColor(Color.WHITE);
        b.setAllCaps(false);
        b.setGravity(Gravity.CENTER);
        b.setBackground(sl);
        b.setIncludeFontPadding(false);
        b.setPadding(0, 0, 0, padBottom);
        b.setStateListAnimator(null);
        b.setOnClickListener(click);
        return b;
    }

    /** Drop a 120x120 square button into the right-edge column at the given top margin. */
    private void addSquare(FrameLayout stack, Button b, int topMargin) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(120, 120, Gravity.TOP | Gravity.END);
        lp.setMargins(0, topMargin, 48, 0);          // right-edge column, stacked vertically
        stack.addView(b, lp);
    }

    /** On-theme modal confirm overlay laid over the whole HUD. accent tints CONFIRM. */
    private void confirm(final FrameLayout host, String msg, int accent, final Runnable onYes) {
        final FrameLayout scrim = new FrameLayout(this);
        scrim.setBackgroundColor(0xCC05070D);
        scrim.setClickable(true);                       // swallow taps to the HUD behind

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(new Chip(0xFF0C1830, 26));
        panel.setPadding(72, 56, 72, 56);
        panel.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView t = new TextView(this);
        t.setText(msg);
        t.setTextColor(Color.WHITE);
        t.setTextSize(24);
        t.setTypeface(corpta);
        t.setLetterSpacing(0.08f);
        t.setAllCaps(false);
        t.setGravity(Gravity.CENTER);
        t.setPadding(44, 0, 44, 0);        // letterSpacing under-measures width on API 23 -> pad so it can't clip
        panel.addView(t);

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        Button cancel = confirmBtn("CANCEL", 0xFF33415A, 0xFF48597A);
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { host.removeView(scrim); }
        });
        Button ok = confirmBtn("CONFIRM", accent, lighten(accent));
        ok.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { host.removeView(scrim); onYes.run(); }
        });
        LinearLayout.LayoutParams okLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        okLp.leftMargin = 24;
        btns.addView(cancel);
        btns.addView(ok, okLp);
        LinearLayout.LayoutParams btnsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        btnsLp.topMargin = 42;
        panel.addView(btns, btnsLp);

        scrim.addView(panel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        host.addView(scrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private Button confirmBtn(String label, int base, int hi) {
        StateListDrawable sl = new StateListDrawable();
        sl.addState(new int[]{ android.R.attr.state_pressed }, new Chip(hi, 20));
        sl.addState(new int[]{}, new Chip(base, 20));
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(17);
        b.setTypeface(corpta);
        b.setLetterSpacing(0.12f);
        b.setTextColor(Color.WHITE);
        b.setAllCaps(false);
        b.setBackground(sl);
        b.setPadding(58, 22, 58, 22);
        b.setStateListAnimator(null);
        return b;
    }

    /** Lighten a colour ~40/255 per channel for the pressed state. */
    private static int lighten(int c) {
        int a = (c >>> 24) & 0xFF;
        int r = Math.min(255, ((c >> 16) & 0xFF) + 40);
        int g = Math.min(255, ((c >> 8) & 0xFF) + 40);
        int b = Math.min(255, (c & 0xFF) + 40);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    // ---------------------------------------------------------------- defuser

    /** Full-screen overlay with a recreated R6-style defuser. NOT the game asset (those are sealed
        in Ubisoft .forge archives) - a drawn homage in the deck's HUD style. Hold anywhere on the
        device to run the ~7s defuse; release pauses; on completion it reads DEFUSED. */
    /** Draws a time string with every digit in a fixed-width cell (colons in a narrower cell), so
        nothing shifts as it ticks - real monospace layout in the numeral font, which has no working
        tabular-figure table so tnum alone doesn't hold the columns. */
    private class ClockView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private String text = "--:--:--";
        private final float digitW, colonW, blockW;
        ClockView(float textSizePx, int colour) {
            super(MainActivity.this);
            p.setTypeface(numeral); p.setColor(colour); p.setTextSize(textSizePx);
            p.setTextAlign(Paint.Align.CENTER);
            digitW = p.measureText("8") * 1.06f;
            colonW = p.measureText(":") * 1.0f;
            blockW = 6 * digitW + 2 * colonW;      // HH:MM:SS = 6 digits + 2 colons
        }
        void setTime(String s) { text = s; invalidate(); }
        @Override protected void onMeasure(int ws, int hs) {
            Paint.FontMetrics fm = p.getFontMetrics();
            setMeasuredDimension((int) Math.ceil(blockW), (int) Math.ceil(fm.descent - fm.ascent));
        }
        @Override protected void onDraw(Canvas c) {
            Paint.FontMetrics fm = p.getFontMetrics();
            float baseline = -fm.ascent, x = 0;
            for (int i = 0; i < text.length(); i++) {
                char ch = text.charAt(i);
                float cw = (ch == ':' ? colonW : digitW);
                c.drawText(String.valueOf(ch), x + cw / 2f, baseline, p);   // centred in its fixed cell
                x += cw;
            }
        }
    }

    /** Tap the corner clock -> show the time large and centred; tap anywhere to dismiss. */
    private void showBigClock(final FrameLayout host) {
        final FrameLayout overlay = new FrameLayout(this);
        overlay.setBackgroundColor(0xEE05070D);
        final ClockView big = new ClockView(
                getResources().getDisplayMetrics().widthPixels * 0.135f, Color.WHITE);
        overlay.addView(big, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        final Handler bh = new Handler();
        final Runnable br = new Runnable() {
            @Override public void run() {
                big.setTime(clockFmt.format(new java.util.Date(
                        System.currentTimeMillis() + (clockSynced ? clockOffset : 0))));
                bh.postDelayed(this, 1000);
            }
        };
        bh.post(br);
        overlay.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { bh.removeCallbacks(br); host.removeView(overlay); }
        });
        host.addView(overlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void showDefuser(final FrameLayout host) {
        final FrameLayout overlay = new FrameLayout(this);
        overlay.setBackgroundColor(0xEE05070D);
        overlay.addView(new DefuserView(), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        // tap anywhere OUTSIDE the defuser panel to close; the DefuserView consumes its own touches
        // (hold-to-defuse), so holding it never dismisses it.
        overlay.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { host.removeView(overlay); }
        });
        host.addView(overlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private class DefuserView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private static final float SECS = 7f;
        private final int GREEN = 0xFF35E06B, TRACK = 0xFF1E2A38, BODY = 0xFF0F141D,
                          EDGE = 0xFF2A3648, RED = 0xFFFF3B54, AMBER = 0xFFFFB020;
        private float progress = 0f;
        private boolean holding = false, defused = false;
        private long lastT = 0;
        private final Handler h = new Handler();
        private final Runnable tick = new Runnable() {
            @Override public void run() {
                long now = SystemClock.uptimeMillis();
                float dt = (lastT == 0) ? 0f : (now - lastT) / 1000f;
                lastT = now;
                if (holding && !defused) {
                    progress += dt / SECS;
                    if (progress >= 1f) { progress = 1f; defused = true; holding = false; }
                }
                invalidate();
                if (holding && !defused) h.postDelayed(this, 16);
            }
        };
        DefuserView() { super(MainActivity.this); }

        @Override public boolean onTouchEvent(android.view.MotionEvent e) {
            if (defused) return true;
            int a = e.getActionMasked();
            if (a == android.view.MotionEvent.ACTION_DOWN) {
                holding = true; lastT = SystemClock.uptimeMillis();
                h.removeCallbacks(tick); h.post(tick);
            } else if (a == android.view.MotionEvent.ACTION_UP || a == android.view.MotionEvent.ACTION_CANCEL) {
                holding = false;
            }
            return true;
        }

        @Override protected void onMeasure(int wSpec, int hSpec) {
            int base = Math.min(getResources().getDisplayMetrics().widthPixels,
                                getResources().getDisplayMetrics().heightPixels);
            int w = Math.round(base * 0.72f);
            setMeasuredDimension(w, Math.round(w * 1.08f));
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), hg = getHeight();
            float cx = w / 2f;
            // device body + edge
            p.setStyle(Paint.Style.FILL); p.setColor(BODY);
            r.set(0, 0, w, hg); c.drawRoundRect(r, 26, 26, p);
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(4f); p.setColor(defused ? GREEN : EDGE);
            r.set(7, 7, w - 7, hg - 7); c.drawRoundRect(r, 20, 20, p);
            // corner bolts
            p.setStyle(Paint.Style.FILL); p.setColor(EDGE);
            float bo = 28;
            c.drawCircle(bo, bo, 7, p); c.drawCircle(w - bo, bo, 7, p);
            c.drawCircle(bo, hg - bo, 7, p); c.drawCircle(w - bo, hg - bo, 7, p);
            // segmented dial - ticks that light green as it fills (kept from the reworked version)
            float cyR = hg * 0.5f, rad = w * 0.30f, innerR = rad * 0.80f;
            int SEG = 44; float a0 = 130f, sweep = 280f;
            p.setStyle(Paint.Style.STROKE); p.setStrokeCap(Paint.Cap.BUTT); p.setStrokeWidth(w * 0.022f);
            for (int i = 0; i < SEG; i++) {
                float frac = i / (float) (SEG - 1);
                double ang = Math.toRadians(a0 + sweep * frac);
                float ca = (float) Math.cos(ang), sa = (float) Math.sin(ang);
                p.setColor(frac <= progress ? GREEN : TRACK);
                c.drawLine(cx + ca * innerR, cyR + sa * innerR, cx + ca * rad, cyR + sa * rad, p);
            }
            // countdown in centre
            float rem = SECS * (1f - progress);
            p.setStyle(Paint.Style.FILL); p.setTextAlign(Paint.Align.CENTER); p.setLetterSpacing(0f);
            p.setTypeface(numeral); p.setColor(defused ? GREEN : 0xFFDDE6F0); p.setTextSize(w * 0.20f);
            c.drawText(defused ? "0.0" : String.format(java.util.Locale.US, "%.1f", rem), cx, cyR + w * 0.07f, p);
            p.setTypeface(corpta); p.setTextSize(w * 0.04f); p.setColor(0xFF6B7A8C); p.setLetterSpacing(0.2f);
            c.drawText("SEC", cx, cyR - rad * 0.44f, p);
            // status line
            String st = defused ? "DEFUSED" : (holding ? "DEFUSING..." : "HOLD TO DEFUSE");
            p.setColor(defused ? GREEN : (holding ? AMBER : 0xFF8593A5));
            p.setTypeface(corpta); p.setTextSize(w * 0.06f); p.setLetterSpacing(0.18f);
            c.drawText(st, cx, hg * 0.9f, p);
        }
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    @Override public void onBackPressed() { }
}
