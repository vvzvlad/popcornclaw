package xyz.vvzvlad.popcornclaw;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

/**
 * Catches the phone remote's ACTION_ASSIST, which carries the TV remote service's decoded-audio binder
 * (AUDIO_STREAM_KEY), and captures it WITHOUT raising the launcher over playing video.
 *
 * The launcher's MainActivity is singleTask + clearTaskOnLaunch with an opaque theme, so handling ASSIST there
 * pulled the whole home screen to the front and cleared the task on every mic press, dropping the video to the
 * desktop. This activity is translucent, in its own task (empty taskAffinity), and finishes in onCreate, so the
 * app playing underneath (mpv/SmartTube) is only paused, never stopped, and stays on screen.
 *
 * {@link VoiceProbe#record} extracts the binder, pushes it to the warm voice relay and starts the collector
 * thread; it runs in the launcher's main process (no android:process), the same process as {@link PicoLink} and
 * {@link AgentService}, so the captured WAV reaches the open pico WebSocket. A binder-less ASSIST (KEYCODE_ASSIST
 * from remotes without a phone mic) carries no AUDIO_STREAM_KEY: it is handed to MainActivity's existing
 * Walker voice path, whose UI comes up as before.
 */
public final class VoiceAssistActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent i = getIntent();
        boolean captured = i != null && Intent.ACTION_ASSIST.equals(i.getAction()) && VoiceProbe.record(this, i);
        if (!captured) {
            // No decoded-audio binder in the intent: let the launcher open the voice UI as it always has.
            try {
                startActivity(new Intent(this, com.omarchytv.home.MainActivity.class)
                        .setAction(Intent.ACTION_ASSIST)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception e) {
                Log.e(VoiceHelper.TAG, "voice: handing ASSIST to the launcher failed", e);
            }
        }
        finish();
        // No transition: the capture must be invisible, the video must not flicker.
        overridePendingTransition(0, 0);
    }
}
