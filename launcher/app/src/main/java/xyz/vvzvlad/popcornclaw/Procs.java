package xyz.vvzvlad.popcornclaw;

/**
 * API 24 stand-ins for {@code Process.isAlive()} and {@code Process.waitFor(long, TimeUnit)}, which need API 26:
 * the app keeps minSdk 24, and the release build's lint fails on calls above it.
 */
final class Procs {
    private Procs() {
    }

    /** True while the process has not exited. */
    static boolean alive(Process p) {
        try {
            p.exitValue();
            return false;
        } catch (IllegalThreadStateException e) {
            return true;
        }
    }

    /** Waits up to {@code timeoutMs} for the process to exit; true if it did. */
    static boolean waitFor(Process p, long timeoutMs) throws InterruptedException {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        while (alive(p)) {
            if (System.nanoTime() - deadline > 0) return false;
            Thread.sleep(50);
        }
        return true;
    }
}
