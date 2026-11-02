package com.omarchytv.home;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Cast image viewer and text card (PROTOCOL.md §6 Cast): full screen on the theme background, title in the theme
 * font. Text is plain text only (no links, no HTML). Reports "playing" once shown, then "ended" (nothing more to
 * fetch, so the PC can close its temporary server); Back or "stop" closes it ("stopped").
 */
public final class CastViewActivity extends Activity implements CastRouter.Surface {

    private static final int IMAGE_MAX = 32 << 20;

    private String id;
    private boolean replaced, done;
    private ScrollView scroll;
    private final Handler h = new Handler(Looper.getMainLooper());
    private volatile Thread loader;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LauncherConfig.wrap(base));
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Theme t = Theme.cur();
        id = getIntent().getStringExtra(CastRouter.EXTRA_ID);
        if (id == null) {
            finish();
            return;
        }
        String kind = getIntent().getStringExtra(CastRouter.EXTRA_KIND);
        String title = getIntent().getStringExtra(CastRouter.EXTRA_TITLE);
        String from = getIntent().getStringExtra(CastRouter.EXTRA_FROM);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000 | (t.bg & 0xFFFFFF));
        int side = CastUi.dp(this, 56), top = CastUi.dp(this, 40);

        // "> title" prompt header and the sender, like the launcher's section headers.
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        String name = title == null || title.isEmpty() ? getString("text".equals(kind) ? R.string.cast_text : R.string.cast_image) : title;
        TextView prompt = CastUi.label(this, "> ", 18, t.green);
        TextView nameV = CastUi.label(this, name, 18, t.fgBright);
        nameV.setSingleLine(true);
        nameV.setEllipsize(android.text.TextUtils.TruncateAt.END);
        head.addView(prompt);
        head.addView(nameV, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (from != null && !from.isEmpty()) head.addView(CastUi.label(this, getString(R.string.cast_from, from), 13, t.fgDim));
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP);
        hp.setMargins(side, top, side, 0);

        int below = top + CastUi.dp(this, 40);
        if ("text".equals(kind)) {
            String text = getIntent().getStringExtra(CastRouter.EXTRA_TEXT);
            TextView body = CastUi.label(this, text == null ? "" : text, 26, t.fg);   // setText(String): plain text
            body.setAutoLinkMask(0);
            body.setLinksClickable(false);
            body.setTextIsSelectable(false);
            body.setLineSpacing(0, 1.25f);
            body.setMovementMethod(null);
            int p = CastUi.dp(this, 24);
            body.setPadding(p, p, p, p);
            scroll = new ScrollView(this);
            scroll.setBackground(CastUi.pane(this));
            scroll.setFocusable(true);
            scroll.setVerticalScrollBarEnabled(true);
            scroll.addView(body, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            sp.setMargins(side, below, side, top);
            root.addView(scroll, sp);
            root.addView(head, hp);
            setContentView(root);
            scroll.requestFocus();
            CastRouter.attach(this);
            shown();
            return;
        }

        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.FIT_CENTER);
        FrameLayout.LayoutParams ip = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        ip.setMargins(side, below, side, top);
        root.addView(img, ip);
        TextView wait = CastUi.label(this, "…", 18, t.fgDim);
        root.addView(wait, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        root.addView(head, hp);
        setContentView(root);
        CastRouter.attach(this);
        final String sha = getIntent().getStringExtra(CastRouter.EXTRA_FILE), url = getIntent().getStringExtra(CastRouter.EXTRA_URL);
        final int maxW = getResources().getDisplayMetrics().widthPixels, maxH = getResources().getDisplayMetrics().heightPixels;
        loader = new Thread(() -> {
            Bitmap bmp = null;
            String err = null;
            try {
                byte[] data = sha != null ? read(new File(CastRouter.dir(this), sha)) : fetch(url);
                bmp = decode(data, maxW, maxH);
                if (bmp == null) err = "not an image";
            } catch (IOException | RuntimeException e) {
                err = "cannot open image";   // never the URL: it carries the cast token
            }
            final Bitmap fb = bmp;
            final String fe = err;
            h.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (fb != null) {
                    wait.setVisibility(View.GONE);
                    img.setImageBitmap(fb);
                    shown();
                } else {
                    wait.setText("✗ " + fe);
                    wait.setTextColor(Theme.cur().red);
                    if (!replaced && !done) OmarchyLink.castState(id, "error", -1, -1, fe);
                    done = true;
                }
            });
        }, "otv-cast-image");
        loader.setPriority(Thread.MIN_PRIORITY);
        loader.start();
    }

    private void shown() {
        if (replaced || done) return;
        OmarchyLink.castState(id, "playing", -1, -1, null);
        OmarchyLink.castState(id, "ended", -1, -1, null);
    }

    private static byte[] read(File f) throws IOException {
        if (f.length() > IMAGE_MAX) throw new IOException("too large");
        try (InputStream in = new FileInputStream(f)) {
            return drain(in);
        }
    }

    private static byte[] fetch(String url) throws IOException {
        if (url == null) throw new IOException("no url");
        // Cleartext is permitted app-wide for LAN web apps (network_security_config.xml): http:// only to the LAN here too.
        Uri u = Uri.parse(url);
        boolean https = "https".equals(u.getScheme());
        if (!https && !LanHttpDataSource.allowed(u)) throw new IOException("http:// only on the LAN");
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(15_000);
        c.setInstanceFollowRedirects(https);   // never follows a redirect to another scheme; a LAN http:// URL none at all
        c.setRequestProperty("User-Agent", "OmarchyTV");
        try (InputStream in = c.getInputStream()) {
            if (c.getContentLengthLong() > IMAGE_MAX) throw new IOException("too large");
            return drain(in);
        } finally {
            c.disconnect();
        }
    }

    private static byte[] drain(InputStream in) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream(256 * 1024);
        byte[] b = new byte[64 * 1024];
        int n;
        while ((n = in.read(b)) > 0) {
            o.write(b, 0, n);
            if (o.size() > IMAGE_MAX) throw new IOException("too large");
        }
        return o.toByteArray();
    }

    /** Decodes at most the screen size (power-of-two subsampling), so a 50 MP photo cannot exhaust memory. */
    static Bitmap decode(byte[] data, int maxW, int maxH) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, o);
        if (o.outWidth <= 0 || o.outHeight <= 0) return null;
        int s = 1;
        while (o.outWidth / (s * 2) >= maxW || o.outHeight / (s * 2) >= maxH) s *= 2;
        BitmapFactory.Options d = new BitmapFactory.Options();
        d.inSampleSize = s;
        return BitmapFactory.decodeByteArray(data, 0, data.length, d);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getAction() == KeyEvent.ACTION_DOWN && scroll != null) {
            int step = scroll.getHeight() / 3;
            switch (e.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    scroll.smoothScrollBy(0, step);
                    return true;
                case KeyEvent.KEYCODE_DPAD_UP:
                    scroll.smoothScrollBy(0, -step);
                    return true;
                default:
            }
        }
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getKeyCode() == KeyEvent.KEYCODE_MEDIA_STOP) {
            onBackPressed();
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    @Override
    public void onBackPressed() {
        close(true);
    }

    private void close(boolean report) {
        if (report && !done && !replaced) OmarchyLink.castState(id, "stopped", -1, -1, null);
        done = true;
        Thread l = loader;
        if (l != null) l.interrupt();
        finish();
    }

    @Override
    public String castId() {
        return id;
    }

    @Override
    public void castControl(String action, double position) {
        if ("stop".equals(action)) close(false);   // the server already reported "stopped"
    }

    @Override
    public void castReplaced() {
        replaced = true;
        close(false);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (!isFinishing()) close(true);
    }

    @Override
    protected void onDestroy() {
        if (!done && !replaced) OmarchyLink.castState(id, "stopped", -1, -1, null);
        CastRouter.detach(this);
        super.onDestroy();
    }
}
