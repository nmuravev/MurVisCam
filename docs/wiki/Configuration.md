# Конфигурация (AppConfiguration)

Все флаги — `public static` поля `utils/AppConfiguration.java`. Меняются из UI
или программно; часть сохраняется в SharedPreferences.

## Камера

| Флаг | Тип | Default | Описание |
|---|---|---|---|
| `cameraSource` | enum `CameraSource {AUTO, EXTERNAL, BUILTIN}` | `AUTO` | политика выбора камеры при fallback. Явный выбор из spinner'а перекрывает её и пишет EXTERNAL/BUILTIN по facing выбранной камеры |

## Детекция

| Флаг | Тип | Default | Описание |
|---|---|---|---|
| `activeModel` | String | первый `yolo26*` в assets | имя `.tflite` из assets (YOLO26 первыми в списке) |
| `processor` | CPU/GPU/NNAPI | CPU | TFLite-делегат (`applyProcessor()` в DetectorActivity) |
| мин. уверенность | float (слайдер) | 0.5 | ниже порога детекции не попадают ни в HUD, ни в motion-оценку |

## Направление движения (RelateAnything-motion)

| Флаг | Тип | Default | Описание |
|---|---|---|---|
| `relateAnythingEnabled` | boolean | `true` | главный переключатель HUD направления + Toast. Полностью офлайн, сеть не нужна |
| `relateAnythingUseRemote` | boolean | `false` | опциональный POST predicate на companion-сервис RelateAnything |
| `RELATE_ANYTHING_URL` | String | `http://192.168.1.10:8090/relate` | эндпоинт сервиса (актуален только при useRemote=true) |

Внутренние константы `DroneMotionEstimator`:

```java
MIN_REQUEST_INTERVAL_MS = 500   // троттл remote-запросов
TIMEOUT_MS              = 1500  // таймаут HTTP
MAX_SAMPLE_AGE_MS       = 1500  // окно тренда
EPS_Z  = 0.08  EPS_XY = 0.12    // м/с, геометрия
EPS_SIZE = 3.0 EPS_CENTROID = 6 // px/с, fallback
// Toast-напоминание: >= 3000 мс
```

## Прочее

| Флаг | Описание |
|---|---|
| Red Line (`cutOffY`) | линия отсечения кадра; детекции ниже неё игнорируются |
| `showText`, `showCrop`, `multiWindow` | legacy-флаги отладки TF sample |

## Как менять значения по умолчанию

Поля статические — правятся в одном месте (`AppConfiguration.java`). Не плодите
локальные копии логики: если флаг нужен классификатору/трекеру — читайте его там
напрямую (так уже сделано с `relateAnythingEnabled/UseRemote`).
