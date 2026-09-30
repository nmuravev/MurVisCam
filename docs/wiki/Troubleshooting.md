# Troubleshooting

## Сборка / окружение

| Симптом | Причина | Решение |
|---|---|---|
| `assembleDebug` не запускается локально | нет Android SDK | установить SDK или проверить логику через `android/run_smoke_tests.sh` (SDK не нужен) |
| Компиляция падает на `(float) Math.sqrt(...)` в DroneMotionEstimator | lossy double→float без каста (реальный баг, найденный тестовой сборкой) | каст `(float)` — уже в коде; не «улучшайте» обратно в double-контекст |

## Детекция YOLO26

| Симптом | Причина | Решение |
|---|---|---|
| `Expected YOLO26 end-to-end output [1,N,6]` при старте | модель экспортирована без `end2end=True` | `yolo export model=yolo26n.pt format=tflite imgsz=640 end2end=True` |
| Модель не появляется в списке | имя файла не с префиксом `yolo26` или лежит не в `assets/` | переименовать по конвенции `yolo26<variant>-<size>-<fp16|int8>.tflite` |
| Метки классов пустые/неверные | `labels_custom_drone.txt` не совпадает по порядку с классами обучения | перегенерировать labels из датасета |
| RAM OOM при загрузке | fp16-модель слишком велика для SoC | int8-вариант или yolo26n вместо m |
| GPU-делегат падает на int8 | GPU delegate не поддерживает часть int8-операторов | переключиться на CPU/NNAPI (`applyProcessor`) |

## Камеры

| Симптом | Причина | Решение |
|---|---|---|
| USB-камера не видна в spinner | камера не отдаёт UVC / нет разрешения на USB | проверить `adb shell dumpsys media.camera` (ищем `LENS_FACING_EXTERNAL`), переткнуть кабель |
| После hot-unplug внешней камеры чёрный экран | Camera2 exception при закрытии | приложение делает fallback chooseCamera(); если не помогло — перезапуск активности |
| Разрешение 4K, но FPS 15 вместо 60 | режим сенсора: max-size ≠ max-fps (известная проблема, Cameras.md) | осознанный выбор режима; при приоритете FPS — менять критерий выбора размера |
| Превью «замирает», кадры копятся | инференс не успевает за нативным потоком (кадры специально не дропаются!) | уменьшить модель (n/int8), включить GPU/NNAPI delegate; проверять по логу incoming vs processed |

## Направление движения / HUD

| Симптом | Причина | Решение |
|---|---|---|
| HUD показывает направление сразу после смены камеры | залипание старых сэмплов | исправлено: `resetAll()` на switch; если вернулось — регресс-тест testResetAll |
| Баннер не обновляется между тредами | гонка static-полей | поля `motionBanner*` обязаны оставаться `volatile` |
| «Приближается» на неподвижном дроне | пороги EPS слишком низкие для шумной геооценки | поднять EPS_Z/EPS_XY; сначала воспроизвести в smoke-тесте |
| Toast спамит | сломан троттл | напоминание ≥3000 мс + мгновенно только при СМЕНЕ направления (testShouldNotifyThrottle) |
| Remote predicate не влияет | `relateAnythingUseRemote=false` (дефолт) или сервис недоступен | поднять companion-сервис RelateAnything, задать `RELATE_ANYTHING_URL`; офлайн-геометрика при этом продолжает работать |

## Серверная обёртка RelateAnything (remote-режим)

В репозитории её нет. Минимальный контракт эндпоинта:

```
POST {RELATE_ANYTHING_URL}
  Content-Type: application/json
  { "image": "<base64 JPEG>", "boxes": [{"box_id": "<trackId>", "xywh": [x,y,w,h]}] }
Ответ:
  { "relations": [{"box_id": "<trackId>", "predicate": "approaching left"}] }
```

Требования клиента: ответы быстрее 1500 мс, идемпотентность к троттлу 500 мс.
Для подъёма: форк Maelic/RelateAnything → FastAPI-обёртка над моделью, фильтр
предикатов движения (approaching/receding/left/right).
