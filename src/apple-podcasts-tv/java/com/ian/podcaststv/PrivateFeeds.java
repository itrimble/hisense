package com.ian.podcaststv;

import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.text.InputType;
import android.util.Xml;
import android.widget.EditText;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Private RSS never enters Apple's page or the launcher. Stored only in app-private preferences. */
final class PrivateFeeds {
    static final class Episode {
        String id = "", title = "", src = "", date = "", art = "", show = "";
        double duration;
        boolean video;
    }
    static final class Feed {
        String url = "", title = "", art = "";
        final List<Episode> episodes = new ArrayList<>();
    }
    private final MainActivity a;
    private final SharedPreferences prefs;
    private final ExecutorService worker = Executors.newFixedThreadPool(2);
    private final List<Feed> feeds = new ArrayList<>();
    private AlertDialog dialog, importer;
    private boolean closed;
    private final java.util.Map<String, String> artwork = new java.util.HashMap<>();

    PrivateFeeds(MainActivity a) {
        this.a = a;
        prefs = a.getSharedPreferences("private_podcasts", 0);
        try {
            JSONArray saved = new JSONArray(prefs.getString("feeds", "[]"));
            for (int i = 0; i < saved.length(); i++) {
                JSONObject o = saved.getJSONObject(i);
                Feed f = new Feed(); f.url = o.getString("url"); f.title = o.optString("title", "Club TWiT");
                f.art = o.optString("art"); feeds.add(f);
            }
        } catch (Exception ignored) { }
    }

    JSONArray nativeShows() {
        JSONArray out = new JSONArray();
        for (Feed f : feeds) try {
            out.put(new JSONObject().put("id", key(f)).put("private", true).put("title", f.title.replaceAll("\\s*\\((Audio|Video) - Club TWiT\\)", ""))
                    .put("art", f.art.split("\\?")[0]).put("format", f.title.contains("Video") ? "Video · Club TWiT" : "Audio · Club TWiT"));
        } catch (Exception ignored) { }
        return out;
    }

    void nativeEpisodes(String id, boolean refresh, java.util.function.Consumer<JSONArray> done) {
        for (Feed f : new ArrayList<>(feeds)) if (key(f).equals(id)) {
            worker.execute(() -> {
                Feed result = null; JSONObject cached = null;
                try {
                    cached = new JSONObject(a.libraryCache.read("private:" + id));
                    if (!refresh && System.currentTimeMillis() - cached.optLong("when") < 3600000)
                        result = unpack(f, cached);
                } catch (Exception ignored) { }
                if (result == null) try {
                    result = fetch(f.url);
                    a.libraryCache.write("private:" + id, pack(result).toString());
                } catch (Exception ignored) {
                    if (cached != null) try { result = unpack(f, cached); } catch (Exception ignoredAgain) { }
                }
                final Feed loaded = result;
                a.runOnUiThread(() -> {
                    if (closed || !feeds.contains(f)) return;
                    if (loaded == null) { done.accept(null); return; }
                    feeds.set(feeds.indexOf(f), loaded);
                    JSONArray rows = new JSONArray();
                    for (Episode e : loaded.episodes) try {
                        rows.put(new JSONObject().put("id",e.id).put("title",e.title).put("art",e.art.split("\\?")[0])
                                .put("format",(e.video ? "Video" : "Audio") + " · " + e.date).put("duration",e.duration));
                    } catch(Exception ignored) { }
                    done.accept(rows);
                });
            }); return;
        }
        done.accept(null);
    }
    private static JSONObject pack(Feed f) throws Exception {
        JSONArray rows = new JSONArray();
        for (Episode e : f.episodes) rows.put(new JSONObject().put("id",e.id).put("title",e.title).put("src",e.src)
                .put("date",e.date).put("art",e.art).put("show",e.show).put("duration",e.duration).put("video",e.video));
        return new JSONObject().put("when",System.currentTimeMillis()).put("episodes",rows);
    }
    private static Feed unpack(Feed source, JSONObject cache) throws Exception {
        Feed f=new Feed(); f.url=source.url; f.title=source.title; f.art=source.art;
        JSONArray rows=cache.getJSONArray("episodes");
        for(int i=0;i<rows.length();i++) { JSONObject o=rows.getJSONObject(i); Episode e=new Episode();
            e.id=o.getString("id"); e.title=o.getString("title"); e.src=o.getString("src"); e.date=o.optString("date");
            e.art=o.optString("art"); e.show=o.optString("show"); e.duration=o.optDouble("duration"); e.video=o.optBoolean("video"); f.episodes.add(e); }
        return f;
    }

    // Only presentation metadata and opaque IDs cross into the web library.
    private String key(Feed f) {
        try { return digest(f.url.split("\\?")[0]); }
        catch (Exception e) { throw new IllegalStateException("SHA-256 unavailable", e); }
    }
    private JSONObject metadata(Feed f) throws Exception {
        String art = f.art;
        // TWiT cover art is public; discard feed-added query strings. Do not
        // expose authenticated/signed image links from any other host.
        try {
            URL u = new URL(art);
            if ("https".equals(u.getProtocol()) && "twit.cachefly.net".equals(u.getHost())
                    && u.getPath().matches("/coverart/[A-Za-z0-9/_-]+\\.(jpg|png)"))
                art = "https://twit.cachefly.net" + u.getPath();
            else art = "";
        } catch (Exception ignored) { art = ""; }
        return new JSONObject().put("id", key(f)).put("title", f.title).put("art", libraryArt(art))
                .put("format", f.title.contains("Video") ? "Video" : "Audio");
    }
    private String libraryArt(String url) {
        if (url.isEmpty()) return "";
        if (artwork.containsKey(url)) return artwork.get(url);
        artwork.put(url, "");
        worker.execute(() -> {
            String data = "";
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(10000); c.setReadTimeout(15000); c.setInstanceFollowRedirects(false);
                try {
                    if (c.getResponseCode() != 200) throw new Exception();
                    ByteArrayOutputStream raw = new ByteArrayOutputStream();
                    try (InputStream in = c.getInputStream()) {
                        byte[] buf = new byte[8192]; int n;
                        while ((n = in.read(buf)) != -1) {
                            if (raw.size() + n > 4 * 1024 * 1024) throw new Exception();
                            raw.write(buf, 0, n);
                        }
                    }
                    byte[] bytes = raw.toByteArray();
                    android.graphics.BitmapFactory.Options options = new android.graphics.BitmapFactory.Options();
                    options.inJustDecodeBounds = true;
                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
                    options.inSampleSize = Math.max(1, Math.max(options.outWidth, options.outHeight) / 320);
                    options.inJustDecodeBounds = false;
                    android.graphics.Bitmap bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
                    if (bitmap == null) throw new Exception();
                    ByteArrayOutputStream small = new ByteArrayOutputStream();
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, small); bitmap.recycle();
                    data = "data:image/jpeg;base64," + android.util.Base64.encodeToString(small.toByteArray(), android.util.Base64.NO_WRAP);
                } finally { c.disconnect(); }
            } catch (Exception ignored) { }
            final String result = data;
            a.runOnUiThread(() -> { if (!closed) { artwork.put(url, result); publish(); } });
        });
        return "";
    }

    private void send(String method, Object value) {
        if (closed || a.web == null || a.web.getUrl() == null || !a.web.getUrl().startsWith("https://podcasts.apple.com/")) return;
        a.web.evaluateJavascript("window.__ptv && __ptv.privateLibrary && __ptv.privateLibrary."
                + method + "(" + value + ")", null);
    }
    void publish() {
        JSONArray out = new JSONArray();
        try { for (Feed f : feeds) out.put(metadata(f)); } catch (Exception ignored) { }
        send("update", out);
    }
    void openInLibrary(String id) {
        for (Feed f : feeds) if (key(f).equals(id)) {
            worker.execute(() -> {
                Feed fresh = null;
                try { fresh = fetch(f.url); } catch (Exception ignored) { }
                final Feed result = fresh;
                a.runOnUiThread(() -> {
                    if (closed || !feeds.contains(f)) return;
                    if (result == null) {
                        send("failed", JSONObject.quote(id)); return;
                    }
                    feeds.set(feeds.indexOf(f), result); save();
                    try {
                        JSONObject out = metadata(result); JSONArray episodes = new JSONArray();
                        for (Episode e : result.episodes) episodes.put(new JSONObject()
                                .put("id", e.id).put("title", e.title).put("date", e.date)
                                .put("duration", e.duration).put("format", e.video ? "Video" : "Audio"));
                        out.put("episodes", episodes); send("loaded", out);
                    } catch (Exception ignored) { send("failed", JSONObject.quote(id)); }
                });
            });
            return;
        }
    }
    void playFromLibrary(String feedId, String episodeId) {
        for (Feed f : feeds) if (key(f).equals(feedId)) for (Episode e : f.episodes)
            if (e.id.equals(episodeId)) {
                double at = a.prefs.getFloat("private_position_" + e.id, 0);
                if (e.duration > 0 && at >= e.duration - 30) at = 0;
                play(e, at); return;
            }
    }

    void show() {
        if (closed) return;
        dismiss();
        String[] names = new String[feeds.size()];
        for (int i = 0; i < feeds.size(); i++) names[i] = feeds.get(i).title;
        AlertDialog.Builder b = new AlertDialog.Builder(a, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                .setTitle("Manage private feeds")
                .setNegativeButton("Close", null).setPositiveButton("Add feeds", (d, w) -> addDialog());
        if (feeds.isEmpty()) b.setMessage("Add your Club TWiT RSS links to watch or listen here. Your private feeds and playback positions stay on this TV.");
        else b.setItems(names, (d, w) -> load(feeds.get(w)))
                .setNeutralButton("Remove a feed", (d, w) -> removeDialog());
        dialog = b.show();
    }

    void addDialog() {
        dismiss();
        EditText input = new EditText(a);
        input.setHint("Paste private RSS links, one per line");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        importer = new AlertDialog.Builder(a, AlertDialog.THEME_DEVICE_DEFAULT_DARK).setTitle("Add Club TWiT feeds")
                .setView(input).setNegativeButton("Cancel", (d, w) -> show())
                .setPositiveButton("Add", null).create();
        importer.setOnShowListener(d -> importer.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> importLinks(input.getText().toString())));
        importer.show();
    }

    // The bridge accepts provisioning only while this explicit native import sheet is open.
    void importLinks(String text) {
        if (closed || importer == null || !importer.isShowing()) return;
        if (text == null || text.length() > 24000) { toast("Too many links"); return; }
        List<String> urls = new ArrayList<>();
        for (String s : text.trim().split("[\\s,]+")) {
            if (s.isEmpty()) continue;
            try {
                URL u = new URL(s);
                if (!"https".equals(u.getProtocol()) || !"twit.memberfulcontent.com".equals(u.getHost())
                        || !u.getPath().matches("/rss/[0-9]+") || u.getUserInfo() != null || u.getPort() != -1)
                    throw new Exception();
            } catch (Exception e) { toast("Use a Club TWiT private RSS link"); return; }
            boolean exists = false;
            for (Feed f : feeds) if (sameFeed(f.url, s)) exists = true;
            if (!exists && !urls.contains(s)) urls.add(s);
        }
        if (urls.size() + feeds.size() > 32) { toast("Up to 32 feeds are supported"); return; }
        importer.dismiss(); importer = null;
        if (urls.isEmpty()) { show(); return; }
        loading("Adding private feeds…");
        worker.execute(() -> {
            List<Feed> loaded = new ArrayList<>(); int failed = 0;
            for (String url : urls) {
                try { loaded.add(fetch(url)); } catch (Exception ignored) { failed++; }
            }
            final int errors = failed;
            a.runOnUiThread(() -> {
                if (closed) return;
                for (Feed f : loaded) {
                    boolean duplicate = false;
                    for (Feed old : feeds) if (sameFeed(old.url, f.url)) duplicate = true;
                    if (!duplicate) feeds.add(f);
                }
                save(); show();
                toast(loaded.size() + " feeds added" + (errors > 0 ? "; " + errors + " could not load. Check the links and try again." : ""));
            });
        });
    }

    private static boolean sameFeed(String x, String y) {
        return x.split("\\?")[0].equals(y.split("\\?")[0]);
    }
    private void save() {
        JSONArray out = new JSONArray();
        try {
            for (Feed f : feeds) out.put(new JSONObject().put("url", f.url).put("title", f.title).put("art", f.art));
            prefs.edit().putString("feeds", out.toString()).apply();
            publish();
        } catch (Exception ignored) { }
    }
    private void removeDialog() {
        String[] names = new String[feeds.size()];
        for (int i = 0; i < names.length; i++) names[i] = feeds.get(i).title;
        dialog = new AlertDialog.Builder(a, AlertDialog.THEME_DEVICE_DEFAULT_DARK).setTitle("Remove a feed from this TV")
                .setItems(names, (d, w) -> { feeds.remove(w); save(); show(); })
                .setNegativeButton("Back", (d, w) -> show()).show();
    }
    private void load(Feed old) {
        loading("Loading " + old.title + "…");
        worker.execute(() -> {
            Feed fresh = null;
            try { fresh = fetch(old.url); } catch (Exception ignored) { }
            final Feed result = fresh;
            a.runOnUiThread(() -> {
                if (closed) return;
                if (result == null) {
                    dismiss();
                    dialog = new AlertDialog.Builder(a, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                            .setTitle("Feed could not load").setMessage("Check the TV's connection and that this private feed link is still valid.")
                            .setPositiveButton("Retry", (d, w) -> load(old)).setNegativeButton("Back", (d, w) -> show()).show();
                } else {
                    int index = feeds.indexOf(old); if (index >= 0) feeds.set(index, result);
                    save(); episodes(result);
                }
            });
        });
    }
    private void episodes(Feed f) {
        dismiss();
        String[] names = new String[f.episodes.size()];
        for (int i = 0; i < names.length; i++) {
            Episode e = f.episodes.get(i);
            names[i] = (e.video ? "▶ Video  ·  " : "♫ Audio  ·  ") + e.title + "\n" + e.date
                    + (e.duration > 0 ? "  ·  " + Math.round(e.duration / 60) + " min" : "");
        }
        AlertDialog.Builder b = new AlertDialog.Builder(a, AlertDialog.THEME_DEVICE_DEFAULT_DARK).setTitle(f.title)
                .setNegativeButton("Back to feeds", (d, w) -> show()).setNeutralButton("Refresh", (d, w) -> load(f));
        if (names.length == 0) b.setMessage("No playable episodes in this feed.");
        else b.setItems(names, (d, w) -> {
            Episode e = f.episodes.get(w);
            double at = a.prefs.getFloat("private_position_" + e.id, 0);
            if (at > 10 && (e.duration <= 0 || at < e.duration - 30)) {
                dialog = new AlertDialog.Builder(a, AlertDialog.THEME_DEVICE_DEFAULT_DARK).setTitle(e.title)
                        .setMessage("Resume at " + (int) at / 60 + ":" + String.format(java.util.Locale.US, "%02d", (int) at % 60) + "?")
                        .setPositiveButton("Resume", (x, y) -> play(e, at))
                        .setNeutralButton("Start over", (x, y) -> play(e, 0)).setNegativeButton("Back", (x, y) -> episodes(f)).show();
            } else play(e, 0);
        });
        dialog = b.show();
        dialog.setOnCancelListener(d -> show());
    }
    private void play(Episode e, double at) {
        dismiss(); a.playback.playPrivate(e, at);
        if (!e.video) player(e);
    }
    private void player(Episode episode) {
        dialog = new AlertDialog.Builder(a, AlertDialog.THEME_DEVICE_DEFAULT_DARK).setTitle(episode.title)
                .setMessage("Loading audio…")
                .setPositiveButton("Play / Pause", null).setNeutralButton("Forward 30 s", null)
                .setNegativeButton("Back to episodes", (d, w) -> {}).show();
        AlertDialog player = dialog;
        player.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> a.media("toggle"));
        player.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> a.media("fwd"));
        player.getWindow().getDecorView().post(new Runnable() {
            @Override public void run() {
                if (!player.isShowing() || closed) return;
                NowPlaying n = a.playback.now();
                if (n != null && n.id.equals("private:" + episode.id)) {
                    int t = (int) n.now();
                    player.setMessage(episode.show + "\n\n" + (n.playing ? "Playing" : "Paused / loading")
                            + "  ·  " + t / 60 + ":" + String.format(java.util.Locale.US, "%02d", t % 60)
                            + "\nProgress is saved on this TV.");
                }
                player.getWindow().getDecorView().postDelayed(this, 1000);
            }
        });
    }
    private void loading(String message) {
        dismiss(); dialog = new AlertDialog.Builder(a, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                .setMessage(message).setCancelable(false).show();
    }
    private void dismiss() { if (dialog != null) { dialog.setOnCancelListener(null); dialog.dismiss(); dialog = null; } }
    private void toast(String s) { Toast.makeText(a, s, Toast.LENGTH_LONG).show(); }
    void close() { closed = true; dismiss(); if (importer != null) importer.dismiss(); worker.shutdownNow(); }

    static Feed fetch(String address) throws Exception {
        URL url = new URL(address);
        for (int redirect = 0; redirect < 5; redirect++) {
            if (!"https".equals(url.getProtocol())) throw new Exception("HTTPS required");
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            c.setRequestProperty("User-Agent", "PodcastsTV/1.0");
            c.setConnectTimeout(15000); c.setReadTimeout(20000); c.setInstanceFollowRedirects(false);
            try {
                int status = c.getResponseCode();
                if (status >= 300 && status < 400) { url = new URL(url, c.getHeaderField("Location")); continue; }
                if (status != 200) throw new Exception("Feed unavailable");
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                try (InputStream in = c.getInputStream()) {
                    byte[] buf = new byte[8192]; int n;
                    while ((n = in.read(buf)) != -1) {
                        if (out.size() + n > 8 * 1024 * 1024) throw new Exception("Feed too large");
                        out.write(buf, 0, n);
                    }
                }
                Feed f = parse(out.toByteArray()); f.url = address; return f;
            } finally { c.disconnect(); }
        }
        throw new Exception("Too many redirects");
    }
    static Feed parse(byte[] xml) throws Exception {
        XmlPullParser p = Xml.newPullParser();
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true);
        p.setInput(new ByteArrayInputStream(xml), null);
        Feed f = new Feed(); Episode item = null; int itemDepth = -1;
        for (int event = p.next(); event != XmlPullParser.END_DOCUMENT; event = p.next()) {
            if (event == XmlPullParser.DOCDECL) throw new Exception("DTD not supported");
            if (event == XmlPullParser.START_TAG) {
                String name = p.getName();
                if ("item".equals(name)) { item = new Episode(); itemDepth = p.getDepth(); }
                else if ("title".equals(name)) {
                    String title = p.nextText().trim();
                    if (item != null) item.title = title; else if (f.title.isEmpty()) f.title = title.replaceFirst("\\s*[—–]\\s*Private to.*$", "");
                } else if ("image".equals(name) && p.getAttributeValue(null, "href") != null) {
                    String art = p.getAttributeValue(null, "href");
                    if (art.startsWith("https://")) { if (item != null) item.art = art; else f.art = art; }
                } else if (item != null) {
                    if ("enclosure".equals(name)) {
                        item.src = p.getAttributeValue(null, "url");
                        String type = p.getAttributeValue(null, "type"); item.video = type != null && type.startsWith("video/");
                    } else if ("guid".equals(name)) item.id = p.nextText();
                    else if ("pubDate".equals(name)) item.date = p.nextText().replaceFirst(" [0-9]{2}:[0-9]{2}:[0-9]{2}.*$", "");
                    else if ("duration".equals(name)) {
                        String value = p.nextText(); double seconds = 0;
                        try { for (String part : value.split(":")) seconds = seconds * 60 + Double.parseDouble(part); item.duration = seconds; }
                        catch (NumberFormatException ignored) { }
                    }
                }
            } else if (event == XmlPullParser.END_TAG && item != null && p.getDepth() == itemDepth && "item".equals(p.getName())) {
                if (item.src != null && item.src.startsWith("https://") && !item.title.isEmpty()) {
                    item.id = digest(f.title + "\n" + (item.id.isEmpty() ? item.title : item.id));
                    item.show = f.title; if (item.art.isEmpty()) item.art = f.art;
                    if (f.episodes.size() < 500) f.episodes.add(item);
                }
                item = null;
            }
        }
        if (f.title.isEmpty()) throw new Exception("Not a podcast feed");
        return f;
    }
    private static String digest(String s) throws Exception {
        byte[] bytes = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        StringBuilder out = new StringBuilder();
        for (byte b : bytes) out.append(String.format(java.util.Locale.US, "%02x", b & 255));
        return out.toString();
    }
}
