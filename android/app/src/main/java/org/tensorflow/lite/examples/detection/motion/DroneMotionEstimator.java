/*
 * Drone motion / trajectory estimator.
 *
 * This is the ONLY functionality we keep from the RelateAnything idea
 * (https://github.com/Maelic/RelateAnything): determining the RELATIVE motion
 * direction of a detected drone with respect to the camera, so we can build a
 * trajectory as the drone approaches:
 *
 *   - longitudinal axis (toward/away from camera): derived from the temporal
 *     derivative of the pinhole-model slant range Z(t) that the detection
 *     pipeline already computes (DetectorActivity.objectPos.getRelativeZ());
 *   - lateral axis (left/right) and vertical axis (up/down): derived from the
 *     temporal derivatives of the world-frame lateral offset X(t) and height
 *     Y(t) computed by PinholeModel;
 *   - when no companion service is available the estimator degrades gracefully
 *     to a purely geometric mode: box-size trend (approaching/receding) plus
 *     box-centroid trend in image space (left/right/up/down).
 *
 * The optional RelateAnything companion endpoint may ALSO return semantic
 * predicates ("approaching", "moving left", ...); they are parsed and used to
 * override/corroborate the geometric estimate. The network call is throttled
 * and fully asynchronous - it never blocks the inference or render threads.
 */

package org.tensorflow.lite.examples.detection.motion;

import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.tensorflow.lite.examples.detection.tflite.Classifier;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class DroneMotionEstimator {

    private static final String TAG = "DroneMotion";

    /** Minimum interval between two requests to the RelateAnything companion. */
    public static final long MIN_REQUEST_INTERVAL_MS = 500;
    private static final int TIMEOUT_MS = 1500;

    /** History window for the regression filters (frames). */
    private static final int HISTORY_SIZE = 24;
    /** A sample older than this is dropped (tracker re-identification gap). */
    private static final long MAX_SAMPLE_AGE_MS = 1500;
    /** Below these rates the object is considered stationary on that axis. */
    private static final double EPS_Z = 0.08;      // m/s
    private static final double EPS_XY = 0.12;     // m/s
    private static final double EPS_SIZE = 3.0;    // px(sqrt-area)/s geometric fallback
    private static final double EPS_CENTROID = 6;  // px/s   geometric fallback

    /**
     * Motion quadrant relative to the camera:
     *   APPROACHING / RECESSION (range) combined with LEFT / RIGHT (lateral).
     * Vertical component (UP/DOWN) is tracked separately and folded into the
     * human-readable description produced by {@link #describe}.
     */
    public enum Direction {
        STATIONARY("Стоит"),
        APPROACHING("Приближается"),
        RECESSION("Удаляется"),
        APPROACHING_LEFT("Приближается спереди-слева"),
        APPROACHING_RIGHT("Приближается спереди-справа"),
        RECESSION_LEFT("Удаляется влево"),
        RECESSION_RIGHT("Удаляется вправо");

        public final String ru;
        Direction(final String ru) { this.ru = ru; }

        public boolean isApproaching() {
            return this == APPROACHING || this == APPROACHING_LEFT || this == APPROACHING_RIGHT;
        }
        public boolean isReceding() {
            return this == RECESSION || this == RECESSION_LEFT || this == RECESSION_RIGHT;
        }
    }

    /** One per tracked target (keyed by Recognition id). */
    private static class Track {
        final List<double[]> zHist = new ArrayList<>();    // {t, relativeZ}
        final List<double[]> xHist = new ArrayList<>();    // {t, lateral X (m)}
        final List<double[]> yHist = new ArrayList<>();    // {t, altitude Y (m)}
        final List<double[]> geomHist = new ArrayList<>(); // {t, cx, cy, sqrt(area)}
        Direction last = Direction.STATIONARY;
        Boolean lastVerticalUp = null;
        Direction notifiedDirection = null;
        long lastNotifyUptimeMs = 0;
        volatile Direction remoteDirection = null;         // from RelateAnything server
        volatile long remoteDirectionUptimeMs = 0;
    }

    private final Map<String, Track> tracks = new HashMap<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor;

    private final String endpointUrl;
    private final boolean useRemoteService;

    private volatile long lastRequestUptimeMs = 0;
    private volatile boolean requestInFlight = false;
    private volatile boolean shutdown = false;

    /** Latest non-stationary state across all tracks (for HUD/banner). */
    private volatile Direction latestDirection = Direction.STATIONARY;
    private volatile Boolean latestVerticalUp = null;
    private volatile String latestTrackId = "";

    public DroneMotionEstimator(final String endpointUrl, final boolean useRemoteService) {
        this.endpointUrl = endpointUrl;
        this.useRemoteService = useRemoteService && endpointUrl != null && !endpointUrl.isEmpty();
        this.executor = this.useRemoteService ? Executors.newSingleThreadExecutor() : null;
    }

    /**
     * Core function kept from RelateAnything: given a tracked detection and its
     * current 3-D pose relative to the camera, update the trajectory state and
     * return the motion direction quadrant.
     *
     * @param id        stable track id (Recognition.getId())
     * @param rect      detection box in model-input coordinates
     * @param relZ      pinhole slant range Z(t) [m] (NaN -> geometric mode)
     * @param lateralX  pinhole lateral offset X(t) [m] (NaN -> unknown)
     * @param altitudeY pinhole altitude offset Y(t) [m] (NaN -> unknown)
     * @param nowMs     SystemClock.uptimeMillis() of the frame
     */
    public synchronized Direction update(final String id,
                                         final RectF rect,
                                         final double relZ,
                                         final double lateralX,
                                         final double altitudeY,
                                         final long nowMs) {
        if (id == null || rect == null) return Direction.STATIONARY;

        Track tr = tracks.get(id);
        if (tr == null) {
            tr = new Track();
            tracks.put(id, tr);
        }

        // ---- feed history ---------------------------------------------------
        if (!Double.isNaN(relZ)) {
            push(tr.zHist, nowMs, relZ);
            if (!Double.isNaN(lateralX)) push(tr.xHist, nowMs, lateralX);
            if (!Double.isNaN(altitudeY)) push(tr.yHist, nowMs, altitudeY);
        }
        pushGeom(tr.geomHist, nowMs, rect.centerX(), rect.centerY(),
                (float) Math.sqrt(Math.max(0f, rect.width() * rect.height())));

        // prune stale tracks when the map grows too large
        if (tracks.size() > 32) {
            final List<String> dead = new ArrayList<>();
            for (final Map.Entry<String, Track> e : tracks.entrySet()) {
                final List<double[]> g = e.getValue().geomHist;
                if (g.isEmpty() || nowMs - g.get(g.size() - 1)[0] > 5 * MAX_SAMPLE_AGE_MS) {
                    dead.add(e.getKey());
                }
            }
            for (final String k : dead) tracks.remove(k);
        }

        // ---- longitudinal: approaching / receding -----------------------------
        Boolean approaching = null;   // null = not enough evidence yet
        if (tr.zHist.size() >= 4) {
            final double dz = slope(tr.zHist);           // m/s; negative => closing in
            if (Math.abs(dz) >= EPS_Z) approaching = dz < 0;
        }
        if (approaching == null && tr.geomHist.size() >= 6) {
            // Geometric fallback: growing apparent size == getting closer.
            final double dSize = slopeChannel(tr.geomHist, 3); // px/s
            if (Math.abs(dSize) >= EPS_SIZE) approaching = dSize > 0;
        }

        // ---- lateral: left / right ----------------------------------------------
        Boolean movingLeft = null;
        if (tr.xHist.size() >= 4) {
            final double dx = slope(tr.xHist);           // m/s, + = camera-right
            if (Math.abs(dx) >= EPS_XY) movingLeft = dx < 0;
        }
        if (movingLeft == null && tr.geomHist.size() >= 6) {
            final double dcx = slopeChannel(tr.geomHist, 1);
            if (Math.abs(dcx) >= EPS_CENTROID) movingLeft = dcx < 0;
        }

        // ---- vertical: up / down ------------------------------------------------
        Boolean movingUp = null;
        if (tr.yHist.size() >= 4) {
            final double dy = slope(tr.yHist);
            if (Math.abs(dy) >= EPS_XY) movingUp = dy > 0;
        }
        if (movingUp == null && tr.geomHist.size() >= 6) {
            final double dcy = slopeChannel(tr.geomHist, 2); // image y grows downward
            if (Math.abs(dcy) >= EPS_CENTROID) movingUp = dcy < 0;
        }

        // ---- combine into quadrant -----------------------------------------------
        Direction dir;
        if (approaching == null) {
            dir = Direction.STATIONARY;
        } else if (approaching) {
            if (Boolean.TRUE.equals(movingLeft)) dir = Direction.APPROACHING_LEFT;
            else if (Boolean.FALSE.equals(movingLeft)) dir = Direction.APPROACHING_RIGHT;
            else dir = Direction.APPROACHING;
        } else {
            if (Boolean.TRUE.equals(movingLeft)) dir = Direction.RECESSION_LEFT;
            else if (Boolean.FALSE.equals(movingLeft)) dir = Direction.RECESSION_RIGHT;
            else dir = Direction.RECESSION;
        }

        // Semantic override from RelateAnything server (fresh within 2 s).
        if (tr.remoteDirection != null && nowMs - tr.remoteDirectionUptimeMs < 2000) {
            dir = tr.remoteDirection;
        }

        tr.last = dir;
        tr.lastVerticalUp = movingUp;
        latestDirection = dir;
        latestVerticalUp = movingUp;
        latestTrackId = id;
        return dir;
    }

    /** Human-readable one-liner for the overlay/HUD, e.g. "↙ Приближается · ниже". */
    public String describe(final Direction d) {
        final String arrow;
        switch (d) {
            case APPROACHING:       arrow = "↑↑↑"; break; // closing distance
            case APPROACHING_LEFT:  arrow = "↙"; break;
            case APPROACHING_RIGHT: arrow = "↘"; break;
            case RECESSION:         arrow = "↓↓↓"; break; // opening distance
            case RECESSION_LEFT:    arrow = "↖"; break;
            case RECESSION_RIGHT:   arrow = "↗"; break;
            default:                arrow = "•"; break;
        }
        String v = "";
        if (latestVerticalUp != null) v = latestVerticalUp ? " · выше" : " · ниже";
        return arrow + " " + d.ru + v;
    }

    /**
     * True when an alert should be raised for this track/frame combination:
     * immediately on direction change, then at most once per 3 s while the
     * same direction persists (periodic reminder).
     */
    public synchronized boolean shouldNotify(final String id, final Direction d, final long nowMs) {
        final Track tr = tracks.get(id);
        if (tr == null || d == Direction.STATIONARY) return false;
        if (tr.notifiedDirection != d) {
            tr.notifiedDirection = d;
            tr.lastNotifyUptimeMs = nowMs;
            return true; // direction changed -> notify now
        }
        if (nowMs - tr.lastNotifyUptimeMs >= 3000) {
            tr.lastNotifyUptimeMs = nowMs;
            return true; // periodic reminder
        }
        return false;
    }

    public Direction getLatestDirection() { return latestDirection; }
    public Boolean getLatestVerticalUp() { return latestVerticalUp; }
    public String getLatestTrackId() { return latestTrackId; }

    /**
     * Drops all accumulated trajectories. Must be called when the video source
     * changes (different camera => different intrinsics/pose), otherwise the
     * Z(t)/X(t) derivatives would mix samples from two lenses and produce
     * phantom "approaching" directions.
     */
    public synchronized void resetAll() {
        tracks.clear();
        latestDirection = Direction.STATIONARY;
        latestVerticalUp = null;
        latestTrackId = "";
    }

    /**
     * Optional async query to the RelateAnything companion service asking ONLY
     * for the motion predicate of each box. Throttled; never blocks.
     *
     * Expected server response format (only `predicate` matters here):
     * { "relations": [ {"box_id": "<id>", "predicate":
     *   "approaching|receding|moving_left|moving_right|hovering"} ] }
     */
    public void queryRemoteDirectionAsync(final List<Classifier.Recognition> detections) {
        if (shutdown || !useRemoteService || executor == null) return;
        if (detections == null || detections.isEmpty()) return;

        final long now = android.os.SystemClock.uptimeMillis();
        if (requestInFlight || now - lastRequestUptimeMs < MIN_REQUEST_INTERVAL_MS) return;
        requestInFlight = true;
        lastRequestUptimeMs = now;

        final StringBuilder boxesJson = new StringBuilder();
        for (int i = 0; i < detections.size(); i++) {
            final Classifier.Recognition r = detections.get(i);
            final RectF l = r.getLocation();
            if (i > 0) boxesJson.append(",");
            boxesJson.append("{\"box_id\":\"").append(r.getId()).append("\",")
                    .append("\"x1\":").append(l.left).append(",")
                    .append("\"y1\":").append(l.top).append(",")
                    .append("\"x2\":").append(l.right).append(",")
                    .append("\"y2\":").append(l.bottom).append("}");
        }
        final String body = "{\"task\":\"motion\",\"boxes\":[" + boxesJson + "]}";

        executor.execute(() -> {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(endpointUrl).openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(TIMEOUT_MS);
                conn.setReadTimeout(TIMEOUT_MS);
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));

                if (conn.getResponseCode() == HttpURLConnection.HTTP_OK) {
                    final String resp = readFully(conn.getInputStream());
                    parseRemoteDirections(resp);
                }
            } catch (Exception e) {
                Log.w(TAG, "RelateAnything motion query failed: " + e.getMessage());
            } finally {
                if (conn != null) conn.disconnect();
                requestInFlight = false;
            }
        });
    }

    private void parseRemoteDirections(final String json) {
        final Map<String, Direction> parsed = new HashMap<>();
        int idx = 0;
        while ((idx = json.indexOf("\"box_id\"", idx)) >= 0) {
            final String id = extractStringField(json, idx + "\"box_id\"".length());
            final int pIdx = json.indexOf("\"predicate\"", idx);
            if (id != null && pIdx >= 0) {
                final String pred = extractStringField(json, pIdx + "\"predicate\"".length());
                final Direction d = predicateToDirection(pred);
                if (d != null) parsed.put(id, d);
            }
            idx = Math.max(idx + 1, pIdx < 0 ? idx : pIdx + 1);
        }
        final long now = android.os.SystemClock.uptimeMillis();
        mainHandler.post(() -> {
            synchronized (DroneMotionEstimator.this) {
                for (final Map.Entry<String, Direction> e : parsed.entrySet()) {
                    Track tr = tracks.get(e.getKey());
                    if (tr == null) { tr = new Track(); tracks.put(e.getKey(), tr); }
                    tr.remoteDirection = e.getValue();
                    tr.remoteDirectionUptimeMs = now;
                }
            }
        });
    }

    private static Direction predicateToDirection(final String p) {
        if (p == null) return null;
        final String s = p.toLowerCase();
        if (s.contains("approach") || s.contains("closing") || s.contains("coming"))
            return Direction.APPROACHING;
        if (s.contains("reced") || s.contains("leaving") || s.contains("away") || s.contains("depart"))
            return Direction.RECESSION;
        if (s.contains("left")) return Direction.APPROACHING_LEFT;
        if (s.contains("right")) return Direction.APPROACHING_RIGHT;
        if (s.contains("hover") || s.contains("stationary")) return Direction.STATIONARY;
        return null;
    }

    // ---- small helpers -----------------------------------------------------------

    private static void push(final List<double[]> hist, final long t, final double v) {
        trim(hist, t);
        hist.add(new double[]{t, v});
        if (hist.size() > HISTORY_SIZE) hist.remove(0);
    }

    private static void pushGeom(final List<double[]> hist, final long t,
                                 final float cx, final float cy, final float size) {
        trim(hist, t);
        hist.add(new double[]{t, cx, cy, size});
        if (hist.size() > HISTORY_SIZE) hist.remove(0);
    }

    private static void trim(final List<double[]> h, final long now) {
        while (!h.isEmpty() && now - h.get(0)[0] > MAX_SAMPLE_AGE_MS) h.remove(0);
    }

    /** Least-squares slope (units/s) over {t, value} samples. */
    private static double slope(final List<double[]> h) {
        return slopeChannel(h, 1);
    }

    /** Least-squares slope of channel `ch` of {t, ..., value_at_ch} samples.
     *  Package-visible static for unit-testability (smoke tests). */
    public static double slopeChannel(final List<double[]> h, final int ch) {
        final int n = h.size();
        if (n < 2) return 0;
        double sx = 0, sy = 0, sxx = 0, sxy = 0;
        final double t0 = h.get(0)[0];
        for (int i = 0; i < n; i++) {
            final double x = (h.get(i)[0] - t0) / 1000.0;
            final double y = h.get(i)[ch];
            sx += x; sy += y; sxx += x * x; sxy += x * y;
        }
        final double den = n * sxx - sx * sx;
        return den == 0 ? 0 : (n * sxy - sx * sy) / den;
    }

    private static String extractStringField(String json, int from) {
        int colon = json.indexOf(':', from);
        if (colon < 0) return null;
        int q1 = json.indexOf('"', colon + 1);
        if (q1 < 0) return null;
        int q2 = json.indexOf('"', q1 + 1);
        if (q2 < 0 || q2 - q1 > 256) return null;
        return json.substring(q1 + 1, q2);
    }

    private static String readFully(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toString("UTF-8");
    }

    public void shutdown() {
        shutdown = true;
        if (executor != null) {
            executor.shutdownNow();
            try { executor.awaitTermination(TIMEOUT_MS, TimeUnit.MILLISECONDS); }
            catch (InterruptedException ignored) { }
        }
    }
}
