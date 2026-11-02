package xyz.vvzvlad.popcornclaw;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.system.ErrnoException;
import android.system.Os;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Shows each new user message of any PicoClaw session (Telegram, the command line) in a window over
 * everything, video included, at the top of the screen for {@link #SHOW_MS}. Lines already in the session
 * files when it starts do not count. Needs the SYSTEM_ALERT_WINDOW app-op (granted by `make app`).
 */
final class CommandPopup {
    private static final String TAG = "PopcornClawPopup";
    private static final File SESSIONS = new File("/data/local/popcornclaw/workspace/sessions");
    private static final long POLL_MS = 1000;
    private static final long SHOW_MS = 10_000;
    private static final int TEXT_SP = 16;
    private static final int MAX_LINES = 6;
    private static final float WIDTH_SHARE = 0.6f;

    /** Where reading of one session file stands. */
    static final class Seen {
        boolean known;
        // The file's inode: a rewrite by temp file and rename changes it.
        long identity;
        // End of the last complete line read.
        long offset;
        // Newest created_at (epoch ms) among the lines already read.
        long seenMs;
    }

    // A rewritten file is read back from this far; the command PicoClaw appends after a rewrite is at its end.
    private static final int TAIL = 256 * 1024;

    private final Context context;
    private final WindowManager wm;
    private final Handler main = new Handler(Looper.getMainLooper());
    private HandlerThread thread;
    private Handler worker;
    // Worker thread only.
    private final Map<String, Seen> seen = new HashMap<>();
    private String lastError;
    // Main thread only.
    private TextView view;
    private boolean attached;
    private boolean overlayFailed;
    private boolean stopped;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            try {
                scan(false);
                lastError = null;
            } catch (RuntimeException e) {
                // Logged once per distinct failure, not every second.
                String msg = String.valueOf(e);
                if (!msg.equals(lastError)) {
                    Log.w(TAG, "scan failed", e);
                    lastError = msg;
                }
            }
            worker.postDelayed(this, POLL_MS);
        }
    };

    private final Runnable hide = this::hide;

    CommandPopup(Context context) {
        this.context = context;
        wm = context.getSystemService(WindowManager.class);
    }

    void start() {
        thread = new HandlerThread("popcornclaw-popup");
        thread.start();
        worker = new Handler(thread.getLooper());
        worker.post(() -> {
            // What is in the files now is history, not a new command.
            try {
                scan(true);
            } catch (RuntimeException e) {
                Log.w(TAG, "first scan failed", e);
            }
            worker.postDelayed(tick, POLL_MS);
        });
    }

    /** On the main thread (the service's onDestroy); a show() the worker already posted is dropped. */
    void stop() {
        thread.quit();
        stopped = true;
        hide();
    }

    /** Runs on the worker; shows the last new user message found in this pass. */
    private void scan(boolean firstPass) {
        File[] files = SESSIONS.listFiles((dir, name) -> name.endsWith(".jsonl"));
        if (files == null) {
            return;
        }
        String last = null;
        for (File f : files) {
            if (!f.isFile()) {
                continue;
            }
            Seen s = seen.computeIfAbsent(f.getName(), k -> new Seen());
            try {
                List<String> commands = advance(f, inode(f), s, firstPass);
                if (!commands.isEmpty()) {
                    last = commands.get(commands.size() - 1);
                }
            } catch (IOException e) {
                // The file changed under the read; the state moves only after a whole read, so the next pass retries.
            }
        }
        if (last != null) {
            final String text = last;
            main.post(() -> show(text));
        }
    }

    /**
     * One pass over one session file: advances its state and returns the user messages that are new since the
     * previous pass, oldest first. A file not known yet is history on the service's first pass and new from its
     * first line after that. identity is the file's inode. Only complete lines are read.
     */
    static List<String> advance(File f, long identity, Seen s, boolean firstPass) throws IOException {
        List<String> out = new ArrayList<>();
        long len = f.length();
        if (!s.known) {
            if (firstPass) {
                // History. The baseline time is the file's own newest line, not the box clock (unsynced at boot).
                readTail(f, len, s, null);
                s.identity = identity;
                s.known = true;
                return out;
            }
            // A session that appears later is new from its first line.
            s.identity = identity;
            s.known = true;
        } else if (s.identity != identity || len < s.offset || len - s.offset > Transcript.MAX_CHUNK
                || !Transcript.endsLineAt(f, s.offset)) {
            // Rewritten. PicoClaw compacts an overlong history (temp file and rename) at the start of a turn and
            // appends the new command milliseconds later, usually within one poll. Offsets mean nothing now, but
            // the rewrite keeps every line's created_at: a user line newer than all lines read before is new.
            readTail(f, len, s, out);
            s.identity = identity;
            return out;
        }
        if (len == s.offset) {
            return out;
        }
        byte[] buf = Transcript.read(f, s.offset, (int) (len - s.offset));
        // A line still being written is left for the next pass.
        int end = Transcript.lastIndexOf(buf) + 1;
        // Appended after what was read, so new whatever its time says (the clock may have been set back).
        for (JSONObject m : Transcript.parse(buf, 0, end)) {
            s.seenMs = Math.max(s.seenMs, createdAt(m));
            addCommand(m, out);
        }
        s.offset += end;
        return out;
    }

    /**
     * Reads the file's last {@link #TAIL} bytes and moves the state to their end; with out, collects the user
     * messages newer than every line read before. The state changes only after the read succeeded.
     */
    private static void readTail(File f, long len, Seen s, List<String> out) throws IOException {
        long start = Math.max(0, len - TAIL);
        byte[] buf = Transcript.read(f, start, (int) (len - start));
        // A tail that starts mid-file starts mid-line; a line still being written is left for the next pass.
        int from = start > 0 ? Transcript.indexOf(buf, 0) + 1 : 0;
        int end = Transcript.lastIndexOf(buf) + 1;
        long newest = s.seenMs;
        for (JSONObject m : Transcript.parse(buf, from, end)) {
            long ts = createdAt(m);
            if (out != null && ts > s.seenMs) {
                addCommand(m, out);
            }
            newest = Math.max(newest, ts);
        }
        s.seenMs = newest;
        s.offset = start + end;
    }

    private static long createdAt(JSONObject m) {
        return Transcript.parseTime(Transcript.str(m, "created_at"));
    }

    /** A user message with text is a command; voice comes as "[voice: …]" and is shown as it came. */
    private static void addCommand(JSONObject m, List<String> out) {
        if ("user".equals(Transcript.str(m, "role"))) {
            String text = Transcript.clean(Transcript.text(m, "content"));
            if (!text.isEmpty()) {
                out.add(text);
            }
        }
    }

    private static long inode(File f) {
        try {
            return Os.stat(f.getPath()).st_ino;
        } catch (ErrnoException e) {
            return 0;
        }
    }

    private void show(String text) {
        if (stopped) {
            return;
        }
        if (view == null) {
            view = new TextView(context);
            view.setTypeface(Typeface.MONOSPACE);
            view.setTextSize(TypedValue.COMPLEX_UNIT_SP, TEXT_SP);
            view.setMaxLines(MAX_LINES);
            view.setEllipsize(TextUtils.TruncateAt.END);
            int pad = dp(14);
            view.setPadding(pad, dp(10), pad, dp(10));
        }
        Palette p = Palette.get();
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(p.bg);
        bg.setStroke(dp(2), p.accent);
        view.setBackground(bg);
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        view.setMaxWidth(Math.round(dm.widthPixels * WIDTH_SHARE));
        // The prompt mark the feed puts before a user message; the text itself as it came (voice: "[voice: …]").
        SpannableStringBuilder sb = new SpannableStringBuilder("› ");
        sb.setSpan(new ForegroundColorSpan(p.accent), 0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new StyleSpan(Typeface.BOLD), 0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        int start = sb.length();
        sb.append(text);
        sb.setSpan(new ForegroundColorSpan(p.text), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        view.setText(sb);
        if (!attached) {
            try {
                wm.addView(view, layoutParams(dm));
                attached = true;
                overlayFailed = false;
            } catch (RuntimeException e) {
                // Android 9 keeps a view whose addView failed registered; drop it so a later
                // addView (e.g. once the app-op is granted) does not fail as "already added".
                try {
                    wm.removeViewImmediate(view);
                } catch (RuntimeException ignored) {
                    // Not registered after all.
                }
                // Without the SYSTEM_ALERT_WINDOW app-op this fails on every message; log it once.
                if (!overlayFailed) {
                    Log.w(TAG, "cannot add the overlay window", e);
                    overlayFailed = true;
                }
                return;
            }
        }
        // A newer message keeps the window up for its own full time.
        main.removeCallbacks(hide);
        main.postDelayed(hide, SHOW_MS);
    }

    private void hide() {
        main.removeCallbacks(hide);
        if (attached) {
            wm.removeView(view);
            attached = false;
        }
    }

    /** Top centre, over everything; never takes focus or touches. */
    @SuppressWarnings("deprecation")
    private WindowManager.LayoutParams layoutParams(DisplayMetrics dm) {
        // TYPE_APPLICATION_OVERLAY exists from Android 8; before it TYPE_PHONE drew over other apps.
        int type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.y = Math.round(dm.heightPixels * 0.04f);
        lp.setTitle("PopcornClawCommand");
        return lp;
    }

    private int dp(float v) {
        return Math.round(v * context.getResources().getDisplayMetrics().density);
    }
}
