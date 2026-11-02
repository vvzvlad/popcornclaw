package xyz.vvzvlad.androidclaw;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Starts the panel service when the box finishes booting. */
public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            context.startForegroundService(new Intent(context, PanelService.class));
        }
    }
}
