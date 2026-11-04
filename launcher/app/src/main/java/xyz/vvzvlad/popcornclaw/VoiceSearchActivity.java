package xyz.vvzvlad.popcornclaw;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.security.SecureRandom;
import java.util.List;

/**
 * Catches the Bluetooth voice remote's mic press and sends the spoken question to the agent instead of Google
 * search.
 *
 * The remote's mic key is KEYCODE_VOICE_ASSIST, for which the system starts android.speech.action.WEB_SEARCH
 * with no extras. At the same moment the TV remote service (com.google.android.tv.remote.service) bridges the
 * remote's mic audio through its AudioPolicy into voice-recognition capture, so the default RecognitionService
 * (katniss) hears it. This activity listens with the in-app {@link SpeechRecognizer} in
 * {@link PicoLink#SPEECH_LANGUAGE} rather than the RECOGNIZE_SPEECH intent, which on Android 9 returned
 * nothing to onActivityResult, and hands the recognized text to {@link PicoLink#ask} exactly as the assistant
 * screen sends a typed question; CommandPopup shows the question and the answer over whatever is playing.
 *
 * Like {@link VoiceAssistActivity} it is translucent and in its own task, so the app playing underneath is only
 * paused, never stopped; unlike it, it stays alive until the recognizer returns.
 */
public final class VoiceSearchActivity extends Activity {
    private SpeechRecognizer recognizer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        recognizer = SpeechRecognizer.createSpeechRecognizer(this);
        recognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle p) { }
            @Override public void onBeginningOfSpeech() { }
            @Override public void onRmsChanged(float v) { }
            @Override public void onBufferReceived(byte[] b) { }
            @Override public void onEndOfSpeech() { }
            @Override public void onPartialResults(Bundle r) { }
            @Override public void onEvent(int type, Bundle p) { }

            @Override public void onResults(Bundle r) {
                List<String> l = r == null ? null : r.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                String s = l != null && !l.isEmpty() && l.get(0) != null ? l.get(0).trim() : "";
                if (!s.isEmpty()) {
                    try {
                        PicoLink.ask(new JSONObject()
                                .put("id", Long.toHexString(new SecureRandom().nextLong()))
                                .put("text", s));
                    } catch (JSONException e) {
                        throw new IllegalStateException(e);
                    }
                }
                close();
            }

            @Override public void onError(int err) {
                Log.w(VoiceHelper.TAG, "voice search: recognizer error " + err);
                close();
            }
        });
        recognizer.startListening(new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, PicoLink.SPEECH_LANGUAGE)
                .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName()));
    }

    private void close() {
        finish();
        // No transition: the video underneath must not flicker.
        overridePendingTransition(0, 0);
    }

    @Override
    protected void onDestroy() {
        recognizer.destroy();
        super.onDestroy();
    }
}
