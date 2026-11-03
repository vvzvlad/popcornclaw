package xyz.vvzvlad.popcornclaw;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.SystemClock;
import android.util.Log;

/**
 * SPIKE, app side: on ACTION_ASSIST pull the remote service's audio binder out of the intent and push it to
 * the warm root relay, from where the katniss-uid worker reads the PCM (see {@link VoiceHelper}).
 *
 * The binder (blj) is a strong binder inside SHOW_SESSION_ARGS → "AUDIO_STREAM_KEY", a Parcelable of the
 * service's own class (AutoValue_AudioStream) that this app cannot load: touching the inner bundle's values
 * the normal way throws BadParcelableException. So the bundle is re-serialized to a raw Parcel and walked by
 * hand to the strong binder, never instantiating that class. Layout is fixed by the service's writer
 * (bka.writeToParcel) and reader (amz CREATOR case 12): typed-object null marker, four ints, then the strong
 * binder, then one int — five ints to skip before the binder.
 */
public final class VoiceProbe {
    private static final String TAG = VoiceHelper.TAG;
    // Parcel.VAL_PARCELABLE, the value type the service's AudioStream is written as.
    private static final int VAL_PARCELABLE = 4;
    // How long to wait for the worker's WAV (the service closes the pipe ~15 s after the press).
    private static final long COLLECT_MS = 18_000;

    private VoiceProbe() {
    }

    /** Handles ACTION_ASSIST {@code i}; true when a binder was pushed and the collector started. */
    public static boolean record(Context c, Intent i) {
        VoiceHelper.exemptHiddenApi();
        IBinder blj = extractBinder(i);
        if (blj == null) return false;
        IBinder relay = VoiceHelper.getService(VoiceHelper.SERVICE_NAME);
        if (relay == null) {
            Log.w(TAG, "app: relay not registered yet");
            return false;
        }
        // Tags this press so the worker's WAV is matched to it: a later press evicts an earlier binder in the
        // relay, and this collector accepts back only the WAV carrying its own id, never a slow earlier capture.
        String requestId = java.util.UUID.randomUUID().toString();
        Parcel data = Parcel.obtain();
        try {
            data.writeInterfaceToken(VoiceHelper.DESCRIPTOR);
            data.writeString(requestId);
            data.writeStrongBinder(blj);
            relay.transact(VoiceHelper.TXN_SET_BINDER, data, null, IBinder.FLAG_ONEWAY);
            Log.i(TAG, "app: pushed audio binder to relay id=" + requestId);
        } catch (Exception e) {
            Log.e(TAG, "app: relay transact failed", e);
            return false;
        } finally {
            data.recycle();
        }
        IBinder r = relay;
        new Thread(() -> collectAndSend(r, requestId), "popcornclaw-voice-collect").start();
        return true;
    }

    // Waits for the worker to leave this press's WAV in the relay, then sends it to the gateway over PicoLink.
    private static void collectAndSend(IBinder relay, String requestId) {
        long deadline = SystemClock.uptimeMillis() + COLLECT_MS;
        while (SystemClock.uptimeMillis() < deadline) {
            byte[] wav;
            try {
                wav = VoiceHelper.getAudio(relay, requestId);
            } catch (Exception e) {
                Log.e(TAG, "app: get audio failed", e);
                return;
            }
            if (wav != null && wav.length > 0) {
                Log.i(TAG, "app: got " + wav.length + " WAV bytes from relay");
                PicoLink.sendAudio(wav);
                return;
            }
            try {
                Thread.sleep(150);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        Log.i(TAG, "app: no audio captured within the window");
    }

    /** The strong binder in AUDIO_STREAM_KEY, or null; never instantiates the service's Parcelable. */
    private static IBinder extractBinder(Intent i) {
        Bundle args;
        try {
            // Unparcels only the outer extras ({SHOW_SESSION_ARGS}); the nested bundle stays lazy.
            args = i == null ? null : i.getBundleExtra("SHOW_SESSION_ARGS");
        } catch (Throwable t) {
            Log.e(TAG, "app: reading SHOW_SESSION_ARGS", t);
            return null;
        }
        if (args == null) {
            Log.w(TAG, "app: no SHOW_SESSION_ARGS in assist intent");
            return null;
        }
        Parcel p = Parcel.obtain();
        try {
            // Copies the lazy bundle's raw bytes without unparcelling its Parcelable values.
            args.writeToParcel(p, 0);
            p.setDataPosition(0);
            p.readInt();            // bundle byte length
            p.readInt();            // bundle magic
            int entries = p.readInt();
            for (int e = 0; e < entries; e++) {
                String key = p.readString();
                if (!"AUDIO_STREAM_KEY".equals(key)) {
                    Log.w(TAG, "app: unexpected session-args key " + key);
                    return null;   // the inner bundle holds only AUDIO_STREAM_KEY
                }
                int type = p.readInt();
                if (type != VAL_PARCELABLE) {
                    Log.w(TAG, "app: unexpected value type " + type);
                    return null;
                }
                String cls = p.readString();
                Log.i(TAG, "app: audio stream class=" + cls);
                for (int k = 0; k < 5; k++) p.readInt();
                IBinder b = p.readStrongBinder();
                Log.i(TAG, "app: extracted audio binder=" + b);
                return b;
            }
            return null;
        } catch (Throwable t) {
            Log.e(TAG, "app: parsing session args", t);
            return null;
        } finally {
            p.recycle();
        }
    }
}
