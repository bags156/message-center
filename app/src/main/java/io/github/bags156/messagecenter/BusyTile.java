package io.github.bags156.messagecenter;

import android.content.ComponentName;
import android.content.Context;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import java.text.DateFormat;
import java.util.Date;

/**
 * Quick Settings tile for busy mode: tap to turn the auto-reply on (until you turn it off) or
 * off. Shows when it ends if it was turned on for a while from the app.
 */
public class BusyTile extends TileService {
    @Override public void onStartListening() { refresh(); }

    @Override public void onClick() {
        boolean on = PairedService.autoReplyUntil(this) > System.currentTimeMillis();
        PairedService.setAutoReply(this, on ? 0 : Long.MAX_VALUE);
        refresh();
    }

    private void refresh() {
        Tile tile = getQsTile();
        if (tile == null) return;
        long until = PairedService.autoReplyUntil(this);
        boolean on = until > System.currentTimeMillis();
        tile.setState(on ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.setLabel("Busy");
        tile.setSubtitle(!on ? "Off" : until == Long.MAX_VALUE ? "Auto-reply on"
            : "Until " + DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(until)));
        tile.setContentDescription(on ? "Busy auto-reply is on" : "Busy auto-reply is off");
        tile.updateTile();
    }

    /** Redraws the tile after busy mode changes in the app. */
    static void changed(Context context) {
        try { TileService.requestListeningState(context, new ComponentName(context, BusyTile.class)); } catch (Exception ignored) { }
    }
}
