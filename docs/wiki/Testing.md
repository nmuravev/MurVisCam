# Тесты

## Smoke-тесты (JVM, без Android SDK)

Запуск:

```bash
cd android && ./run_smoke_tests.sh   # exit!=0 при падении любого теста
```

Скрипт компилирует чистым `javac` подмножество prod-кода (`AppConfiguration`,
`Classifier`, `DroneMotionEstimator`) вместе с JVM-шимками android.* классов и
выполняет `SmokeTests.main`. Текущий статус: **14 passed / 0 failed**.

Расположение:

```
android/app/src/test/java/
├── org/tensorflow/lite/examples/detection/SmokeTests.java   # 14 тестов
└── shims/android/{graphics,os,util}/                          # RectF, Bitmap, Paint,
                                                               # Handler, Looper, SystemClock
                                                               # (управляемые часы), Log
```

### Покрытые сценарии (имена тестов)

| Тест | Проверяет |
|---|---|
| testApproachingCenter | APPROACHING + состояние HUD + isApproaching() |
| testApproachingLeftRightCombos | комбинации «спереди-слева/справа» |
| testRecessionAndVertical | RECESSION («сзади») + вертикаль «выше» в describe() |
| testGeometricFallback | fallback по размеру бокса при Z=NaN |
| testStationaryBelowEps | нет фантомных тревог на порогах EPS |
| testStaleSamplesPruned | усечение окна сэмплов старше 1500 мс |
| testShouldNotifyThrottle | Toast: мгновенно при смене направления, напоминание ≥3 c |
| testResetAll | сброс при смене камеры: одиночный кадр ≠ «приближается» |
| testNullArgsNoCrash | null-safe регрессии (найденные ранее баги) |
| testSlopeMath | математика наклона (публичный slopeChannel) |
| testDescribeArrows | строки HUD со стрелками ↑↑↑ ↙ ↘ ↖ ↗ ↓↓↓ |
| testRecognitionTitleMutable | Recognition.setTitle (post-processing меток) |
| testAppConfigDefaults | дефолты: AUTO / HUD on / remote off |
| testMultiTrackIsolation | независимость двух треков |

### Как добавить тест

1. В `SmokeTests.java`: новый `private static void testXxx()` + регистрация
   `run("testXxx", SmokeTests::testXxx)` в `main`.
2. Если prod-код трогает новые android.* API — расширьте шим (только то, что реально
   вызывается; шимы не должны менять семантику).
3. Prod-классы для тестов компилируются напрямую из `src/main/java` — следите, чтобы
   тестируемый код не тянул зависимости вне списка javac в скрипте.

## CI

Рекомендуемый шаг (не требует образа Android):

```yaml
- run: cd android && ./run_smoke_tests.sh
```

Полная сборка (`./gradlew assembleDebug`) требует Android SDK и выполняется на
машине разработчика/в Android-образе CI.

## Ручное QA (устройство)

Чек-лист после изменений детекции/камеры:
- [ ] YOLO26-модель выбрана по умолчанию, детекции появляются над Red Line;
- [ ] spinner камеры переключает внешнюю/встроенную без краша, HUD гаснет после switch;
- [ ] дрон на камеру → «Приближается…» красным баннером + Toast ≤1 c после смены направления;
- [ ] лог drop-rate: processed ≈ incoming (иначе см. Troubleshooting);
- [ ] FPS превью не проседает относительно нативного режима камеры.
