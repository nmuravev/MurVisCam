package android.os;
/** JVM test shim: runs posted Runnables inline. */
public class Handler {
    public Handler(Looper l) {}
    public void post(Runnable r) { r.run(); }
}
