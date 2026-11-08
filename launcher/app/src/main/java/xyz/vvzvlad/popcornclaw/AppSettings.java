package xyz.vvzvlad.popcornclaw;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Locale;

/**
 * The app's own switches, set from the control panel ({@link ControlServer}) and kept in the app's
 * SharedPreferences -- not in the gateway's config.json, which only the gateway's settings belong in.
 */
final class AppSettings {
    private static final String PREFS = "popcornclaw_settings";
    private static final String VOICE_REMOTE = "voice_remote";
    private static final String SPEECH_LANGUAGE = "speech_language";

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

    /**
     * The BCP 47 tag the remote's voice button is recognized in; the system language while none is stored.
     * VoiceSearchActivity reads it at each recognition.
     */
    static String getSpeechLanguage(Context c) {
        String tag = getStoredSpeechLanguage(c);
        return tag.isEmpty() ? Locale.getDefault().toLanguageTag() : tag;
    }

    /** The tag the panel stored, "" when none: the panel shows it as is, blank for the system language. */
    static String getStoredSpeechLanguage(Context c) {
        return prefs(c).getString(SPEECH_LANGUAGE, "");
    }

    static void setSpeechLanguage(Context c, String tag) {
        prefs(c).edit().putString(SPEECH_LANGUAGE, tag).apply();
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
