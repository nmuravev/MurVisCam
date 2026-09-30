# Направление движения дрона (RelateAnything → только motion-функция)

Из [RelateAnything](https://github.com/Maelic/RelateAnything) в проекте оставлена
**только одна функция**: определение направления движения цели, чтобы понимать
траекторию дрона по мере его приближения к камере. Всё остальное (VLM-атрибуция,
полный граф отношений) вырезано.

## Модель данных

Для каждого трека (`id` из детектора) хранится скользящее окно сэмплов
`(t, Z, X, Y, box-size, centroid)` возрастом ≤ **1500 мс**. По окну считаются
линейные тренды (наклон):

| Ось | Величина | Порог (`EPS_*`) | Смысл |
|---|---|---|---|
| Z (дальность, м) | dZ/dt | 0.08 м/с | <0 → приближается, >0 → удаляется |
| X (боковая, м) | dX/dt | 0.12 м/с | знак → слева/справа |
| Y (вертикаль, м) | dY/dt | 0.12 м/с | выше/ниже (в тексте со стрелками) |
| fallback: размер бокса | d√area/dt | 3 px/с | если Z недоступен (NaN) |
| fallback: центроид | dcx/dcy | 6 px/с | то же для X/Y |

## Enum Direction и HUD-комбинации

```java
STATIONARY          "Стоит"
APPROACHING         "Приближается ↑↑↑"                 // спереди, по траектории на камеру
APPROACHING_LEFT    "Приближается спереди-слева ↙"
APPROACHING_RIGHT   "Приближается спереди-справа ↘"
RECESSION           "Удаляется ↓↓↓"                    // сзади
RECESSION_LEFT      "Удаляется влево ↖"
RECESSION_RIGHT     "Удаляется вправо ↗"
```

`isApproaching()` — все APPROACHING*; `isReceding()` — все RECESSION*.
Вертикальная составляемая дописывается как «· выше» / «· ниже».

## Вывод на экран

1. **Верхний HUD-баннер** (`MultiBoxTracker.draw()`): текст комбинации, красный
   фон при danger (= цель сближается), тёмно-зелёный иначе. Источник — статические
   `DetectorActivity.motionBannerText / Active / Danger` (volatile).
2. **Метка под каждым боксом** — та же строка направления.
3. **Toast-уведомления** (`DetectorActivity.notifyMotion`): мгновенно при смене
   направления трека; напоминание о текущем направлении не чаще 1 раза в 3000 мс;
   для STATIONARY/UNKNOWN Toast не показывается.

## Опциональный remote-режим RelateAnything

По умолчанию **выключен** (`AppConfiguration.relateAnythingUseRemote = false`).
При включении `DroneMotionEstimator` асинхронно (троттл ≥500 мс, таймаут 1500 мс)
POST'ит кадр+боксы на companion-сервис и парсит **только** поле `predicate`:

```json
// запрос
{ "image": "<base64 jpeg>", "boxes": [{"box_id": 7, "xywh": [..]}] }
// ответ
{ "relations": [{"box_id": 7, "predicate": "approaching left"}] }
```

Удалённый предикат перекрывает геометрическую оценку; при недоступности сети всё
продолжает работать офлайн. Серверная обёртка модели (Python) в репозитории
не поставляется — её нужно поднять отдельно (см. Troubleshooting).

## Вызов из конвейера

`DetectorActivity.processImage()`, для каждой детекции выше порога уверенности:

```java
Direction dir = motionEstimator.update(id, rect, Z, X, Y, uptimeMillis);
motionBannerText = estimator.describe(dir);
motionBannerActive = dir != STATIONARY;
motionBannerDanger = dir.isApproaching();
notifyMotion(id, dir, uptimeMillis);
```

Смена камеры/модели → `resetAll()`: окно очищается, HUD гаснет (иначе направление
«залипает» от старых координат новой камеры).

## Тесты

Все комбинации направлений, пороги, троттлинг Toast, resetAll, null-safety покрыты
JVM smoke-тестами — см. [Testing.md](Testing.md).
