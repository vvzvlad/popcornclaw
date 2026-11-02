package xyz.vvzvlad.popcornclaw;

/**
 * The launcher theme's colours for the agent's feed and command popup. The home screen sets them on every
 * bind (theme change, resume); until it has, the old console panel's colours stand in.
 */
public final class Palette {
    final int bg, text, output, dim, accent;

    private static volatile Palette current = new Palette(0xE6000000, 0xFFE8E8E8, 0xFFB0B0B0, 0xFF80808C, 0xFFFFB86C);

    private Palette(int bg, int text, int output, int dim, int accent) {
        this.bg = bg;
        this.text = text;
        this.output = output;
        this.dim = dim;
        this.accent = accent;
    }

    /** Window background, main text, tool output, secondary text, accent. */
    public static void set(int bg, int text, int output, int dim, int accent) {
        Palette p = current;
        if (p.bg != bg || p.text != text || p.output != output || p.dim != dim || p.accent != accent) {
            current = new Palette(bg, text, output, dim, accent);
        }
    }

    /** Immutable: a changed theme is a new object, so readers can compare references. */
    static Palette get() {
        return current;
    }
}
