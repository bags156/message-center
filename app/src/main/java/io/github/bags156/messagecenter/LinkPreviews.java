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
        File dir = new File(context.getCacheDir(), "links-v2"); // v2: earlier results could be bot-check pages
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
            int code = c.getResponseCode();
            String finalUrl = c.getURL().toString(); // after redirects (short links like a.co)
            String type = c.getContentType() == null ? "" : c.getContentType().toLowerCase();
            if (code >= 400 || !type.contains("html")) {
                // Blocked or not a page; a product link can still be named from its address.
                c.disconnect(); String named = ChatText.previewTitle("", "", finalUrl);
                if (!named.isEmpty()) { out.put("title", limit(named, 140)); out.put("site", siteFor(finalUrl, "")); }
                return out;
            }
            String html;
            try (InputStream in = c.getInputStream()) { html = new String(read(in, MAX_PAGE), charset(type)); }
            finally { c.disconnect(); }
            Map<String, String> meta = ChatText.metaTags(html);
            String site = first(meta, "og:site_name");
            String title = first(meta, "og:title", "twitter:title", "title");
            if (title.isEmpty()) { Matcher t = Pattern.compile("(?is)<title[^>]*>(.*?)</title>").matcher(html); if (t.find()) title = ChatText.clean(t.group(1)); }
            title = ChatText.previewTitle(title, site, finalUrl);
            out.put("title", limit(title, 140));
            out.put("description", limit(first(meta, "og:description", "twitter:description", "description"), 200));
            out.put("site", siteFor(finalUrl, site));
            String image = first(meta, "og:image:secure_url", "og:image", "og:image:url", "twitter:image", "twitter:image:src");
            if (!image.isEmpty() && !title.isEmpty()) {
                String imageUrl = URI.create(finalUrl).resolve(image.trim()).toString().replaceFirst("(?i)^http://", "https://");
                File file = new File(dir, MediaCache.hash(url) + ".img");
                int[] size = download(imageUrl, file);
                // Tiny images (placeholders, icons) would be blown up into a blurry banner: keep only real pictures.
                if (size != null && Math.min(size[0], size[1]) >= 64) { out.put("image", file.getPath()); out.put("imageW", size[0]); out.put("imageH", size[1]); }
                else file.delete();
            }
        } catch (Exception ignored) {}
        return out;
    }

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(6000); c.setReadTimeout(8000); c.setInstanceFollowRedirects(true);
        // Sites send their preview details (title, picture) to messaging apps' link previews but
        // often block unknown apps; Amazon, for one, returns a bot check. This names the app too.
        c.setRequestProperty("User-Agent", "WhatsApp/2 (compatible; MessageCenter link preview; +https://github.com/bags156/message-center)");
        c.setRequestProperty("Accept", "text/html,application/xhtml+xml,image/*;q=0.8");
        c.setRequestProperty("Accept-Language", java.util.Locale.getDefault().toLanguageTag() + ",en;q=0.7");
        return c;
    }

    /** Saves an image and returns its {width, height}, or null if it isn't a readable image. */
    private static int[] download(String url, File file) {
        try {
            if (!url.startsWith("https://")) return null;
            HttpURLConnection c = open(url);
            try {
                String type = c.getContentType() == null ? "" : c.getContentType().toLowerCase();
                if (c.getResponseCode() >= 400 || (!type.isEmpty() && !type.startsWith("image/"))) return null;
                byte[] data;
                try (InputStream in = c.getInputStream()) { data = read(in, MAX_IMAGE); }
                if (data.length == 0 || data.length >= MAX_IMAGE) return null;
                android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options(); o.inJustDecodeBounds = true;
                android.graphics.BitmapFactory.decodeByteArray(data, 0, data.length, o);
                if (o.outWidth <= 0 || o.outHeight <= 0) return null;
                try (FileOutputStream out = new FileOutputStream(file)) { out.write(data); }
                return new int[]{o.outWidth, o.outHeight};
            } finally { c.disconnect(); }
        } catch (Exception e) { file.delete(); return null; }
    }

    /** The site's own name, or its address without "www.". */
    private static String siteFor(String finalUrl, String site) {
        if (site != null && !site.trim().isEmpty()) return site.trim();
        String host = ChatText.domain(finalUrl); return host.startsWith("www.") ? host.substring(4) : host;
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
