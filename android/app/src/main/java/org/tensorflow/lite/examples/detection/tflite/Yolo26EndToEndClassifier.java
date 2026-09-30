/* Copyright 2026 The Hunter Concept Ground Sensor Authors.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
==============================================================================*/

package org.tensorflow.lite.examples.detection.tflite;

import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.RectF;
import android.util.Log;

import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.Tensor;
import org.tensorflow.lite.examples.detection.CameraActivity;
import org.tensorflow.lite.examples.detection.env.Logger;
import org.tensorflow.lite.examples.detection.env.Utils;
import org.tensorflow.lite.examples.detection.motion.DroneMotionEstimator;
import org.tensorflow.lite.gpu.GpuDelegate;
import org.tensorflow.lite.nnapi.NnApiDelegate;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Vector;

/**
 * Detector back-end based on Ultralytics YOLO26
 * (https://docs.ultralytics.com/ru/models/yolo26).
 *
 * YOLO26 is an anchor-free, NMS-free ("end-to-end") model: it outputs a single
 * tensor of shape [1, MAX_DETECTIONS, 6] where each row is
 *   [x_center, y_center, width, height, confidence, class_id]
 * already normalized to the model input size and already de-duplicated by the
 * dual-assignment / consistent-NMS training head. Therefore no anchor decoding
 * (unlike the legacy YOLOv5 pipeline in {@link YoloV5Classifier}) and no
 * runtime non-maximum suppression are required here.
 *
 * Optional integration with RelateAnything
 * (https://github.com/Maelic/RelateAnything): from RelateAnything we only keep
 * the motion-direction capability - {@link DroneMotionEstimator} determines
 * whether a tracked drone approaches/recedes from the camera and moves
 * left/right/up/down, building the trajectory of the approach. The estimator
 * works fully on-device (geometric + pinhole-model derivatives); when
 * {@link org.tensorflow.lite.examples.detection.utils.AppConfiguration#relateAnythingEnabled}
 * is on it additionally queries a companion service (Jetson/RPi/server HTTP
 * endpoint) whose semantic predicates can override the geometric estimate.
 */
public class Yolo26EndToEndClassifier implements Classifier {

    private static final Logger LOGGER = new Logger();

    /** Max number of detections emitted by the end-to-end YOLO26 head. */
    public static final int MAX_DETECTIONS = 300;

    /** Rows per detection: xywh + score + class id. */
    private static final int ROW_SIZE = 6;

    // Float model pre-processing constants (Ultralytics export: /255 scaling).
    private final float IMAGE_MEAN = 0;
    private final float IMAGE_STD = 255.0f;

    private int INPUT_SIZE = -1;

    // Number of threads in the java app
    private static final int NUM_THREADS = 4;
    private static boolean isNNAPI = false;
    private static boolean isGPU = false;

    private boolean isModelQuantized;

    GpuDelegate gpuDelegate = null;
    NnApiDelegate nnapiDelegate = null;

    private MappedByteBuffer tfliteModel;

    private final Interpreter.Options tfliteOptions = new Interpreter.Options();

    private Vector<String> labels = new Vector<String>();
    private int[] intValues;

    private ByteBuffer imgData;
    private ByteBuffer outData;
    private Interpreter tfLite;

    private float inp_scale;
    private int inp_zero_point;
    private float oup_scale;
    private int oup_zero_point;

    /**
     * RelateAnything-derived motion estimator (only the direction-of-motion
     * function is used). Owned by DetectorActivity; set via setter so the same
     * instance survives model hot-swaps and receives the pinhole-model pose.
     */
    private DroneMotionEstimator motionEstimator;

    public void setMotionEstimator(final DroneMotionEstimator estimator) {
        this.motionEstimator = estimator;
    }

    public DroneMotionEstimator getMotionEstimator() {
        return motionEstimator;
    }

    private Yolo26EndToEndClassifier() {
    }

    /**
     * Initializes a native TensorFlow Lite session for a YOLO26 end-to-end model.
     *
     * @param assetManager  The asset manager to be used to load assets.
     * @param modelFilename The asset path of the .tflite model
     *                      (exported with Ultralytics: `yolo export format=tflite end2end=True`).
     * @param labelFilename The filepath of label file for classes.
     * @param isQuantized   Boolean representing model is quantized or not.
     * @param inputSize     Model input resolution (640 by default for YOLO26).
     */
    public static Yolo26EndToEndClassifier create(
            final AssetManager assetManager,
            final String modelFilename,
            final String labelFilename,
            final boolean isQuantized,
            final int inputSize)
            throws IOException {
        final Yolo26EndToEndClassifier d = new Yolo26EndToEndClassifier();

        String actualFilename = labelFilename.split("file:///android_asset/")[1];
        InputStream labelsInput = assetManager.open(actualFilename);
        BufferedReader br = new BufferedReader(new InputStreamReader(labelsInput));
        String line;
        while ((line = br.readLine()) != null) {
            if (line.trim().isEmpty()) continue;
            d.labels.add(line.trim());
        }
        br.close();

        try {
            Interpreter.Options options = (new Interpreter.Options());
            options.setNumThreads(NUM_THREADS);
            if (isNNAPI) {
                d.nnapiDelegate = null;
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    d.nnapiDelegate = new NnApiDelegate();
                    options.addDelegate(d.nnapiDelegate);
                    options.setNumThreads(NUM_THREADS);
                    options.setUseNNAPI(true);
                }
            }
            if (isGPU) {
                GpuDelegate.Options gpu_options = new GpuDelegate.Options();
                gpu_options.setPrecisionLossAllowed(true);
                gpu_options.setInferencePreference(GpuDelegate.Options.INFERENCE_PREFERENCE_SUSTAINED_SPEED);
                d.gpuDelegate = new GpuDelegate(gpu_options);
                options.addDelegate(d.gpuDelegate);
            }
            d.tfliteModel = Utils.loadModelFile(assetManager, modelFilename);
            d.tfLite = new Interpreter(d.tfliteModel, options);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        d.isModelQuantized = isQuantized;
        int numBytesPerChannel = isQuantized ? 1 : 4;
        d.INPUT_SIZE = inputSize;
        d.imgData = ByteBuffer.allocateDirect(1 * d.INPUT_SIZE * d.INPUT_SIZE * 3 * numBytesPerChannel);
        d.imgData.order(ByteOrder.nativeOrder());
        d.intValues = new int[d.INPUT_SIZE * d.INPUT_SIZE];

        // Validate the output tensor looks like an end-to-end head: [1, N, 6].
        Tensor outTensor = d.tfLite.getOutputTensor(0);
        int[] outShape = outTensor.shape();
        if (outShape.length != 3 || outShape[outShape.length - 1] < ROW_SIZE) {
            throw new IOException("Expected YOLO26 end-to-end output [1,N,6], got "
                    + java.util.Arrays.toString(outShape)
                    + ". Re-export the model with `format=tflite end2end=True`.");
        }

        int numRows = outShape[1];
        int rowBytes = ROW_SIZE * numBytesPerChannel;
        d.outData = ByteBuffer.allocateDirect(numRows * rowBytes);
        d.outData.order(ByteOrder.nativeOrder());

        if (d.isModelQuantized) {
            Tensor inpten = d.tfLite.getInputTensor(0);
            d.inp_scale = inpten.quantizationParams().getScale();
            d.inp_zero_point = inpten.quantizationParams().getZeroPoint();
            d.oup_scale = outTensor.quantizationParams().getScale();
            d.oup_zero_point = outTensor.quantizationParams().getZeroPoint();
        }

        return d;
    }

    public int getInputSize() {
        return INPUT_SIZE;
    }

    @Override
    public void enableStatLogging(final boolean logStats) {
    }

    @Override
    public String getStatString() {
        return "";
    }

    @Override
    public void close() {
        tfLite.close();
        tfLite = null;
        if (gpuDelegate != null) {
            gpuDelegate.close();
            gpuDelegate = null;
        }
        if (nnapiDelegate != null) {
            nnapiDelegate.close();
            nnapiDelegate = null;
        }
        tfliteModel = null;
        // NOTE: motionEstimator is owned by DetectorActivity - do not shut it down here.
    }

    public void setNumThreads(int num_threads) {
        if (tfLite != null) tfLite.setNumThreads(num_threads);
    }

    @Override
    public void setUseNNAPI(boolean isChecked) {
    }

    private void recreateInterpreter() {
        if (tfLite != null) {
            tfLite.close();
            tfLite = new Interpreter(tfliteModel, tfliteOptions);
        }
    }

    public void useGpu() {
        if (gpuDelegate == null) {
            gpuDelegate = new GpuDelegate();
            tfliteOptions.addDelegate(gpuDelegate);
            recreateInterpreter();
        }
    }

    public void useCPU() {
        recreateInterpreter();
    }

    public void useNNAPI() {
        nnapiDelegate = new NnApiDelegate();
        tfliteOptions.addDelegate(nnapiDelegate);
        recreateInterpreter();
    }

    @Override
    public float getObjThresh() {
        return CameraActivity.confThresh;
    }

    public int getCutOffY() {
        return CameraActivity.cutOffY;
    }

    protected static final int BATCH_SIZE = 1;
    protected static final int PIXEL_SIZE = 3;

    protected ByteBuffer convertBitmapToByteBuffer(Bitmap bitmap) {
        bitmap.getPixels(intValues, 0, bitmap.getWidth(), 0, 0, bitmap.getWidth(), bitmap.getHeight());
        imgData.rewind();
        for (int i = 0; i < INPUT_SIZE; ++i) {
            for (int j = 0; j < INPUT_SIZE; ++j) {
                int pixelValue = intValues[i * INPUT_SIZE + j];
                if (isModelQuantized) {
                    imgData.put((byte) ((((pixelValue >> 16) & 0xFF) - IMAGE_MEAN) / IMAGE_STD / inp_scale + inp_zero_point));
                    imgData.put((byte) ((((pixelValue >> 8) & 0xFF) - IMAGE_MEAN) / IMAGE_STD / inp_scale + inp_zero_point));
                    imgData.put((byte) (((pixelValue & 0xFF) - IMAGE_MEAN) / IMAGE_STD / inp_scale + inp_zero_point));
                } else {
                    imgData.putFloat((((pixelValue >> 16) & 0xFF) - IMAGE_MEAN) / IMAGE_STD);
                    imgData.putFloat((((pixelValue >> 8) & 0xFF) - IMAGE_MEAN) / IMAGE_STD);
                    imgData.putFloat(((pixelValue & 0xFF) - IMAGE_MEAN) / IMAGE_STD);
                }
            }
        }
        return imgData;
    }

    @Override
    public ArrayList<Recognition> recognizeImage(Bitmap bitmap) {
        ByteBuffer byteBuffer_ = convertBitmapToByteBuffer(bitmap);

        Map<Integer, Object> outputMap = new HashMap<>();
        outData.rewind();
        outputMap.put(0, outData);

        Object[] inputArray = {imgData};
        tfLite.runForMultipleInputsOutputs(inputArray, outputMap);

        ByteBuffer byteBuffer = (ByteBuffer) outputMap.get(0);
        byteBuffer.rewind();

        final int numRows = Math.min(MAX_DETECTIONS, outData.capacity() / (ROW_SIZE * (isModelQuantized ? 1 : 4)));

        ArrayList<Recognition> detections = new ArrayList<Recognition>();
        final float scale = 1.0f * INPUT_SIZE;

        for (int i = 0; i < numRows; ++i) {
            float[] row = new float[ROW_SIZE];
            for (int j = 0; j < ROW_SIZE; ++j) {
                if (isModelQuantized) {
                    row[j] = oup_scale * (((int) byteBuffer.get() & 0xFF) - oup_zero_point);
                } else {
                    row[j] = byteBuffer.getFloat();
                }
            }

            final float confidence = row[4];
            if (confidence < getObjThresh()) {
                // End-to-end heads return rows sorted by score; remaining rows are noise.
                break;
            }

            int detectedClass = Math.round(row[5]);
            if (detectedClass < 0 || detectedClass >= labels.size()) {
                continue;
            }

            // xywh are normalized to the input image; denormalize to pixels.
            final float xPos = row[0] * scale;
            final float yPos = row[1] * scale;
            final float w = row[2] * scale;
            final float h = row[3] * scale;

            // Red Line feature: drop detections whose center falls below the cutoff.
            if (yPos + h / 2.0f >= getCutOffY() && getCutOffY() > 0) {
                continue;
            }

            final RectF rect =
                    new RectF(
                            Math.max(0, xPos - w / 2),
                            Math.max(0, yPos - h / 2),
                            Math.min(bitmap.getWidth() - 1, xPos + w / 2),
                            Math.min(bitmap.getHeight() - 1, yPos + h / 2));

            String title = labels.get(detectedClass);
            detections.add(new Recognition("" + i, title, confidence, rect, detectedClass));
        }

        // YOLO26 end-to-end output requires no NMS; keep stable order by score.
        detections.sort(new Comparator<Recognition>() {
            @Override
            public int compare(final Recognition lhs, final Recognition rhs) {
                return Float.compare(rhs.getConfidence(), lhs.getConfidence());
            }
        });

        // RelateAnything (motion-direction function only): refresh the async
        // semantic-predicate query so it can corroborate/override the on-device
        // geometric estimate in DetectorActivity. Never blocks inference.
        if (motionEstimator != null && org.tensorflow.lite.examples.detection.utils.AppConfiguration.relateAnythingUseRemote) {
            motionEstimator.queryRemoteDirectionAsync(detections);
        }

        Log.d("Yolo26Classifier", "detect end, n=" + detections.size());
        return detections;
    }

    /**
     * Exposes the raw output ByteBuffer of the last inference so downstream
     * stages (e.g. trajectory/motion analysis) can re-read detection rows
     * without a second pass through the interpreter.
     */
    public ByteBuffer getOutputData() {
        return outData;
    }
}
