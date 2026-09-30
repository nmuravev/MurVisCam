/*
 * Copyright 2019 The TensorFlow Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.tensorflow.lite.examples.detection;

import static org.tensorflow.lite.examples.detection.utils.AppConfiguration.DESIRED_PREVIEW_WIDTH;
import static org.tensorflow.lite.examples.detection.utils.AppConfiguration.DESIRED_PREVIEW_HEIGHT;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Paint.Style;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.hardware.camera2.CameraManager;
import android.media.ImageReader.OnImageAvailableListener;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;
import android.util.Size;
import android.util.TypedValue;
import android.widget.Toast;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.TimeZone;

import org.tensorflow.lite.examples.detection.customview.OverlayView;
import org.tensorflow.lite.examples.detection.customview.OverlayView.DrawCallback;
import org.tensorflow.lite.examples.detection.env.BorderedText;
import org.tensorflow.lite.examples.detection.env.ImageUtils;
import org.tensorflow.lite.examples.detection.env.Logger;
import org.tensorflow.lite.examples.detection.motion.DroneMotionEstimator;
import org.tensorflow.lite.examples.detection.position.ObjectPosition;
import org.tensorflow.lite.examples.detection.position.PinholeModel;
import org.tensorflow.lite.examples.detection.position.WorldCoordinateOffset;
import org.tensorflow.lite.examples.detection.tflite.Classifier;
import org.tensorflow.lite.examples.detection.tflite.DetectorFactory;
import org.tensorflow.lite.examples.detection.tflite.Yolo26EndToEndClassifier;
import org.tensorflow.lite.examples.detection.tflite.YoloV5Classifier;
import org.tensorflow.lite.examples.detection.tracking.MultiBoxTracker;
import org.tensorflow.lite.examples.detection.utils.AppConfiguration;

/**
 * An activity that uses a TensorFlowMultiBoxDetector and ObjectTracker to detect and then track
 * objects.
 */
public class DetectorActivity extends CameraActivity implements OnImageAvailableListener {
    private static final Logger LOGGER = new Logger();

    private static final DetectorMode MODE = DetectorMode.TF_OD_API;
    private static final float MINIMUM_CONFIDENCE_TF_OD_API = 0.3f;
    private static final boolean MAINTAIN_ASPECT = false; // was true
    private static final Size DESIRED_PREVIEW_SIZE = new Size(DESIRED_PREVIEW_WIDTH, DESIRED_PREVIEW_HEIGHT);
    private static final boolean SAVE_PREVIEW_BITMAP = false;
    private static final float TEXT_SIZE_DIP = 10;
    OverlayView trackingOverlay;
    private Integer sensorOrientation;

    private Classifier detector;

    /**
     * Device-specific delegate helpers (useCPU/useGpu/useNNAPI/getInputSize) are
     * implemented by both YOLOv5 and YOLO26 back-ends but not part of the
     * {@link Classifier} interface, so we dispatch on the concrete type.
     */
    private void applyProcessor(final String device) {
        if (detector instanceof YoloV5Classifier) {
            YoloV5Classifier y5 = (YoloV5Classifier) detector;
            if (device.equals("CPU")) y5.useCPU();
            else if (device.equals("GPU")) y5.useGpu();
            else if (device.equals("NNAPI")) y5.useNNAPI();
        } else if (detector instanceof Yolo26EndToEndClassifier) {
            Yolo26EndToEndClassifier y26 = (Yolo26EndToEndClassifier) detector;
            if (device.equals("CPU")) y26.useCPU();
            else if (device.equals("GPU")) y26.useGpu();
            else if (device.equals("NNAPI")) y26.useNNAPI();
        }
    }

    private int getDetectorInputSize() {
        if (detector instanceof YoloV5Classifier) return ((YoloV5Classifier) detector).getInputSize();
        if (detector instanceof Yolo26EndToEndClassifier) return ((Yolo26EndToEndClassifier) detector).getInputSize();
        return CameraActivity.imageSize;
    }

    private long lastProcessingTimeMs;
    private Bitmap rgbFrameBitmap = null;
    public static Bitmap croppedBitmap = null;
    private Bitmap cropCopyBitmap = null;

    private volatile boolean computingDetection = false;

    // ---- Native stream FPS meter (no artificial throttling: we measure what the
    // camera actually delivers and what analysis consumes, frame by frame). ----
    private long nativeFrameCount = 0;          // frames arriving from camera
    private long analyzedFrameCount = 0;        // frames fed into the detector
    private long fpsWindowStartMs = 0;
    private double measuredNativeFps = 0.0;
    private double measuredAnalysisFps = 0.0;

    private long timestamp = 0;

    private Matrix frameToCropTransform;
    private Matrix cropToFrameTransform;

    private MultiBoxTracker tracker;

    private BorderedText borderedText;

    public static String currentId;

    public static ObjectPosition objectPos;

    private long currentTime, lastTime;

    public static float boxHeight, boxWidth;

    public static double phm_actual_height, phm_actual_width;

    public static double phoneX, phoneY, phoneZ, phoneZ2, phoneHeading, droneHeading, dronePosRadius;

    public static double worldNorthing, worldEasting, lastNorthing, lastEasting;

    // ---- RelateAnything (motion-direction function only) ---------------------
    /**
     * Trajectory/direction estimator kept from the RelateAnything idea: for each
     * tracked drone it decides whether it APPROACHES or RECEDES combined with a
     * LEFT/RIGHT component and an UP/DOWN component. The latest state is exposed
     * here so MultiBoxTracker.draw() can render the top-of-screen HUD banner
     * ("сверху / сзади + слева/справа" combinations) without blocking the render
     * thread with any network work.
     */
    private DroneMotionEstimator motionEstimator;
    public static volatile String motionBannerText = "";
    public static volatile boolean motionBannerActive = false;
    public static volatile boolean motionBannerDanger = false; // drone approaching

    private synchronized void ensureMotionEstimator() {
        if (motionEstimator == null) {
            motionEstimator = new DroneMotionEstimator(
                    AppConfiguration.RELATE_ANYTHING_URL,
                    AppConfiguration.relateAnythingUseRemote);
        }
        // Expose to CameraActivity so the camera-source dropdown can reset
        // trajectories when the video source changes.
        this.motionEstimatorBase = motionEstimator;
        wireMotionEstimator();
    }

    /** Attach the estimator to the currently active YOLO26 classifier, if any. */
    private void wireMotionEstimator() {
        final Classifier d = detector;
        if (d instanceof org.tensorflow.lite.examples.detection.tflite.Yolo26EndToEndClassifier
                && motionEstimator != null) {
            ((org.tensorflow.lite.examples.detection.tflite.Yolo26EndToEndClassifier) d)
                    .setMotionEstimator(motionEstimator);
        }
    }

    @Override
    protected void onCreate(final android.os.Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ensureMotionEstimator();
    }

    @Override
    protected void onDestroy() {
        if (motionEstimator != null) {
            motionEstimator.shutdown();
            motionEstimator = null;
        }
        // Keep the base-class reference in sync (camera dropdown resets it).
        this.motionEstimatorBase = null;
        motionBannerText = "";
        motionBannerActive = false;
        super.onDestroy();
    }

    /** Raise a Toast alert about the current motion direction (UI thread). */
    private void notifyMotion(final String id, final DroneMotionEstimator.Direction d,
                              final long nowMs) {
        if (motionEstimator == null) return;
        if (!motionEstimator.shouldNotify(id, d, nowMs)) return;
        final String msg = motionEstimator.describe(d);
        runOnUiThread(() -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());
    }

    @Override
    public void onPreviewSizeChosen(final Size size, final int rotation) {
        final float textSizePx =
                TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_DIP, TEXT_SIZE_DIP, getResources().getDisplayMetrics());
        borderedText = new BorderedText(textSizePx);
        borderedText.setTypeface(Typeface.MONOSPACE);

        tracker = new MultiBoxTracker(this);

        final int modelIndex = modelView.getCheckedItemPosition();
        final String modelString = modelStrings.get(modelIndex);

        try {
            detector = DetectorFactory.getDetector(getAssets(), modelString);
        } catch (final IOException e) {
            e.printStackTrace();
            LOGGER.e(e, "Exception initializing classifier!");
            Toast toast =
                    Toast.makeText(
                            getApplicationContext(), "Classifier could not be initialized", Toast.LENGTH_SHORT);
            toast.show();
            finish();
        }
        ensureMotionEstimator();

        int cropSize = getDetectorInputSize();
        setImageSize(cropSize);

        previewWidth = size.getWidth();
        previewHeight = size.getHeight();

        // NO-DOWNSCALE / NO-FPS-LOSS: the analysis path consumes the FULL native
        // camera frame (previewWidth x previewHeight) exactly as delivered - no
        // resolution reduction, no stream compression. The model tensor input is a
        // fixed square (cropSize), which is only an internal letterbox transform of
        // that full-resolution frame; detections are mapped back to native pixels.
        LOGGER.d("Native analysis stream: " + previewWidth + "x" + previewHeight
                + "  | detector tensor input: " + cropSize + "x" + cropSize
                + "  | imageSize: " + CameraActivity.imageSize);

        sensorOrientation = rotation - getScreenOrientation();
        LOGGER.i("Camera orientation relative to screen canvas: %d", sensorOrientation);

        LOGGER.i("Initializing at size %dx%d", previewWidth, previewHeight);
        rgbFrameBitmap = Bitmap.createBitmap(previewWidth, previewHeight, Config.ARGB_8888);
        croppedBitmap = Bitmap.createBitmap(cropSize, cropSize, Config.ARGB_8888);

        frameToCropTransform =
                ImageUtils.getTransformationMatrix(
                        previewWidth, previewHeight,
                        cropSize, cropSize,
                        sensorOrientation, MAINTAIN_ASPECT);

        cropToFrameTransform = new Matrix();
        frameToCropTransform.invert(cropToFrameTransform);

        trackingOverlay = (OverlayView) findViewById(R.id.tracking_overlay);
        trackingOverlay.addCallback(
                new DrawCallback() {
                    @Override
                    public void drawCallback(final Canvas canvas) {
                        if (startDetector) // draws the detection bounding boxes only if the detector is turned on
                            tracker.draw(canvas);
                        if (isDebug()) {
                            tracker.drawDebug(canvas);
                        }
                    }
                });

        tracker.setFrameConfiguration(previewWidth, previewHeight, sensorOrientation);
    }

    protected void updateActiveModel() {
        // Get UI information before delegating to background
        final int modelIndex = modelView.getCheckedItemPosition();
        final int deviceIndex = deviceView.getCheckedItemPosition();
        String threads = threadsTextView.getText().toString().trim();
        final int numThreads = Integer.parseInt(threads);

        handler.post(() -> {
            if (modelIndex == currentModel && deviceIndex == currentDevice
                    && numThreads == currentNumThreads) {
                return;
            }
            currentModel = modelIndex;
            currentDevice = deviceIndex;
            currentNumThreads = numThreads;

            // Disable classifier while updating
            if (detector != null) {
                detector.close();
                detector = null;
            }

            // Lookup names of parameters.
            String modelString = modelStrings.get(modelIndex);
            String device = deviceStrings.get(deviceIndex);

            LOGGER.i("Changing model to " + modelString + " device " + device);

            // Try to load model.

            try {
                detector = DetectorFactory.getDetector(getAssets(), modelString);
                // Customize the interpreter to the type of device we want to use.
                if (detector == null) {
                    return;
                }
            }
            catch(IOException e) {
                e.printStackTrace();
                LOGGER.e(e, "Exception in updateActiveModel()");
                Toast toast =
                        Toast.makeText(
                                getApplicationContext(), "Classifier could not be initialized", Toast.LENGTH_SHORT);
                toast.show();
                finish();
            }

            // Re-attach the motion estimator after a model swap.
            ensureMotionEstimator();


            applyProcessor(device);
            detector.setNumThreads(numThreads);

            int cropSize = getDetectorInputSize();
            setImageSize(cropSize);
            croppedBitmap = Bitmap.createBitmap(cropSize, cropSize, Config.ARGB_8888);

            frameToCropTransform =
                    ImageUtils.getTransformationMatrix(
                            previewWidth, previewHeight,
                            cropSize, cropSize,
                            sensorOrientation, MAINTAIN_ASPECT);

            cropToFrameTransform = new Matrix();
            frameToCropTransform.invert(cropToFrameTransform);
        });
    }

    @Override
    protected void processImage() {
        ++timestamp;
        final long currTimestamp = timestamp;

        // ---- Native stream FPS accounting: every frame the camera hands us is counted.
        final long nowMs = SystemClock.uptimeMillis();
        if (fpsWindowStartMs == 0) fpsWindowStartMs = nowMs;
        nativeFrameCount++;
        trackingOverlay.postInvalidate();

        // NO ARTIFICIAL THROTTLE: we do not drop frames by policy - the pipeline is
        // allowed to run as fast as inference permits and every arriving frame is fed
        // into analysis as soon as the detector is free. The only serialization left
        // is the single TFLite interpreter instance itself.
        if (computingDetection) {
            readyForNextImage();
            updateFpsMeter(nowMs);
            return;
        }
        computingDetection = true;
        analyzedFrameCount++;
        LOGGER.i("Preparing image " + currTimestamp + " for detection in bg thread.");

        rgbFrameBitmap.setPixels(getRgbBytes(), 0, previewWidth, 0, 0, previewWidth, previewHeight);

        readyForNextImage();

        final Canvas canvas = new Canvas(croppedBitmap);
        canvas.drawBitmap(rgbFrameBitmap, frameToCropTransform, null);
        // For examining the actual TF input.
        if (SAVE_PREVIEW_BITMAP) {
            ImageUtils.saveBitmap(croppedBitmap);
        }


        // BUGFIX (deadlock): runInBackground() posts to the SAME background Handler that
        // delivers onImageAvailable(). With the frame-wait loop in CameraActivity
        // blocking this thread until isProcessingFrame clears, a posted inference task
        // could never run -> permanent stall. Run detection synchronously on this
        // camera/inference thread instead; results are still marshalled to UI via
        // runOnUiThread below.
        {
            LOGGER.i("Running detection on image " + currTimestamp);
                        final long startTime = SystemClock.uptimeMillis();
                        final List<Classifier.Recognition> results = detector.recognizeImage(croppedBitmap);
                        lastProcessingTimeMs = SystemClock.uptimeMillis() - startTime;

                        Log.e("CHECK", "run: " + results.size());

                        cropCopyBitmap = Bitmap.createBitmap(croppedBitmap);
                        final Canvas canvas = new Canvas(cropCopyBitmap);
                        final Paint paint = new Paint();
                        paint.setColor(Color.RED);
                        paint.setStyle(Style.STROKE);
                        paint.setStrokeWidth(2.0f);

//                        float minimumConfidence = MINIMUM_CONFIDENCE_TF_OD_API;
                        float minimumConfidence = CameraActivity.confThresh;
                        switch (MODE) {
                            case TF_OD_API:
//                                minimumConfidence = MINIMUM_CONFIDENCE_TF_OD_API;
                                minimumConfidence = CameraActivity.confThresh;
                                break;
                        }

                        final List<Classifier.Recognition> mappedRecognitions =
                                new LinkedList<Classifier.Recognition>();

                        for (final Classifier.Recognition result : results) {

                            currentId = result.getId();
                            ObjectPosition objectPosition = new ObjectPosition(result.getLocation(), imageSize);
//                            objectPosition.updateSizeConstant(drone_distance_calibration, boxWidth_calibration, boxHeight_calibration);
                            objectPos = objectPosition;

                            boxHeight = result.getLocation().height();

                            boxWidth = result.getLocation().width();


                            Log.d("Object Position RectF", "Width: " + result.getLocation().width() + " Height: " + result.getLocation().height());
                            Log.d("Object Size Constants", "Drone Distance: " + drone_distance_calibration + " Width: " + boxWidth_calibration + " Height: " + boxHeight_calibration);
                            PinholeModel pinholeModel = new PinholeModel(result.getLocation(), (CameraManager)getSystemService(CAMERA_SERVICE));
                            phm_actual_height = pinholeModel.getActualHeight(objectPosition.getRelativeZ());
                            phm_actual_width = pinholeModel.getActualWidth(objectPosition.getRelativeZ());
                            phoneX = pinholeModel.getRotatedX(objectPosition.getRelativeZ());
                            phoneY = pinholeModel.getRotatedY(objectPosition.getRelativeZ());
                            phoneZ = pinholeModel.getRotatedZ(objectPosition.getRelativeZ());
                            phoneZ2 = pinholeModel.getRotatedZDist(objectPosition.getRelativeZ());

                            WorldCoordinateOffset worldOffset = new WorldCoordinateOffset(phoneX, phoneZ, currentAzimuth);
//                            Log.d("WorldOffset", "RelativeX: " + objectPosition.getRelativeX() + " RelativeZ: " + objectPosition.getRelativeZ());
//                            WorldCoordinateOffset worldOffset = new WorldCoordinateOffset(objectPosition.getRelativeX(), objectPosition.getRelativeZ(), currentAzimuth);
                            double worldNorthOffset = worldOffset.getNorthingOffset();
                            double worldEastOffset = worldOffset.getEastingOffset();
                            phoneHeading = worldOffset.getPhoneHeading();
                            droneHeading = worldOffset.getDroneHeading();
                            dronePosRadius = worldOffset.getDronePosRadius();
                            worldEasting = worldEastOffset;
                            worldNorthing = worldNorthOffset;

//                            SimpleDateFormat sdf = new SimpleDateFormat("yyyy.MM.dd 'at' HH:mm:ss Z");
//                            sdf.setTimeZone(TimeZone.getTimeZone("GMT+8:00"));
//                            String currentDateandTime = sdf.format(new Date());

                            currentTime = System.currentTimeMillis();
                            int size = worldOffsetResults.size();
                            String resultString = String.format("%d,%.2f,%.2f", size, worldNorthing, worldEasting);
                            if (captureWorldOffsets) {
                                if (size == 0) {
                                    worldOffsetResults.add(resultString + currentTime);
                                    lastTime = currentTime;
                                    Log.d("WorldOffSetResults","Result String Added: " + resultString + " Current Time: " +  currentTime);
                                }
                                else if (size != 0 && !String.format("%d, %d",Math.round(lastNorthing), Math.round(lastEasting)).equals(String.format("%d, %d",Math.round(worldNorthing), Math.round(worldEasting)))) {
                                    long elaspedTime = currentTime - lastTime;
                                    if (elaspedTime >= AppConfiguration.DETECTION_RATE) {
                                        worldOffsetResults.add(resultString);
                                        lastTime = currentTime;
                                        Log.d("WorldOffSetResults","Result String Added: " + resultString + " Current Time: " +  currentTime);
                                    }
                                }
                                lastNorthing = worldNorthing;
                                lastEasting = worldEasting;
                            }

                            Log.d("Drone Distance Radius", "Radius: " + worldOffset.getDronePosRadius());
                            Log.d("Phone Tilt Angle", "Theta: " + phoneTiltAngle);
                            Log.d("Pinhole Model Test", "X Distance: " + phm_actual_width + ", Y Distance: " + phm_actual_height);
                            Log.d("Detector Result", "Detected Object: " + result.getTitle() + ", " + result.getLocation() + ", imgSize: " + imageSize + ". sizeConstant: " + objectPosition.sizeConstant);
                            Log.d("Object Position", "Height: " + result.getLocation().height() + ", Width: " + result.getLocation().width() + ", X: " + objectPosition.getRelativeX() + ", Y: " + objectPosition.getRelativeY() + ", Z: " + objectPosition.getRelativeZ());
                            Log.d("Box Center", "CenterX: " + result.getLocation().centerX() + " CenterY: " + result.getLocation().centerY() + "\n");

                            // ---- RelateAnything motion-direction function (trajectory) ----
                            // Feed the estimator with this track's current 3-D pose; it
                            // returns the combined direction quadrant (approaching /
                            // receding  x  left / right, plus up/down component).
                            if (motionEstimator != null
                                    && AppConfiguration.relateAnythingEnabled
                                    && result.getConfidence() >= minimumConfidence) {
                                final DroneMotionEstimator.Direction dir =
                                        motionEstimator.update(
                                                result.getId(),
                                                result.getLocation(),
                                                objectPosition.getRelativeZ(),
                                                objectPosition.getRelativeX(),
                                                objectPosition.getRelativeY(),
                                                SystemClock.uptimeMillis());
                                motionBannerText = motionEstimator.describe(dir);
                                motionBannerActive = dir != DroneMotionEstimator.Direction.STATIONARY;
                                motionBannerDanger = dir.isApproaching();
                                notifyMotion(result.getId(), dir, SystemClock.uptimeMillis());
                            }

                            final RectF location = result.getLocation();
                            if (location != null && result.getConfidence() >= minimumConfidence) {
                                canvas.drawRect(location, paint);

                                cropToFrameTransform.mapRect(location);

                                result.setLocation(location);
                                mappedRecognitions.add(result);
                            }
                        }

                        tracker.trackResults(mappedRecognitions, currTimestamp);
                        trackingOverlay.postInvalidate();

                        computingDetection = false;

                        runOnUiThread(
                                new Runnable() {
                                    @Override
                                    public void run() {
                                        showFrameInfo(previewWidth + "x" + previewHeight);
                                        showCropInfo(cropCopyBitmap.getWidth() + "x" + cropCopyBitmap.getHeight());
                                        updateFpsMeter(SystemClock.uptimeMillis());
                                        showInference(lastProcessingTimeMs + "ms / native "
                                                + String.format("%.1f", measuredNativeFps) + "fps / analysis "
                                                + String.format("%.1f", measuredAnalysisFps) + "fps");
                                    }
                                });
        }
    }

    /** Refresh the 1-second FPS window; called from both camera and inference threads. */
    private void updateFpsMeter(final long nowMs) {
        final long elapsed = nowMs - fpsWindowStartMs;
        if (elapsed >= 1000) {
            measuredNativeFps = nativeFrameCount * 1000.0 / elapsed;
            measuredAnalysisFps = analyzedFrameCount * 1000.0 / elapsed;
            nativeFrameCount = 0;
            analyzedFrameCount = 0;
            fpsWindowStartMs = nowMs;
        }
    }

    @Override
    protected int getLayoutId() {
        return R.layout.tfe_od_camera_connection_fragment_tracking;
    }

    @Override
    protected Size getDesiredPreviewFrameSize() {
        // NO-DOWNSCALE POLICY: ask the camera for its largest native stream. The
        // actual delivered size is re-read in onPreviewSizeChosen and analyzed 1:1 -
        // whatever resolution/fps/bitrate the camera produces is what gets detected.
        return new Size(Integer.MAX_VALUE, Integer.MAX_VALUE);
    }

    // Which detection model to use: by default uses Tensorflow Object Detection API frozen
    // checkpoints.
    private enum DetectorMode {
        TF_OD_API;
    }

    @Override
    protected void setUseNNAPI(final boolean isChecked) {
        runInBackground(() -> detector.setUseNNAPI(isChecked));
    }

    @Override
    protected void setNumThreads(final int numThreads) {
        runInBackground(() -> detector.setNumThreads(numThreads));
    }

    MyForegroundService myForegroundService;
    private boolean bound = false;

    ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
            myForegroundService = ((MyForegroundService.MyForegroundServiceBinder) iBinder).getService();
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            myForegroundService = null;
        }
    };

    private void doBindService() {
        bindService(new Intent(DetectorActivity.this, MyForegroundService.class), serviceConnection, Context.BIND_AUTO_CREATE);
        bound = true;
    }

    private void doUnbindService() {
        if (bound) {
            unbindService(serviceConnection);
            bound = false;
        }
    }
}
