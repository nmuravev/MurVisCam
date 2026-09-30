# Гид по кодовой базе (Codebase Guide)

## Корни

```
/workspace
├── android/            # Android-приложение (Java, TFLite) — ОСНОВНОЙ КОД ДЕТЕКЦИИ
│   ├── app/src/main/java/org/tensorflow/lite/examples/detection/
│   ├── app/src/main/assets/           # .tflite модели + labels (.txt)
│   ├── app/src/test/java/             # JVM smoke-тесты + shims android.*
│   └── run_smoke_tests.sh             # запуск тестов без SDK
├── pointdefence/       # Flutter-приложение (карты, plotting, RTSP-показ)
├── docs/               # эта документация + wiki + инструкции для ИИ
├── scripts/, tools/    # пустые заглушки (будущие утилиты экспорта/сервера)
└── README.md           # общий readme проекта (англ.)
```

## Android: пакет `org.tensorflow.lite.examples.detection`

### Активность / камера
| Файл | Ответственность | Ключевые символы |
|---|---|---|
| `CameraActivity.java` | хост камеры: preview, нижняя панель, выбор модели и **spinner источника видео** | `setupCameraSourceSpinner()`, `chooseCamera()` (AUTO/EXTERNAL/BUILTIN fallback), `getModelStrings()` (YOLO26 первыми/дефолт), `lastBoundCameraId` |
| `DetectorActivity.java` | цикл инференса, Red Line, интеграция направления движения | `processImage()`, `ensureMotionEstimator()`, `applyProcessor()`, `getDetectorInputSize()`, static volatile `motionBannerText/Active/Danger`, `notifyMotion()` |
| `CameraConnectionFragment.java` | Camera2: открытие потока, ImageReader | `OnImageAvailableListener` → `acquireNextImage()` (НЕ latest!), max-разрешение, `CONTROL_AE_TARGET_FPS_RANGE` |
| `LegacyCameraConnectionFragment.java` | Camera1-фолбэк | — |
| `MainActivity/SplashActivity/MyFlutterActivity` | вход, мост во Flutter | — |
| `CameraService.java`, `MyForegroundService.java` | фоновый режим | — |

### Детекция `tflite/`
| Файл | Ответственность | Ключевые символы |
|---|---|---|
| `Classifier.java` | интерфейс классификатора + `Recognition` | `recognizeImage(Bitmap)`, `Recognition.setTitle()` (мутабельный title) |
| `DetectorFactory.java` | маршрутизация по имени модели | префикс `yolo26` → YOLO26-бэкенд, labels `labels_custom_drone.txt`; иначе YOLOv5 |
| `Yolo26EndToEndClassifier.java` | YOLO26 end-to-end (без NMS) | валидация выхода `[1,N,6]` при загрузке; `recognizeImage` — один проход, top-score класс, `System.arraycopy`; `set/getMotionEstimator()`, `getOutputData()`; делегаты CPU/GPU/NNAPI |
| `YoloV5Classifier(Detect).java` | legacy-бэкенд (anchors+NMS) | не ломать: hot-swap старых моделей |

### Направление движения `motion/`
| Файл | Ответственность | Ключевые символы |
|---|---|---|
| `DroneMotionEstimator.java` | единственная функция RelateAnything: траектория сближения | enum `Direction` (STATIONARY/APPROACHING[_LEFT/_RIGHT]/RECESSION[_LEFT/_RIGHT], `isApproaching/isReceding`), `update(id, RectF, Z,X,Y, tMs)`, `describe(dir)` (стрелки ↑↑↑ ↙ ↘ ↓↓↓ ↖ ↗ + «выше/ниже»), `shouldNotify()` (троттл 3000 мс), `resetAll()`, `getLatestDirection/VerticalUp`, public static `slopeChannel` (для тестов), константы EPS_*/MAX_SAMPLE_AGE_MS=1500/MIN_REQUEST_INTERVAL_MS=500/TIMEOUT_MS=1500; опциональный HTTP POST predicate (gated `relateAnythingUseRemote`) |

### Геооценка `position/`
`PinholeModel`, `ObjectPosition` (`getRelativeZ/X/Y` — метры), `WorldCoordinateOffset`,
`LinearInterpolation`, `DrawView`. Питают DroneMotionEstimator.

### Оверлей `tracking/`, `customview/`
| Файл | Ключевое |
|---|---|
| `MultiBoxTracker.java` | IoU-ассоциация треков; `draw()` — боксы, **верхний HUD-баннер направления** (красный при danger), метка направления под боксом; кэш строк/меток, переиспользуемые RectF (no-alloc в draw) |
| `OverlayView/RecognitionScoreView/AutoFitTextureView` | view-слой TF sample |

### Утилиты `utils/`, `env/`
| Файл | Ключевое |
|---|---|
| `AppConfiguration.java` | `CameraSource{AUTO,EXTERNAL,BUILTIN}=AUTO`, `relateAnythingEnabled=true`, `relateAnythingUseRemote=false`, `RELATE_ANYTHING_URL`, activeModel, processor |
| `PathUtils.java` | поиск файлов в assets/sdcard |
| `env/Logger, Size, BorderedText, ImageUtils, Utils` | базовые хелперы TF sample |

### Прочее `trial/`, `compass/`, `display/`
RTSPActivity (RTSP-вход без транскода), CalibrateActivity (калибровка PDM),
SensorActivity, SpatialActivity, ImageDetectionActivity, DragAndDropActivity;
compass (SOTW-формат); display (Client/Display — внешний экран).

## Тесты
`android/app/src/test/java/.../SmokeTests.java` (14 сценариев, main-runners, без JUnit)
+ `shims/android/{graphics,os,util}` (RectF, Bitmap, Paint, Handler, Looper,
SystemClock с управляемыми часами, Log). Запуск: `android/run_smoke_tests.sh`.

## Flutter (`pointdefence/`)
Не трогать при работе с детекцией; общая карта/plotting UI. Мост — MyFlutterActivity.

## Зависимости
TFLite 2.4.0 (+ GPU delegate), Camera2, Flutter module. Модели YOLO26 в assets
отсутствуют в git (бинарные) — см. docs/wiki/YOLO26-Detection.md про экспорт.
