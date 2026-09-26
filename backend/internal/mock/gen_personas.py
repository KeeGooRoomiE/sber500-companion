# Regenerates personas.json — test weeks for checking prompts (see mock.go).
# Run from anywhere: python3 backend/internal/mock/gen_personas.py
# Hourly series are synthesised from the daily totals; `note` is what really happened
# (shown next to the model's answer in eval, never seeded into the database).
import json, os, random, re
random.seed(29754)
HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, '../../../internal-docs/dev/mock_test_data_week.json')
OUT = os.path.join(HERE, 'personas.json')

def hm(s): h, m = map(int, s.split(':')); return h * 60 + m

def hourly(total, first, last, weights=None, peak=None):
    """Spread `total` over the awake hours [first..last]; weights: {hour: factor}."""
    f = hm(first) // 60
    l = hm(last) // 60
    hours = list(range(f, 24)) if l < f else list(range(f, l + 1))
    if l < f:
        hours += list(range(0, l + 1))
    w = {h: 1.0 for h in hours}
    for h, k in (weights or {}).items():
        if h in w:
            w[h] *= k
    s = sum(w.values())
    out = [0] * 24
    acc = 0.0
    for h in hours:
        acc += total * w[h] / s
    # largest remainder rounding
    raw = {h: total * w[h] / s for h in hours}
    for h in hours:
        out[h] = int(raw[h])
    rest = total - sum(out)
    for h in sorted(hours, key=lambda h: raw[h] - int(raw[h]), reverse=True)[:rest]:
        out[h] += 1
    return out

def screen_hourly(total, unl):
    s = sum(unl) or 1
    raw = [total * u / s for u in unl]
    out = [min(60, int(x)) for x in raw]
    rest = total - sum(out)
    i = 0
    order = sorted(range(24), key=lambda h: raw[h] - int(raw[h]), reverse=True)
    while rest > 0 and i < 200:
        h = order[i % 24]
        if unl[h] > 0 and out[h] < 60:
            out[h] += 1; rest -= 1
        i += 1
    return out

def day(offset, *, screen, unlocks, first, last, apps, sleep=None, bed=None, wake=None,
        steps=None, feel=None, tags=None, weights=None, note=None):
    u = hourly(unlocks, first, last, weights)
    d = {"offset": offset, "screen_min": screen, "unlocks": unlocks,
         "first_unlock": first, "last_unlock": last,
         "top_apps": [{"package": p, "minutes": m} for p, m in apps],
         "hourly_unlocks": u, "hourly_screen": screen_hourly(screen, u)}
    if sleep is not None: d["sleep_min"] = sleep
    if bed: d["bedtime"] = bed
    if wake: d["wakeup"] = wake
    if steps is not None: d["steps"] = steps
    if feel: d["feel"] = feel
    if tags: d["tags"] = tags
    if note: d["note"] = note
    return d

personas = []

# 1. The documented mock week (internal-docs/dev/mock_test_data_week.json)
src = json.load(open(SRC))
days = []
for i, x in enumerate(src["days"]):
    first = x["first_unlock"]
    if i == len(src["days"]) - 1:
        first = "05:48"  # the note says «проснулся рано, но не встал — лежал с телефоном»; source had 06:51
    days.append(day(i - len(src["days"]), screen=x["screen_min"], unlocks=x["unlocks"],
        first=first, last=x["last_unlock"],
        apps=[(a["package_name"], a["minutes"]) for a in x["top_apps"]],
        sleep=x.get("sleep_min"), bed=x.get("bedtime"), wake=x.get("wakeup"), steps=x.get("steps"),
        feel=x.get("feel"), note=x.get("note")))
personas.append({"id": "mock_week", "title": "Неделя из mock_test_data_week.json: дедлайн, провал в среду, восстановление",
    "profile": {"work_place": "В офисе", "wearable": "Да, каждый день", "goal": "Лучше спать", "tone": "Мягко, с поддержкой"},
    "days": days})

# 2. Calls in the morning (interview: «с утра 15 звонков — через 2 часа настроение не очень»)
d = []
for off in range(-7, -1):
    d.append(day(off, screen=170 + random.randint(-15, 15), unlocks=45 + random.randint(-5, 5),
        first="07:20", last="23:10",
        apps=[("com.slack", 55), ("org.telegram.messenger", 35), ("com.google.android.dialer", 15), ("ru.yandex.taxi", 5)],
        sleep=420 + random.randint(-20, 20), bed="23:25", wake="07:05", steps=6000 + random.randint(-800, 800),
        feel=random.choice(["ok", "ok", "meh"])))
d.append(day(-1, screen=235, unlocks=88, first="07:02", last="23:40",
    apps=[("com.google.android.dialer", 95), ("com.slack", 70), ("org.telegram.messenger", 40), ("us.zoom.videomeetings", 30)],
    sleep=400, bed="23:40", wake="06:50", steps=3900, feel="hard", tags=["Работа"],
    weights={7: 2, 8: 4, 9: 5, 10: 5, 11: 4, 12: 2},
    note="С 8 до 12 подряд звонки клиентам, к обеду выжата; вечером ничего не хотелось"))
personas.append({"id": "mock_calls", "title": "Менеджер: вчера утро звонков и Slack",
    "profile": {"work_place": "В офисе", "triggers": "Работа и звонки", "wearable": "Иногда", "tone": "Коротко и по делу"},
    "days": d})

# 3. Night owl without a watch: no sleep data, phone after 1 am yesterday
d = []
for off in range(-7, -1):
    d.append(day(off, screen=260 + random.randint(-20, 20), unlocks=70 + random.randint(-6, 6),
        first="09:10", last="23:55",
        apps=[("com.google.android.youtube", 70), ("com.zhiliaoapp.musically", 50), ("org.telegram.messenger", 40), ("com.vk.vkcompose", 20)],
        feel=random.choice(["ok", "meh"])))
d.append(day(-1, screen=345, unlocks=82, first="09:40", last="01:50",
    apps=[("com.zhiliaoapp.musically", 110), ("com.google.android.youtube", 85), ("org.telegram.messenger", 35), ("com.vk.vkcompose", 25)],
    weights={22: 2, 23: 3, 0: 3, 1: 2}, feel="meh",
    note="Залипла в TikTok после полуночи, уснула около двух; часов нет, сна в данных нет"))
personas.append({"id": "mock_nightowl", "title": "Сова без часов: вчера телефон до 01:50, сна в данных нет",
    "profile": {"work_place": "Из дома", "bedtime": "После полуночи", "wearable": "Нет", "goal": "Меньше телефона"},
    "days": d})

# 4. A calm good day: slept long, first hour without the phone, little screen
d = []
for off in range(-7, -1):
    d.append(day(off, screen=205 + random.randint(-20, 20), unlocks=55 + random.randint(-6, 6),
        first="07:10", last="23:20",
        apps=[("org.telegram.messenger", 50), ("com.instagram.android", 45), ("com.google.android.youtube", 30), ("ru.sberbankmobile", 6)],
        sleep=410 + random.randint(-25, 25), bed="23:40", wake="07:00", steps=6500 + random.randint(-900, 900),
        feel=random.choice(["ok", "meh"])))
d.append(day(-1, screen=115, unlocks=34, first="08:05", last="22:30",
    apps=[("org.telegram.messenger", 30), ("ru.yandex.yandexmaps", 20), ("com.spotify.music", 18), ("com.instagram.android", 12)],
    sleep=505, bed="22:50", wake="07:15", steps=11200, feel="ok", tags=["Прогулка"],
    note="Выходной без спешки: выспался, час гулял без телефона, вечером музыка"))
personas.append({"id": "mock_calm", "title": "Спокойный день: выспался, утро без телефона, мало экрана",
    "profile": {"work_place": "По-разному", "wearable": "Да, каждый день", "goal": "Меньше стресса", "tone": "Мягко, с поддержкой"},
    "days": d})

# 5. Remote worker whose Telegram is work (picked in «какие приложения рабочие»)
d = []
for off in range(-7, -1):
    d.append(day(off, screen=230 + random.randint(-20, 20), unlocks=60 + random.randint(-6, 6),
        first="08:30", last="23:30",
        apps=[("org.telegram.messenger", 80), ("com.google.android.youtube", 40), ("com.bitrix24.android", 25), ("com.instagram.android", 20)],
        sleep=440 + random.randint(-20, 20), bed="23:50", wake="08:15", steps=4000 + random.randint(-800, 800),
        feel=random.choice(["ok", "meh"])))
d.append(day(-1, screen=320, unlocks=104, first="07:14", last="00:45",
    apps=[("org.telegram.messenger", 150), ("com.bitrix24.android", 60), ("com.google.android.youtube", 30), ("com.instagram.android", 15)],
    sleep=375, bed="00:55", wake="07:10", steps=2100, feel="hard",
    note="Чаты с командой с утра до ночи, в Telegram по работе; лёг поздно, встал по будильнику",
    weights={9: 2, 10: 3, 11: 3, 12: 2, 13: 2, 14: 3, 15: 3, 16: 3, 17: 2, 23: 2, 0: 2}))
personas.append({"id": "mock_remote", "title": "Удалёнщик: Telegram рабочий, вчера чаты весь день и до ночи",
    "profile": {"work_place": "Из дома", "triggers": "Работа и звонки, Недосып", "wearable": "Да, каждый день",
                "work_apps": "org.telegram.messenger,com.bitrix24.android", "tone": "Коротко и по делу"},
    "days": d})

# 6. Day 0: just installed, only two days of history
d = [
    day(-2, screen=190, unlocks=50, first="07:30", last="23:15",
        apps=[("org.telegram.messenger", 45), ("com.instagram.android", 40), ("ru.ozon.app.android", 15)],
        sleep=430, bed="23:30", wake="07:10", steps=5200),
    day(-1, screen=240, unlocks=66, first="07:05", last="00:20",
        apps=[("com.instagram.android", 70), ("org.telegram.messenger", 50), ("com.google.android.youtube", 30)],
        sleep=380, bed="00:30", wake="06:50", steps=3400,
        note="Первый день с приложением, ничего особенного, лёг поздно"),
]
personas.append({"id": "mock_newbie", "title": "День 0: только установил, два дня истории, без чек-инов",
    "profile": {}, "days": d})

# Compact output: number arrays and apps on one line each
s = json.dumps(personas, ensure_ascii=False, indent=2)
s = re.sub(r'\[\s+((?:-?\d+,\s+)*-?\d+)\s+\]', lambda m: '[' + re.sub(r'\s+', '', m.group(1)).replace(',', ', ') + ']', s)
s = re.sub(r'\{\s+"package": ("[^"]+"),\s+"minutes": (\d+)\s+\}', r'{"package": \1, "minutes": \2}', s)
open(OUT, 'w').write(s + "\n")
print("ok", [p["id"] for p in personas])
