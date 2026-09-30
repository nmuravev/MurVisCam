package org.tensorflow.lite.examples.detection.utils;

public class AppConfiguration {
    // Debug Mode
    public static boolean debugMode = false;

    // Launch Mode (Flutter module with OD or RTSP)
    public static String nativeBuild = "Detection";
//    public static String nativeBuild = "RTSP";

    // ---- Detection hardware (аппарат детекции) ------------------------------
    // Camera source for the object detector:
    //   EXTERNAL - USB-UVC / external lens-facing camera (preferred for PD setups)
    //   BUILTIN  - phone's built-in rear camera
    //   AUTO     - try external first, fall back to built-in
    public enum CameraSource { AUTO, EXTERNAL, BUILTIN }
    public static CameraSource cameraSource = CameraSource.AUTO;

    // ---- RelateAnything (https://github.com/Maelic/RelateAnything) ---------
    // Of the whole RelateAnything idea we keep ONLY its motion-direction
    // function: determining where the drone is heading relative to the camera
    // (approaching = спереди, receding = сзади, combined with слева/справа and
    // выше/ниже) so the trajectory of the drone closing in on the camera can be
    // understood and shown as a top-of-screen HUD banner + Toast notifications.
    // The estimator works fully offline (pinhole geometry + box trends). An
    // optional HTTP companion service running the RelateAnything VLM may refine
    // the semantic predicate; set relateAnythingUseRemote=false to disable it.
    public static boolean relateAnythingEnabled = true;         // motion HUD master switch
    public static boolean relateAnythingUseRemote = false;       // query companion service
    public static String RELATE_ANYTHING_URL = "http://192.168.1.10:8090/relate";

    // Camera Preview Desired Resolution.
    // NOTE: this is only a fallback hint for the RTSP/legacy paths. The live camera
    // analysis stream NEVER downscales: CameraConnectionFragment.chooseOptimalSize()
    // always picks the MAXIMUM native resolution the selected camera delivers, and
    // the pipeline processes every frame at the delivered fps/bitrate (no compression).
    public static int DESIRED_PREVIEW_WIDTH = 1280;
    public static int DESIRED_PREVIEW_HEIGHT = 720;

    // RTSP Client Default Streaming Settings
    public static String RTSP_URL = "rtsp://serverIPAddress/stream/";
    public static int RTSP_WIDTH = 1920;
    public static int RTSP_HEIGHT = 1080;
    public static int RTSP_FPS;
    public static int RTSP_BITRATE;

    // Object Detection Capture Data Interval
    public static int DETECTION_RATE = 1000;
}
