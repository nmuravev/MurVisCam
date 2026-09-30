package android.graphics;
public class Paint {
    public enum Style { FILL, STROKE, FILL_AND_STROKE }
    public static final int ANTI_ALIAS_FLAG = 1;
    public void setAntiAlias(boolean b) {}
    public void setColor(int c) {}
    public int getColor() { return 0; }
    public void setStyle(Style s) {}
    public void setTextSize(float t) {}
    public void setStrokeWidth(float w) {}
}
