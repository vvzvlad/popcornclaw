package com.omarchytv.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

/**
 * The focus highlight, drawn like Hyprland's active-window border in Omarchy: a crisp 2dp border in a
 * 45-degree blue-to-purple gradient, with a faint inner tint. One instance floats over the grid and
 * springs between tiles (the original launcher's "glass" behaviour, restyled).
 */
public class FocusFrameView extends View {

    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float density;
    private float radius;

    public FocusFrameView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        radius = 0f;   // Hyprland rounding = 0
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(Math.max(1, LauncherConfig.get(context).borderDp) * density);
        halo.setStyle(Paint.Style.STROKE);
        tint.setColor(Theme.m(0x147AA2F7));
    }

    private boolean moving;

    /** Move mode: orange-to-red border like a Hyprland window being dragged. */
    void setMoving(boolean m) {
        moving = m;
        onSizeChanged(getWidth(), getHeight(), getWidth(), getHeight());
        invalidate();
    }

    void setRadius(float px) {
        radius = px;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        LauncherConfig cfg = LauncherConfig.get(getContext());
        border.setShader(new LinearGradient(0, 0, Math.max(1, w), Math.max(1, h),
                // Hyprland col.active_border (themed: hyprland_active_border or accent; gradients at 45deg).
                moving ? Theme.m(0xFFFF9E64) : cfg.theme.activeBorder, moving ? Theme.m(0xFFF7768E) : cfg.theme.activeBorder2,
                Shader.TileMode.CLAMP));
        tint.setColor((cfg.accent & 0x00FFFFFF) | 0x14000000);
        halo.setColor(cfg.accent);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float inset = 6f * density;   // room for the halo
        rect.set(inset, inset, getWidth() - inset, getHeight() - inset);
        // Exactly a Hyprland active window border: no glow (Omarchy decoration.shadow.enabled = false), no fill.
        float half = border.getStrokeWidth() / 2f;
        canvas.drawRoundRect(rect.left + half, rect.top + half, rect.right - half, rect.bottom - half, radius, radius, border);
    }
}
