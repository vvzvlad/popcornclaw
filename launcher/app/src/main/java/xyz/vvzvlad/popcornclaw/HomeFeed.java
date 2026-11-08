package xyz.vvzvlad.popcornclaw;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.text.Layout;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * The agent's console feed for the launcher's home screen: follows the newest PicoClaw session and draws it
 * as a console transcript with a status line under it. It polls once a second while its window is on screen
 * and calls the given callback whenever {@link #shown()} changes: true whenever there is a conversation.
 * The home screen decides where it goes and what it hides meanwhile.
 */
public final class HomeFeed extends LinearLayout {
    private static final String TAG = "PopcornClawFeed";
    private static final File SESSIONS = new File("/data/local/popcornclaw/workspace/sessions");
    private static final long POLL_MS = 1000;
    // PicoClaw writes nothing when a turn dies (model error after retries, gateway killed), so a
    // turn with no new step for this long is treated as broken; longer than any normal step.
    private static final long STALE_MS = 10 * 60 * 1000;
    private static final int TEXT_SP = 9;

    private final Runnable onShownChanged;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final TextView body;
    private final TextView status;
    // Main thread only.
    private boolean shown;
    private HandlerThread thread;
    // Written on the main thread, read by the worker.
    private volatile Handler worker;
    private volatile boolean onScreen;
    private volatile int columns;
    // Worker thread only.
    private final Transcript transcript = new Transcript();
    private final SimpleDateFormat hhmm = new SimpleDateFormat("HH:mm", Locale.ROOT);
    private Palette rendered;
    private String lastError;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            try {
                poll();
                lastError = null;
            } catch (RuntimeException e) {
                // Logged once per distinct failure, not every second.
                String msg = String.valueOf(e);
                if (!msg.equals(lastError)) {
                    Log.w(TAG, "poll failed", e);
                    lastError = msg;
                }
            }
            // Null once detached; a handler whose thread has quit drops the message.
            Handler w = worker;
            if (onScreen && w != null) {
                w.postDelayed(this, POLL_MS);
            }
        }
    };

    // Runs on the worker: all scheduling happens there, so there is never more than one tick chain.
    private final Runnable restart = new Runnable() {
        @Override
        public void run() {
            Handler w = worker;
            if (w == null) {
                return;
            }
            w.removeCallbacks(tick);
            if (onScreen) {
                tick.run();
            }
        }
    };

    public HomeFeed(Context c, Runnable onShownChanged) {
        super(c);
        this.onShownChanged = onShownChanged;
        setOrientation(VERTICAL);
        body = console(c);
        // Short transcripts sit at the bottom, right above the status line.
        body.setGravity(Gravity.BOTTOM);
        ScrollView scroll = new TailScrollView(c);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        // Read-only: the D-pad stays with the launcher's tiles.
        scroll.setFocusable(false);
        scroll.addView(body, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        addView(scroll, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        status = console(c);
        status.setSingleLine(true);
        status.setEllipsize(TextUtils.TruncateAt.END);
        status.setPadding(0, dp(6), 0, 0);
        addView(status, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /** True whenever there is a conversation to show: the console stays on the home screen. */
    public boolean shown() {
        return shown;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        thread = new HandlerThread("popcornclaw-feed");
        thread.start();
        worker = new Handler(thread.getLooper());
        worker.post(restart);
    }

    @Override
    protected void onDetachedFromWindow() {
        onScreen = false;
        thread.quitSafely();
        thread = null;
        worker = null;
        main.removeCallbacksAndMessages(null);
        super.onDetachedFromWindow();
    }

    /** The launcher on screen or not: polls only while it is. */
    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        onScreen = visibility == VISIBLE;
        if (worker != null) {
            worker.post(restart);
        }
    }

    /** Lines are cut to the feed's width in monospace columns, the way a console does. */
    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        columns = (int) ((w - getPaddingLeft() - getPaddingRight()) / body.getPaint().measureText("0"));
    }

    /** Runs on the worker thread; the views are touched only through the main handler. */
    private void poll() {
        int cols = columns;
        if (cols <= 0) {
            // Not laid out yet: nothing can be cut to width.
            return;
        }
        long now = System.currentTimeMillis();
        transcript.setColumns(cols);
        Palette p = Palette.get();
        boolean changed = transcript.poll(newestSession());
        final CharSequence text = changed || p != rendered ? transcript.render(p) : null;
        rendered = p;
        final boolean working = transcript.inProgress() && now - transcript.lastEntryMs() < STALE_MS;
        final boolean visible = !transcript.isEmpty();
        final String line;
        final int color;
        if (transcript.isEmpty()) {
            line = "";
            color = p.dim;
        } else if (working) {
            long step = Math.max(0, (now - transcript.lastEntryMs()) / 1000);
            String running = transcript.lastUserMs() > 0 ? clock(now - transcript.lastUserMs()) + " · " : "";
            line = "✻ Working… " + running + "last step " + step + " s ago";
            color = p.accent;
        } else if (transcript.inProgress()) {
            line = "turn broke off · last step at " + hhmm.format(new Date(transcript.lastEntryMs()));
            color = p.dim;
        } else {
            line = "waiting for a message · replied at " + hhmm.format(new Date(transcript.lastEntryMs()));
            color = p.dim;
        }
        main.post(() -> show(visible, text, line, color));
    }

    private void show(boolean visible, CharSequence text, String line, int color) {
        if (worker == null) {
            // Detached after the worker posted this.
            return;
        }
        if (text != null) {
            body.setText(text);
        }
        status.setText(line);
        status.setTextColor(color);
        if (visible != shown) {
            shown = visible;
            onShownChanged.run();
        }
    }

    private static File newestSession() {
        File[] files = SESSIONS.listFiles((dir, name) -> name.endsWith(".jsonl"));
        if (files == null) {
            return null;
        }
        File best = null;
        for (File f : files) {
            if (best == null || f.lastModified() > best.lastModified()) {
                best = f;
            }
        }
        return best;
    }

    private static String clock(long ms) {
        long s = Math.max(0, ms / 1000);
        return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }

    // Lint wants LineBreaker's constants (API 29) in setBreakStrategy; Layout's are the same values from API 23.
    @SuppressLint("WrongConstant")
    private static TextView console(Context c) {
        TextView v = new TextView(c);
        // DroidSansMono: the launcher's own font has no Cyrillic, and the lines are cut in columns.
        v.setTypeface(Typeface.MONOSPACE);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, TEXT_SP);
        // A console wraps where the line runs out: no hyphens, no paragraph balancing.
        v.setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE);
        v.setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE);
        return v;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /** ScrollView that stays scrolled to the end, so the newest line is always on screen. */
    private static final class TailScrollView extends ScrollView {
        TailScrollView(Context c) {
            super(c);
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            super.onLayout(changed, l, t, r, b);
            if (getChildCount() > 0) {
                // ScrollView clamps this to the last screenful.
                scrollTo(0, getChildAt(0).getHeight());
            }
        }
    }
}
