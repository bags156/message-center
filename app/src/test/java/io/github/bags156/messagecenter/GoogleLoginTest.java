package io.github.bags156.messagecenter;

import org.junit.Test;
import static org.junit.Assert.*;

public class GoogleLoginTest {
    @Test public void allowsGoogleSignInChainOnly() {
        assertTrue(GoogleLogin.allowed("https", "accounts.google.com"));
        assertTrue(GoogleLogin.allowed("https", "messages.google.com"));
        assertTrue(GoogleLogin.allowed("https", "Accounts.YouTube.com"));
        assertTrue(GoogleLogin.allowed("about", null));
        assertFalse(GoogleLogin.allowed("http", "accounts.google.com"));
        assertFalse(GoogleLogin.allowed("https", "www.youtube.com"));
        assertFalse(GoogleLogin.allowed("https", "evilgoogle.com"));
        assertFalse(GoogleLogin.allowed("https", "google.com.example.net"));
        assertFalse(GoogleLogin.allowed("intent", null));
    }

    @Test public void signedInNeedsBothSessionCookies() {
        assertFalse(GoogleLogin.signedIn(null));
        assertFalse(GoogleLogin.signedIn("NID=1; SID=abc"));
        assertFalse(GoogleLogin.signedIn("__Secure-3PSID=x; SAPISIDX=y"));
        assertTrue(GoogleLogin.signedIn("NID=1; SID=abc; HSID=h; SAPISID=s/x=="));
    }

    @Test public void skipsOptionalPromptsOnlyAfterSessionSettles() {
        assertFalse(GoogleLogin.shouldSkipAhead(false, 1, 0));
        assertTrue(GoogleLogin.shouldSkipAhead(false, 2, 0));
        assertTrue(GoogleLogin.shouldSkipAhead(false, 5, 1));
        assertFalse(GoogleLogin.shouldSkipAhead(true, 5, 0));
        assertFalse(GoogleLogin.shouldSkipAhead(false, 5, GoogleLogin.MAX_SKIPS));
    }

    @Test public void recognizesConfigPage() {
        assertTrue(GoogleLogin.isConfig("https", "messages.google.com", "/web/config"));
        assertFalse(GoogleLogin.isConfig("https", "messages.google.com", "/web/conversations"));
        assertFalse(GoogleLogin.isConfig("https", "accounts.google.com", "/web/config"));
    }

    @Test public void addressLineOmitsQuery() {
        assertEquals("accounts.google.com/signin/challenge", GoogleLogin.describe("accounts.google.com", "/signin/challenge"));
        assertEquals("gds.google.com", GoogleLogin.describe("gds.google.com", "/"));
        assertTrue(GoogleLogin.describe("a.google.com", "/" + "x".repeat(80)).endsWith("…"));
    }

    @Test public void forwardsOnlySessionCookies() {
        assertTrue(GoogleLogin.cookieAllowed("SAPISID"));
        assertTrue(GoogleLogin.cookieAllowed("__Secure-1PSIDTS"));
        assertFalse(GoogleLogin.cookieAllowed("NID"));
    }
}
