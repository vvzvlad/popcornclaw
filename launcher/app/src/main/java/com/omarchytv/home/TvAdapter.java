package com.omarchytv.home;

import android.content.Context;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

/**
 * The "> tv" section under the apps: a prompt header and one wide tile that opens the TV's own Live TV app
 * (Sony: com.sony.dtv.tvx), which lands on the last channel watched. No channel data is read.
 * Sits after {@link AppAdapter} in a ConcatAdapter, so app positions are unchanged.
 */
final class TvAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    interface Listener {
        void onTvFocused(int globalPos, View card);

        void onTvClick();

        /** D-pad Up from the TV tile. */
        void onTvUp();
    }

    static final int TYPE_PROMPT = 1, TYPE_TILE = 2;

    private final Context ctx;
    private final Listener listener;
    private boolean shown;
    private int offset;
    View promptCursor;
    boolean focusedSection;

    TvAdapter(Context ctx, Listener listener) {
        this.ctx = ctx;
        this.listener = listener;
        setHasStableIds(true);
    }

    void setShown(boolean s) {
        if (s == shown) return;
        shown = s;
        notifyDataSetChanged();
    }

    void setOffset(int appCount) {
        offset = appCount;
    }

    int offset() {
        return offset;
    }

    /** Local index of the TV tile, or -1 when the section is off. */
    int tile() {
        return shown ? 1 : -1;
    }

    @Override
    public int getItemCount() {
        return shown ? 2 : 0;
    }

    @Override
    public int getItemViewType(int position) {
        return position == 0 ? TYPE_PROMPT : TYPE_TILE;
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inf = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_PROMPT) {
            return new RecyclerView.ViewHolder(Theme.applied(inf.inflate(R.layout.item_section_prompt, parent, false))) {
            };
        }
        View v = Theme.applied(inf.inflate(R.layout.item_tv, parent, false));
        LauncherConfig cfg = LauncherConfig.get(ctx);
        float d = v.getResources().getDisplayMetrics().density;
        int columns = cfg.columns;
        int cell = parent.getWidth() / columns;
        int[] size = AppAdapter.tileSize(cfg, d, cell);
        // Two app tiles wide, left edge on the first app column, same height as an app tile.
        View card = v.findViewById(R.id.card);
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) card.getLayoutParams();
        lp.width = cell > 0 ? cell + size[0] : 2 * size[0];
        lp.height = size[1];
        lp.leftMargin = cell > 0 ? (cell - size[0]) / 2 : 0;
        card.setLayoutParams(lp);
        card.setBackground(AppAdapter.tileBackground(cfg, d, true));
        int gap = Math.round(cfg.gapDp * d);
        v.setPadding(0, gap, 0, gap);
        return new TileHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int position) {
        if (position == 0) {
            TextView t = h.itemView.findViewById(R.id.promptText);
            t.setTextColor(LauncherConfig.get(ctx).accent);
            t.setText(ctx.getString(R.string.tv_prompt));
            h.itemView.findViewById(R.id.promptHint).setVisibility(View.GONE);   // the tile says what it is
            promptCursor = h.itemView.findViewById(R.id.promptCursor);
            promptCursor.setVisibility(focusedSection ? View.VISIBLE : View.INVISIBLE);
        }
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
        TileHolder(View v) {
            super(v);
            View card = v.findViewById(R.id.card);
            TextView title = v.findViewById(R.id.tvTitle);
            card.setOnClickListener(x -> listener.onTvClick());
            card.setOnFocusChangeListener((x, focused) -> {
                title.setTextColor(focused ? Theme.m(0xFFC0CAF5) : Theme.m(0xFFA9B1D6));
                if (focused) listener.onTvFocused(offset + getBindingAdapterPosition(), x);
            });
            card.setOnKeyListener((x, keyCode, e) -> {
                if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) return true;    // last row: stay put
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    listener.onTvUp();
                    return true;
                }
                return false;
            });
        }
    }
}
