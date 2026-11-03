package xyz.vvzvlad.popcornclaw;

import android.os.Binder;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;

/**
 * Gets the phone remote's live voice out of the TV remote service. The service hands the decoded PCM only
 * through a one-shot binder (blj, IndirectParcelFileDescriptor) whose transact(1) is rejected unless the
 * caller's uid is the assistant package's uid (katniss). So the app cannot read it directly.
 *
 * This runs through app_process, launched by {@link AgentService} in two roles kept warm for the ~15 s window:
 *   relay  — uid 0 (root), the only uid that may ServiceManager.addService; a rendezvous that holds the binder
 *            the app pushes and the WAV the worker produces (it never transacts the service binder itself);
 *   worker — uid = katniss, opens its own /dev/binder as that uid, so transact(1) passes the uid gate; it reads
 *            the pipe as 16 kHz/16-bit/mono PCM, wraps it as WAV and hands it back through the relay.
 *
 * Flow per mic press: the app ({@link VoiceProbe}) extracts blj from ACTION_ASSIST and pushes it to the relay;
 * the worker pulls it, reads the PCM, pushes the WAV to the relay; the app pulls the WAV and sends it to the
 * gateway over the already-open pico WebSocket ({@link PicoLink}), where it is transcribed and becomes the
 * owner's message on the shared TV/Telegram dialog.
 */
public final class VoiceHelper {
    static final String TAG = "popcornclaw-voice";
    static final String SERVICE_NAME = "popcornclaw_voice";
    static final String DESCRIPTOR = "popcornclaw.voice";
    static final int TXN_SET_BINDER = 1;
    static final int TXN_GET_BINDER = 2;
    static final int TXN_SET_AUDIO = 3;
    static final int TXN_GET_AUDIO = 4;
    // The remote service's interface and its single transaction (blj.z, code 1).
    private static final String PFD_DESCRIPTOR = "com.google.android.tv.remote.service.IndirectParcelFileDescriptor";
    private static final int PFD_TXN = 1;

    // Pipe PCM format the service writes (bnt.a: 16000/16/1).
    private static final int RATE = 16000;
    // The service closes the pipe ~15 s after the mic press; cap and size guard keep the WAV under the binder limit.
    private static final long CAP_MS = 15_000;
    private static final int MAX_PCM_BYTES = 900_000;
    // Below ~0.3 s of PCM nothing is sent on.
    private static final int MIN_PCM_BYTES = 9_600;

    private VoiceHelper() {
    }

    public static void main(String[] args) {
        exemptHiddenApi();
        String role = args.length > 0 ? args[0] : "";
        // app_process --nice-name sets argv[0] but leaves /proc/pid/comm as "main"; naming the main thread sets
        // comm, so AgentService can drop stale helpers with `pkill pcvoice<role>` without matching its own shell.
        Thread.currentThread().setName("pcvoice" + role);
        Log.i(TAG, "helper start role=" + role + " uid=" + android.os.Process.myUid());
        if ("relay".equals(role)) {
            relay();
        } else if ("worker".equals(role)) {
            worker();
        } else {
            Log.e(TAG, "unknown role " + role);
        }
    }

    /**
     * Root rendezvous: holds the binder the app pushed and the WAV the worker produced, each tagged with the
     * press's request id. A new press (TXN_SET_BINDER) evicts any earlier binder and audio, and the app reads
     * back only the audio tagged with its own id, so a slow capture from an earlier press is never delivered to
     * a later one.
     */
    private static void relay() {
        Looper.prepareMainLooper();
        final Object lock = new Object();
        final String[] binderId = {null};
        final IBinder[] heldBinder = {null};
        final String[] audioId = {null};
        final byte[][] heldAudio = {null};
        Binder stub = new Binder() {
            @Override
            protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
                switch (code) {
                    case TXN_SET_BINDER: {
                        data.enforceInterface(DESCRIPTOR);
                        String id = data.readString();
                        IBinder b = data.readStrongBinder();
                        synchronized (lock) {
                            binderId[0] = id;
                            heldBinder[0] = b;
                            // A new press evicts an earlier press's binder and any audio it left behind.
                            audioId[0] = null;
                            heldAudio[0] = null;
                        }
                        Log.i(TAG, "relay: stored binder id=" + id + " present=" + (b != null));
                        return true;
                    }
                    case TXN_GET_BINDER: {
                        data.enforceInterface(DESCRIPTOR);
                        String id;
                        IBinder b;
                        synchronized (lock) {
                            id = binderId[0];
                            b = heldBinder[0];
                            binderId[0] = null;
                            heldBinder[0] = null;
                        }
                        if (reply != null) {
                            reply.writeNoException();
                            reply.writeString(id);
                            reply.writeStrongBinder(b);
                        }
                        return true;
                    }
                    case TXN_SET_AUDIO: {
                        data.enforceInterface(DESCRIPTOR);
                        String id = data.readString();
                        byte[] a = data.createByteArray();
                        synchronized (lock) {
                            audioId[0] = id;
                            heldAudio[0] = a;
                        }
                        if (reply != null) reply.writeNoException();
                        Log.i(TAG, "relay: stored audio id=" + id + " bytes=" + (a == null ? 0 : a.length));
                        return true;
                    }
                    case TXN_GET_AUDIO: {
                        data.enforceInterface(DESCRIPTOR);
                        String want = data.readString();
                        byte[] a = null;
                        synchronized (lock) {
                            if (want != null && want.equals(audioId[0])) {
                                a = heldAudio[0];
                                heldAudio[0] = null;
                                audioId[0] = null;
                            }
                        }
                        if (reply != null) {
                            reply.writeNoException();
                            reply.writeByteArray(a);
                        }
                        return true;
                    }
                    default:
                        return false;
                }
            }
        };
        try {
            addService(SERVICE_NAME, stub);
        } catch (Throwable t) {
            Log.e(TAG, "relay: addService failed", t);
            return;
        }
        Log.i(TAG, "relay: registered " + SERVICE_NAME);
        Looper.loop();
    }

    /** Worker (katniss uid): pulls the pushed binder, passes the uid gate, pushes the captured WAV back. */
    private static void worker() {
        IBinder relay = null;
        for (int tries = 0; relay == null; tries++) {
            relay = getService(SERVICE_NAME);
            if (relay == null) {
                if (tries == 0) Log.i(TAG, "worker: waiting for relay");
                sleep(200);
            }
        }
        Log.i(TAG, "worker: got relay, polling for audio binder");
        while (true) {
            Grab g;
            try {
                g = pollBinder(relay);
            } catch (Exception e) {
                Log.w(TAG, "worker: relay gone, re-resolving", e);
                relay = getService(SERVICE_NAME);
                if (relay == null) sleep(200);
                continue;
            }
            if (g == null) {
                sleep(120);
                continue;
            }
            Log.i(TAG, "worker: got audio binder id=" + g.id + ", requesting PFD");
            ParcelFileDescriptor pfd = requestPfd(g.binder);
            if (pfd == null) {
                Log.w(TAG, "worker: PFD null (uid gate rejected us or already taken)");
                continue;
            }
            byte[] wav = captureWav(pfd);
            if (wav == null) continue;
            try {
                setAudio(relay, g.id, wav);
                Log.i(TAG, "worker: pushed " + wav.length + " WAV bytes to relay id=" + g.id);
            } catch (Exception e) {
                Log.e(TAG, "worker: setAudio failed", e);
            }
        }
    }

    // A pushed binder with the id of the press it belongs to.
    private static final class Grab {
        final String id;
        final IBinder binder;

        Grab(String id, IBinder binder) {
            this.id = id;
            this.binder = binder;
        }
    }

    private static Grab pollBinder(IBinder relay) throws Exception {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            relay.transact(TXN_GET_BINDER, data, reply, 0);
            reply.readException();
            String id = reply.readString();
            IBinder b = reply.readStrongBinder();
            return b == null ? null : new Grab(id, b);
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    /** Calls blj.transact(1) as the katniss uid; the service returns the pipe's read end or null. */
    private static ParcelFileDescriptor requestPfd(IBinder blj) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(PFD_DESCRIPTOR);
            blj.transact(PFD_TXN, data, reply, 0);
            reply.readException();
            if (reply.readInt() != 0) {
                return ParcelFileDescriptor.CREATOR.createFromParcel(reply);
            }
            return null;
        } catch (Exception e) {
            Log.e(TAG, "worker: transact(1) failed", e);
            return null;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    // Reads until the service closes the pipe (end of the voice session) or the cap; returns a 16 kHz mono WAV,
    // or null when the capture is shorter than MIN_PCM_BYTES (nothing is sent on for an empty/too-short press).
    private static byte[] captureWav(ParcelFileDescriptor pfd) {
        ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        long start = System.currentTimeMillis();
        boolean first = true;
        // AutoCloseInputStream closes the pfd when the stream closes; closing pfd again would risk a double close
        // of a reused fd in this long-lived worker.
        try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(pfd)) {
            byte[] buf = new byte[4096];
            int r;
            while ((r = in.read(buf)) > 0) {
                if (first) {
                    Log.i(TAG, "worker: first PCM chunk after " + (System.currentTimeMillis() - start) + " ms");
                    first = false;
                }
                pcm.write(buf, 0, r);
                if (pcm.size() > MAX_PCM_BYTES || System.currentTimeMillis() - start > CAP_MS) {
                    Log.i(TAG, "worker: cap reached");
                    break;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "worker: read failed after " + pcm.size() + " bytes", e);
        }
        int n = pcm.size();
        Log.i(TAG, "worker: captured " + n + " PCM bytes");
        if (n < MIN_PCM_BYTES) {
            Log.i(TAG, "worker: too short, dropping");
            return null;
        }
        byte[] wav = new byte[44 + n];
        System.arraycopy(wavHeader(n), 0, wav, 0, 44);
        System.arraycopy(pcm.toByteArray(), 0, wav, 44, n);
        return wav;
    }

    private static byte[] wavHeader(int data) {
        java.nio.ByteBuffer h = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        h.put("RIFF".getBytes()).putInt(36 + data).put("WAVE".getBytes());
        h.put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) 1).putInt(RATE)
                .putInt(RATE * 2).putShort((short) 2).putShort((short) 16);
        h.put("data".getBytes()).putInt(data);
        return h.array();
    }

    // --- shared helpers: relay transactions and ServiceManager, used by the app too ---

    /** Pushes the captured WAV to the relay, tagged with the press's id (worker side). */
    static void setAudio(IBinder relay, String id, byte[] wav) throws Exception {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeString(id);
            data.writeByteArray(wav);
            relay.transact(TXN_SET_AUDIO, data, reply, 0);
            reply.readException();
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    /** Pulls the WAV the worker left for this press's id, or null when none with that id is ready (app side). */
    static byte[] getAudio(IBinder relay, String id) throws Exception {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeString(id);
            relay.transact(TXN_GET_AUDIO, data, reply, 0);
            reply.readException();
            return reply.createByteArray();
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    static void addService(String name, IBinder service) throws Exception {
        Class<?> sm = Class.forName("android.os.ServiceManager");
        Method m = sm.getMethod("addService", String.class, IBinder.class);
        m.invoke(null, name, service);
    }

    static IBinder getService(String name) {
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            Method m = sm.getMethod("getService", String.class);
            return (IBinder) m.invoke(null, name);
        } catch (Throwable t) {
            Log.e(TAG, "getService " + name + " failed", t);
            return null;
        }
    }

    /** Opens all hidden APIs for the app process (ServiceManager is restricted on Android 9). */
    static void exemptHiddenApi() {
        try {
            Method forName = Class.class.getDeclaredMethod("forName", String.class);
            Method getDeclaredMethod =
                    Class.class.getDeclaredMethod("getDeclaredMethod", String.class, Class[].class);
            Class<?> vm = (Class<?>) forName.invoke(null, "dalvik.system.VMRuntime");
            Method getRuntime = (Method) getDeclaredMethod.invoke(vm, "getRuntime", (Object) null);
            Method setExemptions = (Method) getDeclaredMethod.invoke(
                    vm, "setHiddenApiExemptions", new Class[]{String[].class});
            Object runtime = getRuntime.invoke(null);
            setExemptions.invoke(runtime, new Object[]{new String[]{"L"}});
        } catch (Throwable ignored) {
            // app_process is normally unrestricted; the app path still needs it.
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
