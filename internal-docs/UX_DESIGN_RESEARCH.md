# UX Design Research — sber500-companion

*Compiled: 2026-09-24. Focus: UX nomination Sber500 × DISRUPT.*

---

## TL;DR — три главных вывода

1. **Одна цель + персонаж = победа.** Focus Friend (2025 Play winner) — анимированный бобик с одной функцией. Partiful (2024) — один повод собраться. Простота + personality бьёт feature list.
2. **M3 Expressive (май 2025)** — Google наградит, если используешь текущие guidelines. Spring physics в 21 компоненте Compose — бесплатно.
3. **Health = постоянная editorial тема Play Store.** Alignment = самый мощный буст для featuring.

---

## 1. Google Play Award Winners 2024–2025

### Best of 2024
| App | Award | Ключевое |
|-----|-------|---------|
| **Partiful** | Best App | "Beauty of simplicity." Party Genie AI. Один повод, ноль трений |
| **MacroFactor** | Best Essential | Data-first, adaptive algorithm. Precision без шума |
| **Timeleft** | Best Hidden Gem | Distinctive visual identity + сильный onboarding hook |
| **UpStudy** | Best Personal Growth | AI делает работу, интерфейс невидим |
| **Bloom** | Best Indie | Сильная art direction, color-forward identity |

**2024 тема:** "Beauty of simplicity: effortless connectivity, meaningful gatherings, approachable self-improvement."

### Best of 2025
| App | Award | Ключевое |
|-----|-------|---------|
| **Focus Friend** | Best App | Анимированный "Bean" реагирует на сессию фокуса. One feature + character = trophy |
| **Luminar** | Best Multi-Device | AI photo editing. "Intuitive interface that adapts to any screen size." |

**2025 сигнал:** Google явно наградил "digital well-being and utility over pure flash." Один анимированный персонаж с одной задачей победил feature-rich утилиты.

---

## 2. Смежные категории — что делают лучшие

### Health/Wellness (Oura, Calm, Headspace, Daylio)

**Oura (design by Instrument, 2024–2025 redesign):**
- 5 вкладок → 3 (Today, Vitals, My Health). Меньше = лучше.
- **3-level progressive disclosure:** абстрактные кольца → metric cards → exploratory charts. Три глубины для одних данных.
- **Семантический цвет = состояние здоровья.** Зелёный = готов, жёлтый = умеренно, красный = recovery. Цвет сообщает состояние без чтения.
- Short-term → long-term narrative: "как ты спал" → "что меняется за 90 дней."

**Calm:**
- Content IS the UI. Full-screen immersive, минимальный chrome.
- Одно основное действие на экран. Снижает decision fatigue.

**Headspace:**
- Консистентная система иллюстраций (одни персонажи, одни окружения).
- Progress feels measurable: полоски, стрики, прогресс видны.

**Daylio:**
- Icon-only logging. Текст не нужен. 5-second check-in.
- Zero friction — занятые люди реально поддерживают привычку.

### AI Companion Apps (Replika, Wysa, Focus Friend, Woebot)

| Урок | Источник |
|------|---------|
| Эмоциональный дизайн = retention больше, чем фичи | Replika |
| Не-человеческий маскот снижает стигму + создаёт эмоциональную безопасность | Wysa (пингвин) |
| Клиническая эффективность ≠ retention без тепла | Woebot (закрылся 2026, несмотря на Stanford RCT) |
| Один анимированный персонаж, реагирующий на состояние = Play Award | Focus Friend 2025 |

**Для нашего приложения:** рассмотреть анимированный элемент/персонажа, который меняет состояние в зависимости от readiness/сна. Медленно дышит в "спокойный день", активно пульсирует в "энергичный." Lottie + speed control.

### Self-Analytics (Gyroscope, Bearable, Reflectly)

**Gyroscope:**
- Premium quantified-self dashboard. Stat tiles + sparklines + heatmaps.
- Aggregates + interprets — не просто показывает, а рассказывает.

**Bearable:**
- Симптомы + настроение + сон + лекарства в одном. Мощная визуализация данных.
- Справляется с глубиной, не перегружая.

**Reflectly:**
- Conversational AI. "Best friend" тон. Warm gradient palette.
- Reduces blank-page anxiety через prompts.

---

## 3. Material Design 3 — что использовать

### Dynamic Color
- **HCT color space** (Hue–Chroma–Tone): perceptually uniform, лучше HSL.
- 12 color roles: Primary/Secondary/Tertiary/Error + Container + On-Container пары.
- **Tonal elevation в dark theme:** Surface+1...+5, каждый уровень добавляет цветовой overlay вместо тени. Critical для premium dark feel.
- Auto-генерация light/dark из одного seed цвета.

### Typography Scale
```
Display Large  57sp / Light — утреннее приветствие ("Доброе утро")
Headline Large 32sp / Medium — score, ключевые числа
Title Large    22sp / SemiBold — заголовки секций
Body Large     16sp / Regular — текст прогноза
Label Medium   12sp / Medium / ALL CAPS — метки метрик
```
**Тренд 2025:** крупный display type как дизайнерский элемент, не просто информация. Приветствие в Display Large — это момент, а не подпись.

### Shape Tokens
```
ExtraSmall  4dp  — чипы, индикаторы
Small       8dp  — кнопки, маленькие карточки
Medium      12dp — карточки, text fields
Large       16dp — bottom sheets, dialogs
ExtraLarge  28dp — FAB, featured cards
Full        circular — аватар, icon buttons
```

### Motion — Spring Physics
- stiffness + dampingRatio = ощущение физического объекта.
- **21 M3-компонент в Compose уже используют spring по умолчанию** — бесплатно.
- Container Transform (SharedTransitionLayout): card → detail — лучший паттерн для переходов.
- Timing: 300ms = смена контента, 150ms = micro-interactions, 500ms+ = page transitions.

---

## 4. Material 3 Expressive (май 2025)

Не "Material 4" — расширение Material You. Research base: 46 исследований, 18,000+ участников.

**Что нового:**
- 15 новых/обновлённых компонентов (button groups, split buttons, toolbars, loading indicators)
- Spring physics везде — elastic in/out вместо cubic bezier
- Containment patterns: контрастные оттенки внутри rounded containers сигнализируют "эта группа вместе"
- Adaptive components: auto-reflow для phone/tablet/foldable/watch

---

## 5. Google Play Editor's Choice — критерии

### P1 (обязательно)
1. **Актуальный Material Design compliance** — Google явно награждает M3. "Small bonus."
2. **Distinctive visual identity** — узнаваемый в один скриншот. Partiful, Focus Friend, Calm — каждый сразу понятен.
3. **Alignment с editorial calendar** — Health + wellbeing = постоянный Play Store editorial pillar. **Самый большой буст для featuring.**

### P2 (важно)
4. Zero critical bugs / crashes — стабильность = table stakes
5. Accessibility — content descriptions, touch targets ≥48dp, system font scaling support
6. Quality over ratings — editors могут переопределить

### Тема 2024–2025
2024: "Beauty of simplicity — approachable self-improvement, packaged in experiences that spark joy."
2025: Single-purpose + personality beats feature-rich. Digital well-being + utility + emotional resonance = winning formula.

---

## 6. Паттерны, которые делают приложение "premium"

### Loading States
- **Skeleton screens > spinners** — Facebook-паттерн, теперь table stakes. Синхронизированный shimmer на весь экран, не покарточно.
- Tasks >10s → percent progress. Никогда не оставлять пользователя без фидбека.
- **Lottie для branded loading** — vector animations, tiny files, crisp at every density.

```kotlin
// Shimmer
Modifier.shimmerEffect()  // compose-shimmer-skeleton lib

// Lottie
val composition by rememberLottieComposition(
    LottieCompositionSpec.RawRes(R.raw.morning_loading)
)
LottieAnimation(composition = composition, iterations = LottieConstants.IterateForever)
```

### Onboarding
- **Объяснить зачем разрешение нужно ПЕРЕД системным диалогом** — кастомный экран + потом системный. Acceptance rate 2–3× выше.
- Progressive revelation: не показывать все фичи сразу. Разблокировать по мере шагов.
- **First session = один satisfying момент.** Deliver ONE insight до того, как что-то просить.
- Наш онбординг (3 шага) уже следует этому: ценность → HC permission → уведомления.

### Empty States
- Никогда просто текст. "Apps that show clear paths in empty states see feature adoption jump by 80%."
- Lottie animation + одна кнопка.
- **Для первого запуска sber500-companion:** "Данных пока нет. Носи телефон сегодня — завтра утром появится первый прогноз." + анимированная иконка.

### Micro-Interactions
- Button press: scale 0.95x + color shift, 150ms spring
- Checkmark/completion: animated draw-on (не instant). Удовлетворяющее ощущение.
- Metric counter: countup animation, не instant swap. "87" считает вверх от предыдущего значения.
- Haptic feedback: medium impact на completion, light на selection. Не на каждый tap.
- Card swipe: spring return. UI как физические объекты.

```kotlin
// Spring appear
AnimatedVisibility(
    visible = showInsight,
    enter = fadeIn() + slideInVertically(
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 200f)
    )
)

// Плавное обновление метрики
val animatedScore by animateFloatAsState(
    targetValue = readinessScore,
    animationSpec = tween(800, easing = FastOutSlowInEasing)
)

// Hero transition card → detail
SharedTransitionLayout {
    Modifier.sharedElement(rememberSharedContentState("morning-card"), this)
}

// Button press feel
val scale by animateFloatAsState(
    targetValue = if (pressed) 0.95f else 1f,
    animationSpec = spring(stiffness = Spring.StiffnessHigh)
)
Modifier.graphicsLayer { scaleX = scale; scaleY = scale }

// Staggered list entry
LazyColumn {
    itemsIndexed(insights) { index, item ->
        AnimatedVisibility(
            visible = true,
            enter = fadeIn(tween(300, delayMillis = index * 60))
                  + slideInVertically(tween(300, delayMillis = index * 60))
        ) { InsightCard(item) }
    }
}
```

### Navigation
- 3 taps maximum до любой критической функции.
- **Bottom nav: 3 destinations.** Today / История / Настройки. Больше = bloat signal для судей.
- Contextual FAB: меняется по экрану.

### Персонализация — сигналы
- Имя пользователя в ключевых моментах (Display Large) — это момент, не метка
- Personal averages, not population norms: "Твой обычный результат" vs "10 000 шагов норма"
- Contextual reminders triggered by deviation от своей нормы, не по расписанию

---

## 7. Типографика и цвет у лучших

### Шрифты по категориям
| Шрифт | Приложения | Почему работает |
|-------|-----------|----------------|
| Roboto / Roboto Flex | Android system, M3 apps | 51% всех Google Font views. Familiar = trustworthy |
| DM Sans + DM Serif | Wellness, companion apps | Sans для данных, Serif для эмоциональных моментов. Контраст |
| Inter | Productivity (Notion, Linear) | Высокая читаемость малым кеглем |
| Nunito | Casual/friendly apps | Мягкие bubbly letterforms = warmth и approachability |

**Паттерн winner apps:** одна гарнитура + вариативность через weight (300–700). Не смешивать много шрифтов.

### Цвет
- **Oura:** цвет = состояние здоровья (семантический). Зелёный/жёлтый/красный = instant status read.
- **Calm:** тёмно-синие градиенты. Успокоение без клинического холода.
- **Headspace:** яркие, но гармоничные. Primary + tertiary accent для радостных моментов.
- **Наш Primary #6B5CE7:** тепло-фиолетовый — уникально для health apps (все в синем/зелёном). Дифференциатор.

---

## 8. Конкретные рекомендации для sber500-companion

### Приоритет 1 — реализовать до 2026-10-06

**Animated character/element в HomeScreen**
- Небольшой Lottie-элемент (64–96dp) в топе экрана, реагирующий на readiness/сон
- Медленное дыхание в "спокойный день", активная пульсация в "энергичный"
- Меняет скорость анимации через `LottieAnimatable.speed`
- Oura/Focus Friend pattern — emotional design = daily return

**Skeleton loading вместо CircularProgressIndicator**
- `shimmerEffect()` на HomeScreen пока грузятся данные
- Синхронизированный shimmer по всему экрану

**Empty state при первом запуске (до первых данных)**
- Маленький Lottie + "Носи телефон сегодня" + счётчик до утра

**Counter animation на метриках**
- screenMin, sleepMin, unlocks — анимированный countup при первом показе

**Spring animation на кнопке чек-ина**
- scale 0.95f + haptic при нажатии action кнопок

### Приоритет 2 — polish до 2026-10-15

**Staggered entry для карточек HomeScreen**
- Каждая карточка появляется с delay (index * 60ms)

**SharedTransitionLayout**
- Переход morning message card → full screen detail

**Персонализация Display Large**
- Если есть имя/данные: "Доброе утро" в Display Large как первый элемент экрана

**Bottom Nav → 3 вкладки**
- Today (дом), История, Настройки. Убрать всё лишнее.

**Semantic color state**
- Если readiness > 75% → акцент зелёный, 50–75% → жёлтый, <50% → мягкий красный
- Oura-паттерн: цвет ring/chip показывает состояние без чтения

### Типографика (конкретные изменения)
```kotlin
// Утреннее приветствие — это момент
Text(
    "Доброе утро",
    style = MaterialTheme.typography.displayMedium.copy(
        fontWeight = FontWeight.Light
    )
)

// Метрика-заголовок — крупно и boldly
Text(
    "87",
    style = MaterialTheme.typography.displayLarge.copy(
        fontWeight = FontWeight.Bold,
        color = Primary
    )
)
```

---

## 9. Интерактивный документ (онлайн)

Полная версия с таблицами, примерами кода, визуальными образцами:
https://claude.ai/artifact/QAPVY8rvLUTc6TPbzM5YWy

---

*Следующий шаг: реализовать animated element в HomeScreen — это самая высокоимпактная задача из всего списка.*
