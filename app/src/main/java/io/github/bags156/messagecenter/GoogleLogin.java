package io.github.bags156.messagecenter;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Rules for the embedded Google sign-in, kept free of Android types so they can be unit tested. */
final class GoogleLogin {
    static final String CONFIG = "https://messages.google.com/web/config";
    static final String START = "https://accounts.google.com/AccountChooser?continue=" + CONFIG;
    /** How many times the app moves past optional Google prompts on its own before leaving it to the user. */
    static final int MAX_SKIPS = 2;
    /** Consecutive polls with a signed-in session before treating it as settled. */
    static final int STABLE_POLLS = 2;

    private GoogleLogin() {}

    /**
     * Top-level pages the sign-in chain may pass through. Google redirects through
     * accounts.youtube.com to set its session; blocking that redirect stalls the page.
     */
    static boolean allowed(String scheme, String host) {
        if ("about".equals(scheme)) return true;
        if (!"https".equals(scheme) || host == null) return false;
        String h = host.toLowerCase(Locale.ROOT);
        return h.equals("google.com") || h.endsWith(".google.com") || h.equals("accounts.youtube.com");
    }

    static boolean isConfig(String scheme, String host, String path) {
        return "https".equals(scheme) && "messages.google.com".equals(host) && "/web/config".equals(path);
    }

    /** Google has signed the account in once the session cookies used for pairing are present. */
    static boolean signedIn(String cookieHeader) {
        Set<String> names = names(cookieHeader);
        return names.contains("SID") && names.contains("SAPISID");
    }

    /**
     * After sign-in has settled, optional prompts (profile photo, recovery info, passkeys)
     * are left by going straight to Messages, the same destination Google's own "Not now" uses.
     */
    static boolean shouldSkipAhead(boolean onConfig, int stablePolls, int skipsUsed) {
        return !onConfig && stablePolls >= STABLE_POLLS && skipsUsed < MAX_SKIPS;
    }

    static boolean cookieAllowed(String name) {
        return name.equals("SID") || name.equals("HSID") || name.equals("SSID") || name.equals("OSID")
            || name.equals("APISID") || name.equals("SAPISID") || name.startsWith("__Secure-1PSID")
            || name.startsWith("__Secure-3PSID") || name.equals("__Secure-1PAPISID") || name.equals("__Secure-3PAPISID");
    }

    /** Host and path only (never the query, which can carry sign-in tokens), for the address line. */
    static String describe(String host, String path) {
        if (host == null) return "";
        String p = path == null || path.equals("/") ? "" : path;
        if (p.length() > 40) p = p.substring(0, 39) + "…";
        return host + p;
    }

    static Set<String> names(String cookieHeader) {
        Set<String> names = new HashSet<>();
        if (cookieHeader == null) return names;
        for (String pair : cookieHeader.split(";")) {
            int eq = pair.indexOf('=');
            if (eq > 0) names.add(pair.substring(0, eq).trim());
        }
        return names;
    }
}
