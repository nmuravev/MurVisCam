# Drone Detection Map — Wiki

**Drone Detection Map** — наземное сенсорное приложение концепции «Hunter» для
аэронадзора за местоположением: детектирует несанкционированные (враждебные)
дроны, оценивает дальность/геопозицию и направление движения цели.

## Что изменилось в текущей ветке

1. **Аппарат детекции заменён на YOLO26** ([docs.ultralytics.com/ru/models/yolo26](https://docs.ultralytics.com/ru/models/yolo26)):
   end-to-end (без NMS), anchor-free, выход `[1, N, 6]`. Старые YOLOv5-модели
   остались поддержанными (hot-swap).
2. **Из RelateAnything** ([github.com/Maelic/RelateAnything](https://github.com/Maelic/RelateAnything))
   использована только функция определения направления движения — для понимания
   траектории дрона по мере приближения к камере. Работает полностью офлайн
   (геометрия); опциональный companion-сервис отключён по умолчанию.
3. **HUD «сверху/сзади + слева/справа»**: комбинации уведомлений на экране
   (верхний баннер + метка у бокса) и Toast-уведомления.
4. **Явный выбор камеры-источника** из выпадающего списка: внешние USB-UVC и
   встроенные камеры; политика AUTO/EXTERNAL/BUILTIN как fallback.
5. **Поток анализа без потерь**: нативный размер, FPS и bitrate камеры —
   ничего не сжимается и не дропается (`acquireNextImage`, max-разрешение,
   фиксация диапазона FPS, мониторинг drop-rate).

## Страницы wiki

- [Architecture](Architecture.md) — конвейер и модули
- [YOLO26 Detection](YOLO26-Detection.md) — экспорт моделей и инференс
- [Motion Direction](Motion-Direction.md) — траектория/направление, HUD
- [Cameras](Cameras.md) — внешние/встроенные камеры, выбор источника
- [Configuration](Configuration.md) — флаги AppConfiguration
- [Testing](Testing.md) — smoke-тесты
- [Troubleshooting](Troubleshooting.md) — FAQ по поломкам

## Основные экраны приложения

| Экран | Класс | Назначение |
|---|---|---|
| Splash → Main | `SplashActivity`, `MainActivity` | старт, Flutter-карта |
| Детекция | `DetectorActivity` | live-анализ, Red Line, HUD направления |
| Камера-хост | `CameraActivity` | выбор модели/камеры, нижняя панель (spinner) |
| Карта/позиция | `position/*`, Flutter `pointdefence/` | геооценка, plotting |
| RTSP / Calibrate / Image | `trial/*` | вспомогательные режимы |
