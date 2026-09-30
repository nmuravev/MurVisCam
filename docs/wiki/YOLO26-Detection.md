# YOLO26 — аппарат детекции

Документация модели: <https://docs.ultralytics.com/ru/models/yolo26>

## Почему YOLO26

YOLO26 — end-to-end модель Ultralytics: **без якорей и без NMS** на этапе
post-processing. Модель сама подавляет дубликаты, поэтому инференс на устройстве
детерминирован, быстрее и не требует runtime-NMS (в отличие от YOLOv5-бэкенда).

## Экспорт модели для Android

```bash
pip install ultralytics
yolo export model=yolo26n.pt format=tflite imgsz=640 end2end=True
# варианты: yolo26n / yolo26s / yolo26m; int8-квантизация:
yolo export model=yolo26n.pt format=tflite imgsz=640 end2end=True int8=True
```

Именование файлов (обязательно! `DetectorFactory` маршрутизирует по префиксу):

```
yolo26<variant>-<imgsz>-fp16.tflite     # напр. yolo26n-640-fp16.tflite
yolo26<variant>-<imgsz>-int8.tflite     # напр. yolo26s-640-int8.tflite
```

Положить файл в `android/app/src/main/assets/`. Метки классов:
`assets/labels_custom_drone.txt` (порядок строк = индекс класса в выходе модели).

После помещения модели в assets она автоматически появится в выпадающем списке
моделей **первой** и станет значением по умолчанию (`CameraActivity.getModelStrings`).

## Формат входа/выхода

| | Форма | Тип | Примечание |
|---|---|---|---|
| input | `[1, H, W, 3]` | fp16/int8 | uint8-Bitmap → нормализация 0..1 |
| output | `[1, N, 6]` | fp16/int8 | N ≈ 300 предложений; колонки: `cx, cy, w, h, score, class_id` (координаты уже в пикселях входа) |

Валидация формы выхода выполняется при загрузке: если тензор не `[1,N,6]`,
бросается `IOException` с подсказкой реэкспортировать модель с `end2end=True`.

## Парсинг (Yolo26EndToEndClassifier.recognizeImage)

Оптимизированный путь (один проход по N строкам):
1. `System.arraycopy` сырых байтов выхода → `float[]` (fp16→float конверсия один раз);
2. фильтр по порогу уверенности (слайдер UI, `minimumConfidence`);
3. для каждого бокса — только **top-score класс** (полный softmax по всем классам
   не считается);
4. сортировка по score (стабильная), ограничение `MAX_RESULTS`;
5. маппинг координат модели → экран через `cropToFrameTransform` (потом в DetectorActivity).

NMS нет — это свойство end-to-end головы YOLO26.

## Делегаты

Переключаются в UI (Processor): CPU / GPU (Delegate) / NNAPI. Применяются к обеим
модельным веткам через `DetectorActivity.applyProcessor()`. Для int8-моделей GPU
может быть недоступен — используется CPU-путь.

## Обратная совместимость

Старые модели `yolov5*.tflite` продолжают работать через прежний бэкенд
(`YoloV5ClassifierDetect`, выход 3×anchor-grid + NMS). Удаление YOLOv5 из assets
не требуется.

## Частые ошибки

| Симптом | Причина | Решение |
|---|---|---|
| `Expected YOLO26 end-to-end output [1,N,6]` | экспортировали без `end2end=True` | переэкспортировать |
| Детекции есть, метки пустые | рассинхрон `labels_custom_drone.txt` с классами модели | пересобрать labels в порядке training-класса |
| Интенсивный нагрев/троттлинг | fp16 на слабом SoC | взять `-int8` вариант или smaller variant |
