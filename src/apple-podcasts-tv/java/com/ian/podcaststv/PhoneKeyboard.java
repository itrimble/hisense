package com.ian.podcaststv;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * "Type with your phone": shows a QR code that opens a one-time page on the
 * phone; text sent from that page is typed into whatever field has focus on
 * the TV (including Apple's sign-in form). The server only runs while the
 * panel is visible, answers only on a random token path, and closes itself
 * after ten idle minutes.
 */
class PhoneKeyboard {
    interface Sink {
        void type(String text);
        void key(String name); // "enter", "backspace", "tab"
    }

    private static final String TAG = "PodcastsTV";
    private static final int PORT = 8765;
    private static final int MAX_BODY = 4096;
    private static final long IDLE_MS = 10 * 60 * 1000;

    private final Activity activity;
    private final FrameLayout root;
    private final Sink sink;
    private final String page;
    private View panel;
    private ServerSocket server;
    private String token;
    private boolean auto;
    private volatile long lastUse;

    private final Runnable idleCheck = new Runnable() {
        @Override public void run() {
            if (panel == null) return;
            if (System.currentTimeMillis() - lastUse > IDLE_MS) hide();
            else root.postDelayed(this, 30_000);
        }
    };

    PhoneKeyboard(Activity activity, FrameLayout root, String page, Sink sink) {
        this.activity = activity;
        this.root = root;
        this.page = page;
        this.sink = sink;
    }

    boolean isShowing() { return panel != null; }

    /** True when the panel was opened by the sign-in sheet rather than by the viewer. */
    boolean isAuto() { return auto; }

    void show(boolean automatic) {
        if (panel != null) return;
        String ip = lanAddress();
        if (ip == null || page == null) return;
        token = randomToken();
        try {
            server = new ServerSocket(PORT, 8);
            server.setReuseAddress(true);
        } catch (IOException e) {
            Log.w(TAG, "phone keyboard: cannot listen on " + PORT, e);
            return;
        }
        auto = automatic;
        lastUse = System.currentTimeMillis();
        final ServerSocket s = server;
        final String t = token;
        Thread th = new Thread(() -> serve(s, t), "phone-keyboard");
        th.setDaemon(true);
        th.start();
        String url = "http://" + ip + ":" + PORT + "/k/" + token;
        panel = buildPanel(url);
        root.addView(panel, root.getChildCount() - 1); // below the pointer
        root.postDelayed(idleCheck, 30_000);
    }

    void hide() {
        root.removeCallbacks(idleCheck);
        if (panel != null) root.removeView(panel);
        panel = null;
        auto = false;
        token = null;
        if (server != null) {
            try { server.close(); } catch (IOException ignored) { }
            server = null;
        }
    }

    // ---- UI ---------------------------------------------------------------

    private View buildPanel(String url) {
        float d = activity.getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = (int) (14 * d);
        box.setPadding(pad, pad, pad, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(235, 28, 28, 30));
        bg.setCornerRadius(16 * d);
        box.setBackground(bg);

        box.addView(text("Type with your phone", 15, Color.WHITE, true));
        ImageView qr = new ImageView(activity);
        int size = (int) (120 * d);
        qr.setImageBitmap(qrBitmap(url, size));
        LinearLayout.LayoutParams qlp = new LinearLayout.LayoutParams(size, size);
        qlp.topMargin = qlp.bottomMargin = (int) (10 * d);
        box.addView(qr, qlp);
        box.addView(text("Scan with your phone's camera,\nthen type on your phone.", 11, Color.argb(220, 235, 235, 245), false));
        box.addView(text(url, 9, Color.argb(160, 200, 200, 210), false));

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                (int) (172 * d), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.START | Gravity.CENTER_VERTICAL);
        lp.leftMargin = (int) (6 * d);
        box.setLayoutParams(lp);
        return box;
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView tv = new TextView(activity);
        tv.setText(s);
        tv.setTextColor(color);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        tv.setGravity(Gravity.CENTER);
        if (bold) tv.setTypeface(tv.getTypeface(), android.graphics.Typeface.BOLD);
        return tv;
    }

    private static Bitmap qrBitmap(String url, int size) {
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.MARGIN, 1);
        try {
            BitMatrix m = new QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, size, size, hints);
            int[] px = new int[size * size];
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) px[y * size + x] = m.get(x, y) ? Color.BLACK : Color.WHITE;
            }
            return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888);
        } catch (WriterException e) {
            return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
        }
    }

    // ---- Server -------------------------------------------------------------

    private void serve(ServerSocket s, String t) {
        while (!s.isClosed()) {
            try (Socket c = s.accept()) {
                c.setSoTimeout(5000);
                handle(c, t);
            } catch (IOException e) {
                if (!s.isClosed()) Log.w(TAG, "phone keyboard: request failed", e);
            }
        }
    }

    private void handle(Socket c, String t) throws IOException {
        InputStream in = c.getInputStream();
        String requestLine = readLine(in);
        int length = 0;
        for (String h; !(h = readLine(in)).isEmpty(); ) {
            if (h.toLowerCase().startsWith("content-length:")) {
                try { length = Integer.parseInt(h.substring(15).trim()); } catch (NumberFormatException ignored) { }
            }
        }
        String[] parts = requestLine.split(" ");
        OutputStream out = c.getOutputStream();
        if (parts.length < 2 || length < 0 || length > MAX_BODY) { respond(out, 400, "text/plain", "bad request"); return; }
        String method = parts[0], path = parts[1], base = "/k/" + t;

        if (method.equals("GET") && path.equals(base)) {
            lastUse = System.currentTimeMillis();
            respond(out, 200, "text/html; charset=utf-8", page);
            return;
        }
        if (method.equals("POST") && path.startsWith(base + "/")) {
            String action = path.substring(base.length() + 1);
            String body = new String(readBody(in, length), StandardCharsets.UTF_8);
            lastUse = System.currentTimeMillis();
            if (action.equals("type")) {
                activity.runOnUiThread(() -> sink.type(body));
            } else if (action.equals("key/enter") || action.equals("key/backspace") || action.equals("key/tab")) {
                String name = action.substring(4);
                activity.runOnUiThread(() -> sink.key(name));
            } else {
                respond(out, 404, "text/plain", "not found");
                return;
            }
            respond(out, 204, "text/plain", "");
            return;
        }
        respond(out, 404, "text/plain", "not found");
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        for (int ch; (ch = in.read()) != -1 && ch != '\n'; ) {
            if (ch != '\r') b.write(ch);
            if (b.size() > 8192) throw new IOException("header too long");
        }
        return b.toString("UTF-8");
    }

    private static byte[] readBody(InputStream in, int length) throws IOException {
        byte[] buf = new byte[length];
        for (int off = 0; off < length; ) {
            int n = in.read(buf, off, length - off);
            if (n < 0) break;
            off += n;
        }
        return buf;
    }

    private static void respond(OutputStream out, int code, String type, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        String status = code == 200 ? "OK" : code == 204 ? "No Content" : code == 400 ? "Bad Request" : "Not Found";
        String head = "HTTP/1.1 " + code + " " + status + "\r\nContent-Type: " + type
                + "\r\nContent-Length: " + b.length + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.US_ASCII));
        out.write(b);
        out.flush();
    }

    private static String randomToken() {
        byte[] b = new byte[12];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private static String lanAddress() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) return a.getHostAddress();
                }
            }
        } catch (IOException ignored) { }
        return null;
    }
}
