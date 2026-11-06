package xyz.vvzvlad.popcornclaw;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * The app's own switches, set from the control panel ({@link ControlServer}) and kept in the app's
 * SharedPreferences -- not in the gateway's config.json, which only the gateway's settings belong in.
 */
final class AppSettings {
    private static final String PREFS = "popcornclaw_settings";
    private static final String VOICE_REMOTE = "voice_remote";

    private AppSettings() {
    }

    /**
     * Whether the remote's voice button talks to the agent. On by default. AgentService applies it at its start
     * and whenever the panel changes it (ACTION_VOICE).
     */
    static boolean getVoiceRemote(Context c) {
        return prefs(c).getBoolean(VOICE_REMOTE, true);
    }

    static void setVoiceRemote(Context c, boolean on) {
        prefs(c).edit().putBoolean(VOICE_REMOTE, on).apply();
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
