package xyz.vvzvlad.popcornclaw;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Starts {@link AgentService} once the box has booted. */
public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            context.startService(new Intent(context, AgentService.class));
        }
    }
}
