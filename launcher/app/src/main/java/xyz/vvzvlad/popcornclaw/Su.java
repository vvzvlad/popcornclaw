package xyz.vvzvlad.popcornclaw;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * The su command line for every root call of the app. Magisk starts root with no supplementary groups, and
 * Android 14's run-as (Termux's apt, the bootstrap, the agent's own installs) dies with SIGSEGV in
 * get_supplementary_gids when its caller has none; with Magisk the app therefore asks for root as
 * {@code su -g 0 -G 3003}, which keeps gid 0 and adds inet. Magisk learned -g/-G in v26.2 (older versions
 * exit 2 on them) and SuperSU knows neither flag (exit 127): both are called as before.
 */
final class Su {
    /** The first Magisk version code whose su takes -g and -G. */
    static final int MAGISK_GROUPS = 26200;

    private static volatile Boolean groups;

    private Su() {
    }

    /** The su command line for args on this box's su. */
    static String[] argv(String... args) {
        return argv(groups(), args);
    }

    /** "su", then a leading "--mount-master" from args, then -g 0 -G 3003 when groups is set, then the rest of args. */
    static String[] argv(boolean groups, String... args) {
        List<String> a = new ArrayList<>();
        a.add("su");
        int i = 0;
        if (args.length > 0 && "--mount-master".equals(args[0])) {
            a.add(args[0]);
            i = 1;
        }
        if (groups) {
            a.add("-g");
            a.add("0");
            a.add("-G");
            a.add("3003");
        }
        for (; i < args.length; i++) a.add(args[i]);
        return a.toArray(new String[0]);
    }

    /** True when `su -v` names Magisk ("26.4:MAGISKSU") and its `su -V` version code ("26400") takes -g/-G. */
    static boolean groups(String v, String code) {
        if (!v.contains("MAGISK")) return false;
        try {
            return Integer.parseInt(code.trim()) >= MAGISK_GROUPS;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** groups(String, String) for this box's su; the answer is kept once su has given one. */
    private static boolean groups() {
        Boolean g = groups;
        if (g != null) return g;
        try {
            String v = out("-v");
            g = groups(v, v.contains("MAGISK") ? out("-V") : "");
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        groups = g;
        return g;
    }

    /** What `su <flag>` prints. */
    private static String out(String flag) throws IOException, InterruptedException {
        Process p = new ProcessBuilder("su", flag).redirectErrorStream(true).start();
        p.getOutputStream().close();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = p.getInputStream()) {
            byte[] b = new byte[256];
            int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
        }
        p.waitFor();
        return out.toString("UTF-8");
    }
}
