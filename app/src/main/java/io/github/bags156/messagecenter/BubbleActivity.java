package io.github.bags156.messagecenter;

/**
 * The same chat screen, hosted inside an Android conversation bubble. Android embeds this
 * activity in a floating window, so it shows one conversation and never the inbox.
 */
public final class BubbleActivity extends PairedActivity {
    @Override protected boolean bubble() { return true; }
}
