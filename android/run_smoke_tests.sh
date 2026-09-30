#!/usr/bin/env bash
# JVM smoke tests for the YOLO26 + RelateAnything-motion pipeline.
# No Android SDK required: android.* classes come from src/test/java/shims.
set -euo pipefail
cd "$(dirname "$0")"
OUT=build/smoketest/out
rm -rf "$OUT"; mkdir -p "$OUT"
javac -encoding UTF-8 -d "$OUT" \
  $(find app/src/test/java/shims -name '*.java') \
  app/src/main/java/org/tensorflow/lite/examples/detection/utils/AppConfiguration.java \
  app/src/main/java/org/tensorflow/lite/examples/detection/tflite/Classifier.java \
  app/src/main/java/org/tensorflow/lite/examples/detection/motion/DroneMotionEstimator.java \
  app/src/test/java/org/tensorflow/lite/examples/detection/SmokeTests.java
java -Dfile.encoding=UTF-8 -cp "$OUT" org.tensorflow.lite.examples.detection.SmokeTests
