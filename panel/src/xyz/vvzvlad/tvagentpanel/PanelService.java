package xyz.vvzvlad.tvagentpanel;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.text.Layout;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Foreground service that follows the newest PicoClaw session and draws it as a console
 * transcript over the home screen while a turn runs and for a few minutes after it.
 */
public final class PanelService extends Service {
    private static final String TAG = "TvAgentPanel";
    private static final File SESSIONS = new File("/data/local/tvagent/workspace/sessions");
    private static final long POLL_MS = 1000;
    private static final long LINGER_MS = 5 * 60 * 1000;
    // PicoClaw writes nothing when a turn dies (model error after retries, gateway killed), so a
    // turn with no new step for this long is treated as over; longer than any normal step.
    // Must stay above LINGER_MS: a stale turn is then already hidden, so "ответ в" never shows
    // for a turn without an answer.
    private static final long STALE_MS = 10 * 60 * 1000;
    // The first query has no previous poll to continue from: look back far enough to find
    // the last app switch.
    private static final long FIRST_LOOKBACK_MS = 7L * 24 * 60 * 60 * 1000;
    // Usage events are recorded asynchronously; re-reading a few seconds back catches late ones.
    private static final long EVENT_OVERLAP_MS = 10_000;
    private static final String CHANNEL = "panel";
    private static final float WIDTH_SHARE = 0.6f;
    private static final int PAD_DP = 12;
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    private final Transcript transcript = new Transcript();
    private Handler main;
    private Handler worker;
    private HandlerThread thread;
    private WindowManager wm;
    private View root;
    private TextView body;
    private TextView status;
    private boolean attached;
    private boolean overlayFailed;
    private boolean destroyed;
    private String foreground;
    private long eventsQueriedTo;
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
            worker.postDelayed(this, POLL_MS);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        main = new Handler(Looper.getMainLooper());
        wm = getSystemService(WindowManager.class);
        root = buildViews();
        // Lines are cut to the panel's width in monospace columns, the way a console does.
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        int textWidth = Math.round(dm.widthPixels * WIDTH_SHARE) - 2 * dp(PAD_DP);
        transcript.setColumns((int) (textWidth / body.getPaint().measureText("0")));
        thread = new HandlerThread("panel-poll");
        thread.start();
        worker = new Handler(thread.getLooper());
        worker.post(tick);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Every startForegroundService() call must be answered with startForeground().
        startForeground(1, notification());
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        // A show() the poll thread posted after the cleanup below must not add the window back.
        destroyed = true;
        worker.removeCallbacksAndMessages(null);
        thread.quitSafely();
        main.removeCallbacksAndMessages(null);
        if (attached) {
            wm.removeView(root);
            attached = false;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /** Runs on the worker thread; the views are touched only through the main handler. */
    private void poll() {
        long now = System.currentTimeMillis();
        updateForeground(now);
        boolean home = foreground != null && homePackages().contains(foreground);
        final CharSequence text = transcript.poll(newestSession()) ? transcript.render() : null;
        final boolean working = transcript.inProgress() && now - transcript.lastEntryMs() < STALE_MS;
        final boolean visible = home && !transcript.isEmpty()
                && (working || now - transcript.lastEntryMs() < LINGER_MS);
        final String line;
        final int color;
        if (transcript.isEmpty()) {
            line = "";
            color = Transcript.DIM;
        } else if (working) {
            long step = Math.max(0, (now - transcript.lastEntryMs()) / 1000);
            String running = transcript.lastUserMs() > 0 ? clock(now - transcript.lastUserMs()) + " · " : "";
            line = "✻ Работает… " + running + "последний шаг " + step + " с назад";
            color = Transcript.ACCENT;
        } else {
            String at = HH_MM.format(Instant.ofEpochMilli(transcript.lastEntryMs()).atZone(ZoneId.systemDefault()));
            line = "ждёт сообщения · ответ в " + at;
            color = Transcript.DIM;
        }
        main.post(() -> show(visible, text, line, color));
    }

    private void show(boolean visible, CharSequence text, String line, int color) {
        if (destroyed) {
            return;
        }
        if (text != null) {
            body.setText(text);
        }
        status.setText(line);
        status.setTextColor(color);
        if (visible && !attached) {
            try {
                wm.addView(root, layoutParams());
                attached = true;
                overlayFailed = false;
            } catch (RuntimeException e) {
                // Android 9 keeps a view whose addView failed registered; drop it so a later
                // addView (e.g. once the app-op is granted) does not fail as "already added".
                try {
                    wm.removeViewImmediate(root);
                } catch (RuntimeException ignored) {
                    // Not registered after all.
                }
                // Without the SYSTEM_ALERT_WINDOW app-op this fails every poll; log it once.
                if (!overlayFailed) {
                    Log.w(TAG, "cannot add the overlay window", e);
                    overlayFailed = true;
                }
            }
        } else if (!visible && attached) {
            wm.removeView(root);
            attached = false;
        }
    }

    /** Tracks the app on screen from the usage events since the previous poll. */
    private void updateForeground(long now) {
        UsageStatsManager usm = getSystemService(UsageStatsManager.class);
        long from = eventsQueriedTo == 0 ? now - FIRST_LOOKBACK_MS : eventsQueriedTo - EVENT_OVERLAP_MS;
        UsageEvents events = usm.queryEvents(from, now);
        UsageEvents.Event e = new UsageEvents.Event();
        // Replaying the overlap in order ends in the same state, so seeing an event twice is harmless.
        while (events.hasNextEvent()) {
            events.getNextEvent(e);
            if (e.getEventType() == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                foreground = e.getPackageName();
            } else if (e.getEventType() == UsageEvents.Event.MOVE_TO_BACKGROUND
                    && e.getPackageName().equals(foreground)) {
                foreground = null;
            }
        }
        eventsQueriedTo = now;
    }

    /** The default home app; any home app when no default is set and the chooser resolves instead. */
    private Set<String> homePackages() {
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        PackageManager pm = getPackageManager();
        Set<String> out = new HashSet<>();
        ResolveInfo def = pm.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
        if (def != null && def.activityInfo != null && !"android".equals(def.activityInfo.packageName)) {
            out.add(def.activityInfo.packageName);
            return out;
        }
        for (ResolveInfo r : pm.queryIntentActivities(home, 0)) {
            out.add(r.activityInfo.packageName);
        }
        return out;
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

    private View buildViews() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xE6000000);
        bg.setCornerRadius(dp(6));
        panel.setBackground(bg);
        int pad = dp(PAD_DP);
        panel.setPadding(pad, pad, pad, pad);

        body = console();
        // Short transcripts sit at the bottom, right above the status line.
        body.setGravity(Gravity.BOTTOM);
        ScrollView scroll = new TailScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.addView(body, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        panel.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        status = console();
        status.setSingleLine(true);
        status.setEllipsize(TextUtils.TruncateAt.END);
        status.setPadding(0, dp(6), 0, 0);
        panel.addView(status, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return panel;
    }

    private TextView console() {
        TextView v = new TextView(this);
        v.setTypeface(Typeface.MONOSPACE);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        v.setTextColor(Transcript.WHITE);
        // A console wraps where the line runs out: no hyphens, no paragraph balancing.
        v.setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE);
        v.setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE);
        return v;
    }

    /**
     * Right 60% of the screen, between the launcher's top bar and its bottom app row
     * (OmarchyTV at 1920x1080: bar ends at y≈52, app row starts at y≈820); never takes focus or touches.
     */
    private WindowManager.LayoutParams layoutParams() {
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        int margin = dp(16);
        int top = Math.round(dm.heightPixels * 0.065f);
        int bottom = Math.round(dm.heightPixels * 0.25f);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                Math.round(dm.widthPixels * WIDTH_SHARE),
                dm.heightPixels - top - bottom,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.RIGHT;
        lp.x = margin;
        lp.y = top;
        lp.setTitle("TvAgentPanel");
        return lp;
    }

    private Notification notification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Agent panel", NotificationManager.IMPORTANCE_MIN));
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("TV agent panel")
                .build();
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
