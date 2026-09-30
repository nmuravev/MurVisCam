package android.os;
/** Test shim with a manually controllable clock. */
public class SystemClock {
    private static volatile long now = 0;
    public static void setNowForTests(long ms) { now = ms; }
    public static long uptimeMillis() { return now; }
}
