# CI/CD runbook

## Workflows

### android-build.yml
- Trigger: push to `android/**` or `.github/workflows/android-build.yml`, or manual dispatch
- Runner: `ubuntu-latest`
- Key steps: setup-java v5, setup-gradle v4 (gradle 8.11.1), SDK setup via sdkmanager, Gradle assembleDebug
- Artifact: `companion-debug-{sha}`, 30-day retention
- TG notification: always (success ✅ / failure ❌)

### pages.yml
- Trigger: push to `web/**`, manual dispatch
- Deploys `web/` folder to GitHub Pages
- TG notification with page URL on success

## Build failure history

### 2026-09-24 — YAML syntax error (fixed in 9960965)
- **Symptom:** `android-build.yml` failed at TG notification step, YAML parse error line 81
- **Cause:** Multiline bash string with `\n` inside YAML `run: |` block
- **Fix:** Rewrote MSG as single concatenated line

### 2026-09-24 — Missing hilt/ksp plugins (fixed in 2757a59)
- **Symptom:** "No Gradle build results detected", build exit 1
- **Cause:** Root `build.gradle.kts` missing `alias(libs.plugins.hilt) apply false` and `alias(libs.plugins.ksp) apply false` — Gradle couldn't resolve plugin classpaths for `:app` submodule
- **Fix:** Added both plugins to root `build.gradle.kts`

### 2026-09-24 — android-actions/setup-android@v3 fails (fixed, current)
- **Symptom:** `sdkmanager` exit code 1, "Warning: Failed to find package 'tools'"
- **Cause:** The `tools` package was removed from Android SDK; `setup-android@v3` tries to install it
- **Fix:** Removed `setup-android` action entirely. Replaced with inline step:
  ```yaml
  - name: Setup Android SDK
    working-directory: android
    run: |
      echo "sdk.dir=$ANDROID_HOME" > local.properties
      $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --install \
        "platforms;android-35" "build-tools;35.0.0" 2>&1 | tail -10
  ```
  GitHub runners have Android SDK pre-installed at `$ANDROID_HOME`

## Pending / known warnings

| Warning | Status | Fix |
|---------|--------|-----|
| Node.js 20 deprecated (actions/checkout@v4) | warning only, non-breaking | checkout@v5 when released |
| setup-java v4 deprecated | fixed → v5 | done |
| ubuntu-latest migrates to Ubuntu 26 (2026-10-19) | future | monitor |
| `gradle-wrapper.jar` missing | intentional | using `gradle` directly via setup-gradle |

## SDK / Gradle versions

| Component | Version |
|-----------|---------|
| Gradle | 8.11.1 |
| AGP | 8.7.3 |
| Kotlin | 2.0.21 |
| compileSdk / targetSdk | 35 |
| minSdk | 29 |
| JDK | 17 (temurin) |

## Release workflow

**Файл:** `.github/workflows/release.yml`  
**Триггер:** пуш тега `v*` — только вручную из чата командой `git tag vX.Y && git push origin vX.Y`  
**НЕ** триггерится при каждом билде android/ — только явный тег.

**Что делает:**
1. Собирает debug APK
2. Создаёт публичный GitHub Release с именем тега
3. Прикрепляет `companion-vX.Y.apk` — доступен для скачивания без авторизации
4. Шлёт TG-нотификацию со ссылкой на Release

**Создать релиз из чата:** сказать "создай релиз vX.Y" — Claude запустит `git tag + git push origin tag`.

**История релизов:**

| Тег | Дата | Что |
|-----|------|-----|
| v0.4 | 2026-09-24 | Первый публичный релиз для ручного тестирования |

## Deployment (backend)

```bash
cd deploy/
./deploy.sh          # cross-compile Go → scp → run migrations → restart systemd
```

Requires: `~/.ssh/config` entry for server, `DEPLOY_HOST` env var, server has `/opt/companion/.env`.

## Secrets in GitHub Actions

| Secret name | Description |
|-------------|-------------|
| `TG_BOT_TOKEN` | Telegram bot token for CI notifications |
| `TG_CHAT_ID` | Telegram chat ID (398066304) |

Server secrets live in `/opt/companion/.env` on cloudcore.ru VPS — NOT in GitHub.
