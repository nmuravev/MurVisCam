# Drone Detection Map — Документация

Полная документация проекта. Все документы на русском языке.

## Структура документации (Wiki)

| Файл | Содержание |
|---|---|
| [wiki/Home.md](wiki/Home.md) | Главная страница wiki: обзор, карта документов |
| [wiki/Architecture.md](wiki/Architecture.md) | Архитектура приложения, конвейер данных, модули |
| [wiki/YOLO26-Detection.md](wiki/YOLO26-Detection.md) | Аппарат детекции YOLO26: экспорт моделей, формат, запуск |
| [wiki/Motion-Direction.md](wiki/Motion-Direction.md) | Определение направления движения дрона (RelateAnything-функция), HUD и уведомления |
| [wiki/Cameras.md](wiki/Cameras.md) | Внешние (USB-UVC) и встроенные камеры, выбор источника потока, требования к качеству потока |
| [wiki/Configuration.md](wiki/Configuration.md) | Все конфигурационные флаги (`AppConfiguration`) |
| [wiki/Testing.md](wiki/Testing.md) | Smoke-тесты, запуск, CI |
| [wiki/Troubleshooting.md](wiki/Troubleshooting.md) | Известные проблемы и решения |
| [CODEBASE.md](CODEBASE.md) | Гид по кодовой базе: все классы и их ответственность |
| [AI-IDE-INSTRUCTIONS.md](AI-IDE-INSTRUCTIONS.md) | Инструкция для ИИ-агентов, работающих в IDE с этим репозиторием |

## Быстрый старт

```bash
cd android && ./gradlew assembleDebug        # сборка APK (нужен Android SDK)
./run_smoke_tests.sh                          # JVM smoke-тесты (без SDK)
```

Модели YOLO26 экспортируются отдельно (см. [YOLO26-Detection.md](YOLO26-Detection.md))
и кладутся в `android/app/src/main/assets/`.

## Для пользователей

Приложение: обнаружение дронов на Android 9+ с hot-swappable моделями,
оценкой дальности/геопозиции, выбором камеры анализа (внешняя USB-UVC /
встроенная) и HUD направления движения цели («приближается спереди-слева» и т.п.).

## Для разработчиков

Начните с [CODEBASE.md](CODEBASE.md), затем [wiki/Architecture.md](wiki/Architecture.md).
Критические инварианты кода перечислены в [AI-IDE-INSTRUCTIONS.md](AI-IDE-INSTRUCTIONS.md)
(раздел «Non-negotiable rules») — они обязательны при любом изменении кода.
