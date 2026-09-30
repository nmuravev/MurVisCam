package android.graphics;
/** JVM test shim for android.graphics.Bitmap (smoke tests only). */
public class Bitmap {
    public enum Config { ARGB_8888, RGB_565 }
    public int width, height;
    public int getWidth() { return width; }
    public int getHeight() { return height; }
}
