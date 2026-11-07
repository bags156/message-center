package io.github.bags156.messagecenter;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Title, description, and image for a link, read from the page's Open Graph tags. Pages are
 * fetched by this phone (like a browser would, so the site sees your IP address), never run
 * as a web page, and cached for a week, including failures, so each link loads once.
 */
final class LinkPreviews {
    private LinkPreviews() {}

    interface Done { void done(JSONObject preview); }

    private static final ExecutorService pool = Executors.newFixedThreadPool(2);
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final Map<String, JSONObject> memory = new HashMap<>();
    private static final Set<String> loading = new HashSet<>();
    private static final long WEEK = 7L * 24 * 60 * 60 * 1000;
    private static final int MAX_PAGE = 300 * 1024, MAX_IMAGE = 3 * 1024 * 1024;

    /** A preview already in memory, or null. Has "title", "description", "site", "image" (file path), "url". */
    static synchronized JSONObject peek(String url) { return memory.get(url); }

    /** Loads a preview in the background; the callback runs on the main thread only if a title was found. */
    static void load(Context context, String url, Done done) {
        synchronized (LinkPreviews.class) {
            JSONObject hit = memory.get(url);
            if (hit != null) { if (!hit.optString("title").isEmpty()) done.done(hit); return; }
            if (!loading.add(url)) return;
        }
        File dir = new File(context.getCacheDir(), "links");
        pool.execute(() -> {
            JSONObject preview = cached(dir, url);
            if (preview == null) { preview = fetch(dir, url); save(dir, url, preview); }
            JSONObject result = preview;
            synchronized (LinkPreviews.class) { memory.put(url, result); loading.remove(url); }
            if (!result.optString("title").isEmpty()) main.post(() -> done.done(result));
        });
    }

    private static JSONObject cached(File dir, String url) {
        File f = new File(dir, MediaCache.hash(url) + ".json");
        if (!f.isFile() || System.currentTimeMillis() - f.lastModified() > WEEK) return null;
        try { return new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8)); } catch (Exception e) { return null; }
    }

    private static void save(File dir, String url, JSONObject preview) {
        try { if (!dir.isDirectory()) dir.mkdirs(); Files.write(new File(dir, MediaCache.hash(url) + ".json").toPath(), preview.toString().getBytes(StandardCharsets.UTF_8)); }
        catch (Exception ignored) {}
    }

    private static JSONObject fetch(File dir, String url) {
        JSONObject out = new JSONObject();
        try {
            out.put("url", url);
            String target = url.replaceFirst("(?i)^http://", "https://"); // this app only makes encrypted connections
            HttpURLConnection c = open(target);
            String type = c.getContentType() == null ? "" : c.getContentType().toLowerCase();
            if (c.getResponseCode() >= 400 || !type.contains("html")) { c.disconnect(); return out; }
            String html;
            try (InputStream in = c.getInputStream()) { html = new String(read(in, MAX_PAGE), charset(type)); }
            finally { c.disconnect(); }
            Map<String, String> meta = ChatText.metaTags(html);
            String title = first(meta, "og:title", "twitter:title");
            if (title.isEmpty()) { Matcher t = Pattern.compile("(?is)<title[^>]*>(.*?)</title>").matcher(html); if (t.find()) title = ChatText.clean(t.group(1)); }
            out.put("title", limit(title, 140));
            out.put("description", limit(first(meta, "og:description", "twitter:description", "description"), 200));
            out.put("site", first(meta, "og:site_name"));
            String image = first(meta, "og:image", "og:image:url", "twitter:image");
            if (!image.isEmpty() && !out.optString("title").isEmpty()) {
                String imageUrl = URI.create(c.getURL().toString()).resolve(image.trim()).toString().replaceFirst("(?i)^http://", "https://");
                File file = new File(dir, MediaCache.hash(url) + ".img");
                if (download(imageUrl, file)) out.put("image", file.getPath());
            }
        } catch (Exception ignored) {}
        return out;
    }

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(6000); c.setReadTimeout(8000); c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) MessageCenterLinkPreview/1.0");
        c.setRequestProperty("Accept", "text/html,image/*;q=0.8");
        return c;
    }

    private static boolean download(String url, File file) {
        try {
            if (!url.startsWith("https://")) return false;
            HttpURLConnection c = open(url);
            try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(file)) {
                if (c.getResponseCode() >= 400) return false;
                byte[] data = read(in, MAX_IMAGE);
                if (data.length == 0 || data.length >= MAX_IMAGE) return false;
                out.write(data); return true;
            } finally { c.disconnect(); }
        } catch (Exception e) { file.delete(); return false; }
    }

    private static byte[] read(InputStream in, int max) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream(); byte[] buf = new byte[8192]; int n;
        while ((n = in.read(buf)) > 0 && b.size() < max) b.write(buf, 0, Math.min(n, max - b.size()));
        return b.toByteArray();
    }

    private static java.nio.charset.Charset charset(String contentType) {
        Matcher m = Pattern.compile("charset=([\\w-]+)").matcher(contentType);
        try { return m.find() ? java.nio.charset.Charset.forName(m.group(1)) : StandardCharsets.UTF_8; } catch (Exception e) { return StandardCharsets.UTF_8; }
    }

    private static String first(Map<String, String> meta, String... keys) {
        for (String k : keys) { String v = meta.get(k); if (v != null && !v.isEmpty()) return v; }
        return "";
    }

    private static String limit(String s, int max) { return s.length() <= max ? s : s.substring(0, max - 1) + "…"; }
}
