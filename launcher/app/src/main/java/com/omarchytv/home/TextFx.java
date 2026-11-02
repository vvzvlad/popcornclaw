package com.omarchytv.home;

import android.os.SystemClock;
import android.widget.TextView;

import java.util.Random;

/**
 * Cheap terminal text effects in the spirit of Omarchy's screensaver (terminaltexteffects):
 * {@link #decrypt} scrambles a label and resolves it left to right, {@link #type} types it out with a
 * block cursor. Pure setText() at about 30 fps for a few hundred milliseconds, posted on the view itself,
 * so a new effect on the same view cancels the old one. No bitmaps, no blur, no layers.
 */
final class TextFx {

    static final String GLYPHS = "!<>-_\\/[]{}=+*^?#$%&01";
    private static final long FRAME_MS = 33;
    private static final Random RANDOM = new Random();

    private TextFx() {
    }

    /** Scramble then resolve: each character settles at a staggered time inside durationMs. */
    static void decrypt(TextView v, CharSequence target, long durationMs) {
        start(v, target, durationMs, false);
    }

    /** Typewriter: characters appear one by one followed by a block cursor. */
    static void type(TextView v, CharSequence target, long durationMs) {
        start(v, target, durationMs, true);
    }

    private static void start(TextView v, CharSequence target, long durationMs, boolean typewriter) {
        final String text = target == null ? "" : target.toString();
        Object old = v.getTag(R.id.textfx);
        if (old instanceof Runnable) v.removeCallbacks((Runnable) old);
        if (text.isEmpty() || durationMs <= 0) {
            v.setText(text);
            v.setTag(R.id.textfx, null);
            return;
        }
        final long t0 = SystemClock.uptimeMillis();
        final int n = text.length();
        // Per-character settle times for decrypt: left-to-right bias plus jitter.
        final float[] settle = new float[n];
        for (int i = 0; i < n; i++) settle[i] = Math.min(1f, 0.35f * i / Math.max(1, n - 1) + 0.65f * RANDOM.nextFloat() * (0.4f + 0.6f * i / Math.max(1, n)));
        Runnable frame = new Runnable() {
            @Override
            public void run() {
                float p = Math.min(1f, (SystemClock.uptimeMillis() - t0) / (float) durationMs);
                if (p >= 1f) {
                    v.setText(text);
                    v.setTag(R.id.textfx, null);
                    return;
                }
                StringBuilder sb = new StringBuilder(n + 1);
                if (typewriter) {
                    int shown = (int) (p * n);
                    sb.append(text, 0, shown).append('\u2588');
                } else {
                    for (int i = 0; i < n; i++) {
                        char c = text.charAt(i);
                        sb.append(c == ' ' || p >= settle[i] ? c : GLYPHS.charAt(RANDOM.nextInt(GLYPHS.length())));
                    }
                }
                v.setText(sb);
                v.postDelayed(this, FRAME_MS);
            }
        };
        v.setTag(R.id.textfx, frame);
        frame.run();
    }

    /** Stops a running effect and leaves the final text. */
    static void finish(TextView v, CharSequence target) {
        Object old = v.getTag(R.id.textfx);
        if (old instanceof Runnable) v.removeCallbacks((Runnable) old);
        v.setTag(R.id.textfx, null);
        v.setText(target);
    }
}
