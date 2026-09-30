package android.graphics;
/** JVM test shim for android.graphics.RectF (smoke tests only). */
public class RectF {
    public float left, top, right, bottom;
    public RectF() {}
    public RectF(RectF o) { this(o.left, o.top, o.right, o.bottom); }
    public RectF(float l, float t, float r, float b) { left=l; top=t; right=r; bottom=b; }
    public float width()  { return right - left; }
    public float height() { return bottom - top; }
    public float centerX(){ return (left + right) / 2f; }
    public float centerY(){ return (top + bottom) / 2f; }
}
