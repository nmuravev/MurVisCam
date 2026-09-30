# Архитектура

## Конвейер данных (live-детекция)

```
Camera2 (нативный size/FPS/bitrate, без сжатия)
  └─> ImageReader (OnImageAvailableListener)
        └─> acquireNextImage()            ← НЕ acquireLatestImage(): кадры не дропаются
              └─> YuvToRgbConverter → Bitmap (полный размер кадра)
                    └─> DetectorActivity.processImage()   [background thread]
                          ├─> Classifier.recognizeImage(bitmap)      (YOLO26 или YOLOv5)
                          ├─> ObjectPosition/PinholeModel → Z, X, Y (дальность/геопозиция)
                          ├─> DroneMotionEstimator.update(id, rect, Z,X,Y, t)
                          │     → Direction (приближается/удаляется × слева/справа ↑/↓)
                          │     → static volatile motionBannerText/Active/Danger + Toast
                          └─> MultiBoxTracker.trackResults() → OverlayView.draw()
                                ├─ боксы + метки
                                ├─ верхний HUD-баннер направления (красный = danger)
                                └─ метка направления под каждым боксом
```

Флаг `computingDetection` сериализует инференс: если модель ещё считает, кадр
ждёт очереди (не выбрасывается) — см. Cameras.md про требования к потоку.

## Слои

### 1. Камера и UI-хост (`org.tensorflow.lite.examples.detection`)
- **CameraActivity** — базовый класс камеры: перечисление камер, нижняя панель,
  выпадающий список источника видео (`setupCameraSourceSpinner`), список моделей
  (`getModelStrings`, YOLO26 первыми и дефолтными).
- **DetectorActivity** — наследник: загрузка модели через `DetectorFactory`,
  цикл обработки кадров, Red Line, интеграция `DroneMotionEstimator`, Toast-уведомления.
- **CameraConnectionFragment / LegacyCameraConnectionFragment** — Camera2/Camera1.
- **CameraService, MyForegroundService** — фоновый режим.

### 2. Детекция (`tflite/`)
- **Classifier** — интерфейс; вложенный класс `Recognition` (id, title (мутабельный
  через `setTitle`), confidence, location RectF).
- **DetectorFactory** — маршрутизация по имени файла: `yolo26*` → Yolo26EndToEndClassifier,
  иначе → YoloV5Classifier(Detect).
- **Yolo26EndToEndClassifier** — end-to-end head `[1,N,6]` (x,y,w,h,score,class),
  fp16/int8, делегаты CPU/GPU/NNAPI, без NMS.
- **YoloV5Classifier / YoloV5ClassifierDetect** — legacy-бэкенд (postprocess+NMS).

### 3. Направление движения (`motion/`)
- **DroneMotionEstimator** — единственная сохранённая функция RelateAnything:
  оценка траектории сближения дрона с камерой. Geometric core (тренды Z/X/Y по
  окну 1.5 c) + опциональный remote predicate (выключен по умолчанию).
  См. Motion-Direction.md.

### 4. Геооценка (`position/`)
- **PinholeModel, ObjectPosition, WorldCoordinateOffset, LinearInterpolation, DrawView**
  — перевод пиксельного бокса в Z (дальность, м) и X/Y (метры относительно камеры).
  Именно эти значения питают DroneMotionEstimator.

### 5. Оверлей (`customview/`, `tracking/`)
- **MultiBoxTracker** — трекинг боксов (IoU-ассоциация), отрисовка HUD направления.
- **OverlayView, RecognitionScoreView, AutoFitTextureView** — view-слой.

### 6. Конфигурация (`utils/AppConfiguration.java`)
Глобальные статические флаги — см. Configuration.md.

### 7. Flutter-карта (`pointdefence/`)
Отдельное Flutter-приложение (карты, plotting, RTSP-показ). Общается с Android-частью
через `MyFlutterActivity`/плагины. Не затронуто заменой детектора.

## Нити
| Поток | Что делает | Синхронизация |
|---|---|---|
| Main/UI | Spinner камеры/модели, Toast | runOnUiThread |
| background (inference) | processImage → recognizeImage → tracker | `computingDetection` lock |
| camera capture | Camera2 callbacks | — |
| HTTP (relate) | асинхронный POST к companion-сервису | троттл ≥500 мс; только при useRemote=true |

Обмен между inference-потоком и UI-отрисовкой — через `static volatile` поля
`DetectorActivity.motionBanner*` (см. баг-лист: без volatile был гонка).
