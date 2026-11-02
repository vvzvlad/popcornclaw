package com.omarchytv.home;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/**
 * The "> omarchy" section between the apps and "> tv": a prompt header and one app-sized tile per shortcut
 * the paired PC pushed (link.shortcuts). Hidden when there are none or the row is switched off.
 * "app"/"view" shortcuts work without the PC; "pc" ones need it connected and say "not connected" otherwise.
 */
final class ShortcutAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    interface Listener {
        void onShortcutFocused(int globalPos, View card);

        void onShortcutClick(LauncherConfig.Shortcut s);

        /** Up/Down from tile {@code index}; return true when handled. */
        boolean onShortcutVertical(int index, int keyCode);
    }

    static final int TYPE_PROMPT = 1, TYPE_TILE = 2;

    private final Context ctx;
    private final Listener listener;
    private final List<LauncherConfig.Shortcut> items = new ArrayList<>();
    private int offset;
    private View promptCursor;
    private boolean focusedSection;

    ShortcutAdapter(Context ctx, Listener listener) {
        this.ctx = ctx;
        this.listener = listener;
        setHasStableIds(true);
    }

    void set(List<LauncherConfig.Shortcut> list) {
        items.clear();
        items.addAll(list);
        notifyDataSetChanged();
    }

    void setOffset(int appCount) {
        offset = appCount;
    }

    /** Number of shortcut tiles (0 = section hidden). */
    int tiles() {
        return items.size();
    }

    @Override
    public int getItemCount() {
        return items.isEmpty() ? 0 : items.size() + 1;
    }

    @Override
    public int getItemViewType(int position) {
        return position == 0 ? TYPE_PROMPT : TYPE_TILE;
    }

    @Override
    public long getItemId(int position) {
        return position == 0 ? 0 : (items.get(position - 1).id + "|" + items.get(position - 1).label).hashCode() & 0x7fffffffL | 1L << 40;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inf = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_PROMPT) {
            return new RecyclerView.ViewHolder(Theme.applied(inf.inflate(R.layout.item_section_prompt, parent, false))) {
            };
        }
        View v = Theme.applied(inf.inflate(R.layout.item_app, parent, false));
        LauncherConfig cfg = LauncherConfig.get(ctx);
        float d = v.getResources().getDisplayMetrics().density;
        int columns = cfg.columns;
        AppAdapter.applyGeometry(v, cfg, d, parent.getWidth() / columns);
        return new TileHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int position) {
        if (position == 0) {
            TextView t = h.itemView.findViewById(R.id.promptText);
            t.setTextColor(LauncherConfig.get(ctx).accent);
            t.setText(ctx.getString(R.string.sc_prompt));
            h.itemView.findViewById(R.id.promptHint).setVisibility(View.GONE);
            promptCursor = h.itemView.findViewById(R.id.promptCursor);
            promptCursor.setVisibility(focusedSection ? View.VISIBLE : View.INVISIBLE);
            return;
        }
        ((TileHolder) h).bind(items.get(position - 1));
    }

    void setFocusedSection(boolean f) {
        focusedSection = f;
        if (promptCursor != null) promptCursor.setVisibility(f ? View.VISIBLE : View.INVISIBLE);
    }

    void blink() {
        if (promptCursor != null && focusedSection) {
            promptCursor.setVisibility(promptCursor.getVisibility() == View.VISIBLE ? View.INVISIBLE : View.VISIBLE);
        }
    }

    final class TileHolder extends RecyclerView.ViewHolder {
        final View card;
        final ImageView banner, icon;
        final TextView label, name;
        LauncherConfig.Shortcut item;

        TileHolder(View v) {
            super(v);
            card = v.findViewById(R.id.card);
            banner = v.findViewById(R.id.banner);
            icon = v.findViewById(R.id.icon);
            label = v.findViewById(R.id.label);
            name = v.findViewById(R.id.name);
            card.setOnClickListener(x -> {
                if (item != null) listener.onShortcutClick(item);
            });
            card.setOnFocusChangeListener((x, focused) -> {
                float s = focused ? AppAdapter.FOCUS_SCALE : 1f;
                card.animate().scaleX(s).scaleY(s).setDuration(90).start();
                AppAdapter.setMuted(banner, !focused);
                AppAdapter.setMuted(icon, !focused);
                if (!focused && item != null && "pc".equals(item.type) && !OmarchyLink.connected()) {
                    banner.setColorFilter(AppAdapter.UNAVAILABLE);
                    icon.setColorFilter(AppAdapter.UNAVAILABLE);
                }
                name.animate().alpha(focused ? 1f : 0f).setDuration(90).start();
                if (focused) listener.onShortcutFocused(offset + getBindingAdapterPosition(), x);
            });
            card.setOnKeyListener((x, keyCode, e) -> {
                if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (keyCode != KeyEvent.KEYCODE_DPAD_UP && keyCode != KeyEvent.KEYCODE_DPAD_DOWN) return false;
                int pos = getBindingAdapterPosition();
                return pos > 0 && listener.onShortcutVertical(pos - 1, keyCode);
            });
        }

        void bind(LauncherConfig.Shortcut s) {
            item = s;
            boolean pcOff = "pc".equals(s.type) && !OmarchyLink.connected();
            Drawable art = null, ic = null;
            if ("app".equals(s.type) || ("view".equals(s.type) && !s.pkg.isEmpty())) {
                PackageManager pm = ctx.getPackageManager();
                try {
                    art = pm.getApplicationBanner(s.pkg);
                    if (art == null) ic = pm.getApplicationIcon(s.pkg);
                } catch (PackageManager.NameNotFoundException ignored) {
                }
            }
            if (art == null && ic == null) ic = ctx.getDrawable("pc".equals(s.type) ? R.drawable.omarchy_icon : R.drawable.ic_web);
            boolean focused = card.isFocused();
            if (art != null) {
                banner.setImageDrawable(art);
                banner.setVisibility(View.VISIBLE);
                icon.setVisibility(View.GONE);
                label.setVisibility(View.GONE);
            } else {
                banner.setImageDrawable(null);
                banner.setVisibility(View.GONE);
                icon.setImageDrawable(ic);
                icon.setVisibility(View.VISIBLE);
                label.setText(s.label);
                label.setVisibility(View.VISIBLE);
            }
            AppAdapter.setMuted(banner, !focused);
            AppAdapter.setMuted(icon, !focused);
            if (pcOff) {   // greyed but opaque: readability over transparency
                banner.setColorFilter(AppAdapter.UNAVAILABLE);
                icon.setColorFilter(AppAdapter.UNAVAILABLE);
            }
            // Name under the tile on focus; a PC command says why it would not run.
            name.setText(pcOff ? s.label + " · " + ctx.getString(R.string.link_offline) : s.label);
            name.setAlpha(focused ? 1f : 0f);
        }
    }
}
