package io.github.bags156.messagecenter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
public final class PairedBootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context,Intent intent) {
        // Alarms for scheduled messages don't survive a restart or an app update; set them again.
        if(Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction()) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())){Scheduled.rearmAll(context);Reminders.rearmAll(context);Nudges.reschedule(context);}
        if((Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction()) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction()))
            && context.getSharedPreferences("paired",Context.MODE_PRIVATE).getBoolean("enabled",false) && new SessionVault(context).exists()) {
            try{PairedService.start(context);}catch(RuntimeException ignored){/* User can reopen app if OEM blocks startup. */}
        }
    }
}
