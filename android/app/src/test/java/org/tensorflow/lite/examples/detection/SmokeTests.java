package org.tensorflow.lite.examples.detection;

import android.graphics.RectF;
import android.os.SystemClock;

import org.tensorflow.lite.examples.detection.motion.DroneMotionEstimator;
import org.tensorflow.lite.examples.detection.motion.DroneMotionEstimator.Direction;
import org.tensorflow.lite.examples.detection.tflite.Classifier;
import org.tensorflow.lite.examples.detection.utils.AppConfiguration;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Smoke tests for the detection pipeline additions:
 *   - DroneMotionEstimator (RelateAnything motion-direction function only):
 *     pinhole Z(t)/X(t)/Y(t) derivatives, geometric fallback, direction
 *     quadrant combinations, notification throttling, reset on camera switch.
 *   - Classifier.Recognition title mutability (RelateAnything post-processing).
 *   - AppConfiguration defaults for camera source / RelateAnything switches.
 *
 * Runs as a plain JVM program (no Android SDK required): android.* classes are
 * provided by minimal shims under src/test/java/shims. Exit code 0 = all green.
 *
 * Run:  see android/run_smoke_tests.sh
 */
public class SmokeTests {

    private static int passed = 0;
    private static final List<String> failures = new ArrayList<>();

    // ------------------------------------------------------------------ main
    public static void main(final String[] args) {
        run("estimator_offline_approaching_center", SmokeTests::testApproachingCenter);
        run("estimator_approaching_left_right_combos", SmokeTests::testApproachingLeftRightCombos);
        run("estimator_recession_and_vertical", SmokeTests::testRecessionAndVertical);
        run("estimator_geometric_fallback_size_trend", SmokeTests::testGeometricFallback);
        run("estimator_stationary_below_eps", SmokeTests::testStationaryBelowEps);
        run("estimator_stale_samples_pruned", SmokeTests::testStaleSamplesPruned);
        run("estimator_should_notify_throttle", SmokeTests::testShouldNotifyThrottle);
        run("estimator_reset_all_clears_hud", SmokeTests::testResetAll);
        run("estimator_null_args_no_crash", SmokeTests::testNullArgsNoCrash);
        run("estimator_slope_math", SmokeTests::testSlopeMath);
        run("estimator_describe_arrows", SmokeTests::testDescribeArrows);
        run("recognition_title_mutable", SmokeTests::testRecognitionTitleMutable);
        run("appconfig_defaults", SmokeTests::testAppConfigDefaults);
        run("multi_track_isolation", SmokeTests::testMultiTrackIsolation);

        System.out.println("\n========================================");
        System.out.printf("SMOKE TESTS: %d passed, %d failed%n", passed, failures.size());
        for (final String f : failures) System.out.println("  FAILED: " + f);
        System.out.println("========================================");
        System.exit(failures.isEmpty() ? 0 : 1);
    }

    private interface TestBody { void run() throws Exception; }

    private static void run(final String name, final TestBody body) {
        try {
            body.run();
            passed++;
            System.out.println("[ OK ] " + name);
        } catch (final Throwable t) {
            failures.add(name + " -> " + t);
            System.out.println("[FAIL] " + name + " -> " + t);
            t.printStackTrace(System.out);
        }
    }

    private static void check(boolean cond, final String msg) {
        if (!cond) throw new AssertionError(msg);
    }

    // ------------------------------------------------------------- fixtures

    /** Estimator in fully-offline mode (no companion service). */
    private static DroneMotionEstimator offline() {
        return new DroneMotionEstimator(null, false);
    }

    private static RectF boxAt(final float cx, final float cy, final float half) {
        return new RectF(cx - half, cy - half, cx + half, cy + half);
    }

    /** Feeds `update` with the estimator's own clock advanced by dtMs each step. */
    private static Direction feed(final DroneMotionEstimator e, final String id,
                                  final long startMs, final int steps, final long dtMs,
                                  final double z0, final double dzPerStep,
                                  final double x0, final double dxPerStep,
                                  final double y0, final double dyPerStep,
                                  final float cx0, final float cy0, final float half) {
        Direction last = Direction.STATIONARY;
        long t = startMs;
        for (int i = 0; i < steps; i++) {
            SystemClock.setNowForTests(t);
            last = e.update(id, boxAt(cx0, cy0, half),
                    z0 + dzPerStep * i, x0 + dxPerStep * i, y0 + dyPerStep * i, t);
            t += dtMs;
        }
        return last;
    }

    // ---------------------------------------------------------------- tests

    /** Drone closing straight distance => APPROACHING (top of screen banner case). */
    private static void testApproachingCenter() {
        final DroneMotionEstimator e = offline();
        final Direction d = feed(e, "1", 1000, 8, 100,
                20.0, -0.5,   // Z shrinking: 20m -> 16.5m
                0.0, 0.0,     // no lateral motion
                0.0, 0.0,     // no vertical motion
                320, 240, 20);
        check(d == Direction.APPROACHING, "expected APPROACHING, got " + d);
        check(e.getLatestDirection() == Direction.APPROACHING, "HUD state not updated");
        check(d.isApproaching(), "isApproaching flag");
    }

    /** Combinations requested by the user: спереди-слева / спереди-справа. */
    private static void testApproachingLeftRightCombos() {
        // X decreasing at >= EPS_XY while Z closes => APPROACHING_LEFT
        final DroneMotionEstimator e1 = offline();
        final Direction left = feed(e1, "1", 1000, 8, 100,
                20.0, -0.5, -0.0, -0.3, 0.0, 0.0, 320, 240, 20);
        check(left == Direction.APPROACHING_LEFT, "expected APPROACHING_LEFT, got " + left);

        // X increasing while Z closes => APPROACHING_RIGHT
        final DroneMotionEstimator e2 = offline();
        final Direction right = feed(e2, "1", 1000, 8, 100,
                20.0, -0.5, 0.0, 0.3, 0.0, 0.0, 320, 240, 20);
        check(right == Direction.APPROACHING_RIGHT, "expected APPROACHING_RIGHT, got " + right);
    }

    /** Moving away (сзади) + vertical component reported via describe(). */
    private static void testRecessionAndVertical() {
        final DroneMotionEstimator e = offline();
        final Direction d = feed(e, "1", 1000, 8, 100,
                10.0, 0.6,    // Z growing => receding
                0.0, 0.0,
                0.0, 0.5,     // climbing
                320, 240, 20);
        check(d == Direction.RECESSION, "expected RECESSION, got " + d);
        check(Boolean.TRUE.equals(e.getLatestVerticalUp()), "vertical up should be true");
        check(e.describe(d).contains("выше"), "describe should mention 'выше': " + e.describe(d));
    }

    /** No pinhole data (NaN Z) => pure geometric fallback from box-size trend. */
    private static void testGeometricFallback() {
        final DroneMotionEstimator e = offline();
        long t = 1000;
        Direction last = Direction.STATIONARY;
        for (int i = 0; i < 10; i++) {           // box growing fast => approaching
            SystemClock.setNowForTests(t);
            last = e.update("g", boxAt(300, 300, 10 + 3 * i),
                    Double.NaN, Double.NaN, Double.NaN, t);
            t += 100;
        }
        check(last == Direction.APPROACHING, "geometric fallback expected APPROACHING, got " + last);
    }

    /** Sub-threshold motion must stay STATIONARY (no phantom alerts). */
    private static void testStationaryBelowEps() {
        final DroneMotionEstimator e = offline();
        final Direction d = feed(e, "1", 1000, 10, 100,
                20.0, -0.005,   // 0.05 m/s < EPS_Z
                0.0, 0.005,     // below EPS_XY
                0.0, 0.0,
                320, 240, 20);  // constant size
        check(d == Direction.STATIONARY, "expected STATIONARY, got " + d);
    }

    /** Samples older than MAX_SAMPLE_AGE_MS drop out; history stays bounded. */
    private static void testStaleSamplesPruned() {
        final DroneMotionEstimator e = offline();
        long t = 1000;
        for (int i = 0; i < 40; i++) {           // 40 frames @100ms = 4s window
            SystemClock.setNowForTests(t);
            e.update("1", boxAt(320, 240, 20), 20.0 - 0.5 * i, 0.0, 0.0, t);
            t += 100;
        }
        // With a 1.5s max age and 100ms cadence the Z history holds <=15 raw
        // samples; slope must remain valid and direction stable.
        final Direction d = e.getLatestDirection();
        check(d.isApproaching(), "long approaching run should stay approaching, got " + d);
    }

    /** Toast policy: instant on change, reminder no more often than every 3 s. */
    private static void testShouldNotifyThrottle() {
        final DroneMotionEstimator e = offline();
        feed(e, "1", 1000, 8, 100, 20.0, -0.5, 0.0, 0.0, 0.0, 0.0, 320, 240, 20);
        final Direction d = e.getLatestDirection();

        check(e.shouldNotify("1", d, 2000), "first alert on direction change");
        check(!e.shouldNotify("1", d, 2500), "no spam within 3s window");
        check(e.shouldNotify("1", d, 5100), "periodic reminder after 3s");
        check(e.shouldNotify("1", Direction.RECESSION, 5200), "immediate alert on change");
        check(!e.shouldNotify("1", Direction.STATIONARY, 9000), "never notify stationary");
        check(!e.shouldNotify("unknown-id", d, 9000), "unknown track never notifies");
    }

    /** Switching video source must wipe trajectories to avoid mixed-lens phantoms. */
    private static void testResetAll() {
        final DroneMotionEstimator e = offline();
        feed(e, "1", 1000, 8, 100, 20.0, -0.5, 0.0, 0.0, 0.0, 0.0, 320, 240, 20);
        check(e.getLatestDirection() != Direction.STATIONARY, "precondition: moving");
        e.resetAll();
        check(e.getLatestDirection() == Direction.STATIONARY, "reset -> STATIONARY");
        check(e.getLatestVerticalUp() == null, "reset clears vertical");
        check(e.getLatestTrackId().isEmpty(), "reset clears track id");
        // First frame after reset must not instantly claim "approaching"
        SystemClock.setNowForTests(50000);
        final Direction d = e.update("1", boxAt(320, 240, 20), 5.0, 0.0, 0.0, 50000);
        check(d == Direction.STATIONARY, "single fresh sample must be STATIONARY, got " + d);
    }

    /** Null-safety regression: bad input must not crash the inference thread. */
    private static void testNullArgsNoCrash() {
        final DroneMotionEstimator e = offline();
        check(e.update(null, boxAt(1, 1, 1), 1, 1, 1, 1) == Direction.STATIONARY, "null id");
        check(e.update("x", null, 1, 1, 1, 1) == Direction.STATIONARY, "null rect");
        e.queryRemoteDirectionAsync(null);                       // must not throw
        e.queryRemoteDirectionAsync(new ArrayList<Classifier.Recognition>());
        e.shutdown();
        e.queryRemoteDirectionAsync(Arrays.asList(
                new Classifier.Recognition("1", "drone", 0.9f, new RectF(0, 0, 10, 10))));
        // shutdown() disables remote path entirely -> still no throw
    }

    /** Least-squares slope sanity (core math behind every derivative). */
    private static void testSlopeMath() {
        final List<double[]> h = new ArrayList<>();
        for (int i = 0; i < 5; i++) h.add(new double[]{i * 1000.0, 2.0 * i}); // 2 units/s
        check(Math.abs(DroneMotionEstimator.slopeChannel(h, 1) - 2.0) < 1e-9, "slope=2");
        check(DroneMotionEstimator.slopeChannel(new ArrayList<double[]>(), 1) == 0, "empty -> 0");
        check(DroneMotionEstimator.slopeChannel(h.subList(0, 1), 1) == 0, "single -> 0");
        // flat series -> 0
        final List<double[]> flat = new ArrayList<>();
        for (int i = 0; i < 5; i++) flat.add(new double[]{i * 100.0, 7.0});
        check(Math.abs(DroneMotionEstimator.slopeChannel(flat, 1)) < 1e-9, "flat -> 0");
    }

    /** HUD strings: arrows + ru labels for every quadrant combination. */
    private static void testDescribeArrows() {
        final DroneMotionEstimator e = offline();
        check(e.describe(Direction.APPROACHING).contains("↑↑↑"), "approaching arrow");
        check(e.describe(Direction.APPROACHING_LEFT).contains("↙"), "front-left arrow");
        check(e.describe(Direction.APPROACHING_RIGHT).contains("↘"), "front-right arrow");
        check(e.describe(Direction.RECESSION).contains("↓↓↓"), "recession arrow");
        check(e.describe(Direction.RECESSION_LEFT).contains("↖"), "back-left arrow");
        check(e.describe(Direction.RECESSION_RIGHT).contains("↗"), "back-right arrow");
        check(e.describe(Direction.STATIONARY).contains("Стоит"), "stationary label");
        for (final Direction d : Direction.values()) {
            check(d.ru != null && !d.ru.isEmpty(), "ru label for " + d);
        }
    }

    /** Recognition.setTitle is what RelateAnything-style enrichment relies on. */
    private static void testRecognitionTitleMutable() {
        final Classifier.Recognition r =
                new Classifier.Recognition("id1", "drone", 0.83f, new RectF(1, 2, 30, 40));
        check("drone".equals(r.getTitle()), "initial title");
        r.setTitle("drone [подлетает слева]");
        check(r.getTitle().contains("подлетает"), "title enriched");
        check("id1".equals(r.getId()), "id stable");
        check(Math.abs(r.getConfidence() - 0.83f) < 1e-6, "confidence kept");
    }

    /** Config defaults: AUTO camera source, motion HUD on, remote off, URL set. */
    private static void testAppConfigDefaults() {
        check(AppConfiguration.cameraSource == AppConfiguration.CameraSource.AUTO,
                "cameraSource default AUTO");
        check(AppConfiguration.relateAnythingEnabled, "motion HUD enabled by default");
        check(!AppConfiguration.relateAnythingUseRemote, "remote disabled by default (offline-first)");
        check(AppConfiguration.RELATE_ANYTHING_URL != null
                        && AppConfiguration.RELATE_ANYTHING_URL.startsWith("http"),
                "companion URL looks sane");
    }

    /** Two drones: independent tracks must not cross-contaminate directions. */
    private static void testMultiTrackIsolation() {
        final DroneMotionEstimator e = offline();
        long t = 1000;
        for (int i = 0; i < 8; i++) {
            SystemClock.setNowForTests(t);
            e.update("A", boxAt(100, 240, 20), 20.0 - 0.5 * i, 0.0, 0.0, t);
            e.update("B", boxAt(500, 240, 20), 10.0 + 0.5 * i, 0.0, 0.0, t);
            t += 100;
        }
        // A approaching, B receding. Feed one more frame each and read returns.
        SystemClock.setNowForTests(t);
        final Direction a = e.update("A", boxAt(100, 240, 20), 20.0 - 0.5 * 8, 0.0, 0.0, t);
        final Direction b = e.update("B", boxAt(500, 240, 20), 10.0 + 0.5 * 8, 0.0, 0.0, t);
        check(a.isApproaching(), "track A approaching, got " + a);
        check(b.isReceding(), "track B receding, got " + b);
    }
}
