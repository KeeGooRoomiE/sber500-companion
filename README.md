<div align="center">

# Companion

**Пассивный трекинг + утренний прогноз на основе твоих данных.**  
Работает, пока ты спишь. Три действия в сутки — и то не обязательных.

[![Download](https://img.shields.io/github/v/release/KeeGooRoomiE/sber500-companion?label=скачать&color=4CAF50)](https://github.com/KeeGooRoomiE/sber500-companion/releases/latest/download/companion-latest.apk)
[![Android](https://img.shields.io/badge/Android-10%2B-3DDC84?logo=android&logoColor=white)](https://github.com/KeeGooRoomiE/sber500-companion/releases/latest)
[![Backend](https://img.shields.io/badge/бэкенд-Go%20%2B%20PostgreSQL-00ADD8?logo=go&logoColor=white)](https://api.94-183-236-169.sslip.io/api/v1/version)

[🌐 Лендинг](https://keegooroomie.github.io/sber500-companion/) · [📊 Live-метрики](https://keegooroomie.github.io/sber500-companion/metrics.html) · [📱 Скачать APK](https://github.com/KeeGooRoomiE/sber500-companion/releases/latest/download/companion-latest.apk)

</div>

---

## Что это

Каждое утро — одна фраза о том, чего ждать от дня. Не «высыпайся и отдыхай», а конкретный прогноз, построенный на паттернах из твоего сна, шагов и экранного времени за последние недели.

Данные собирает WorkManager в фоне — ты не трогаешь приложение. Прогноз строит LLM ночью. Утром читаешь одно предложение.

---

## Как работает

```
Ночь: WorkManager → Health Connect + UsageStats → POST /data/passive
Утро: LLM смотрит на последние 7 дней → одна фраза → push-уведомление
День: три кнопки в шторке уведомлений → «норм / так себе / тяжко»
Неделя: «Разбор недели» — что изменилось и почему
```

**Никаких форм.** Никаких напоминаний заполнить что-то. Данные — твои, наружу не уходят.

---

## Скачать

Актуальный APK всегда по одному адресу:

```
https://github.com/KeeGooRoomiE/sber500-companion/releases/latest/download/companion-latest.apk
```

Android 10+. Разрешить установку из неизвестных источников → поставить.

---

## Что внутри

| | |
|---|---|
| **Утренний прогноз** | Одна фраза на день — на основе сна, активности, экранного времени |
| **Passivе трекинг** | Health Connect + UsageStats. Без ручного ввода |
| **Разбор дня / недели** | LLM-саммари с конкретными паттернами |
| **Чек-ин из шторки** | Три кнопки в уведомлении — открывать приложение не нужно |
| **Переустановка** | История и прогнозы сохраняются — телефон узнаётся по device hash |

---

## Стек

| Слой | Технологии |
|---|---|
| Android | Kotlin 2.0, Jetpack Compose, Hilt, WorkManager, Health Connect, Room |
| Бэкенд | Go 1.22, chi, pgx/v5, slog |
| БД | PostgreSQL 15 |
| LLM | cloud.ru Foundation Models (OpenAI-compatible API) |
| Инфра | Ubuntu 22.04, Caddy (auto TLS), systemd, cloudcore.ru |
| CI/CD | GitHub Actions — APK + Pages + TG-уведомления |

---

## Структура репозитория

```
android/     Kotlin/Compose приложение
backend/     Go API-сервер
web/         GitHub Pages лендинг
deploy/      Caddyfile, systemd unit, deploy.sh
```

---

## Live-данные

Метрики проекта публичны и обновляются в реальном времени:  
→ [keegooroomie.github.io/sber500-companion/metrics.html](https://keegooroomie.github.io/sber500-companion/metrics.html)

---

## Конкурс

Проект подаётся на **Sber500 × DISRUPT**, номинация **«Пользовательский опыт»**.  
Участник: Александр Гусаров (ID 29754).
