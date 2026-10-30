#!/usr/bin/env python3
"""
Варианты раскладки главного экрана Companion: одни и те же пять элементов (компаньон-орб, прогноз,
метрики, история, чек-ин), разная композиция. Блоки, стили и орб берутся из web/screens/build.py и
собранного home.html (1 CSS px = 1 dp), поэтому тексты настоящие, русские, без искажений.

  python3 home_variants.py            -> data/home/variants/*.html, *.png, viewer.html
Нужен установленный Google Chrome (рендер в headless).
"""
import importlib.util, re, subprocess, sys, html as H
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
OUT = HERE / "data" / "home" / "variants"
CHROME = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"

spec = importlib.util.spec_from_file_location("b", ROOT / "web/screens/build.py")
B = importlib.util.module_from_spec(spec)
spec.loader.exec_module(B)

BASE = (ROOT / "web/screens/home.html").read_text(encoding="utf-8")
CSS = re.search(r"<style>(.*?)</style>", BASE, re.S).group(1)
CSS = CSS.replace("url(fonts/", f"url(file://{ROOT}/web/screens/fonts/")

EXTRA_CSS = """
.obox{position:relative;flex:none}
.cap{font-size:12px;font-weight:600;letter-spacing:.8px;color:#6E6A85;line-height:normal}
.fc{font-size:17px;line-height:26px;color:#1A1525}
.links{display:flex;gap:18px;font-size:14px;font-weight:600;color:#6B5CE7;line-height:normal}
.chips{display:flex;gap:8px}
.chip{flex:1;border-radius:14px;background:#fff;padding:9px 12px;display:flex;flex-direction:column;gap:1px;box-shadow:0 1px 2px rgba(0,0,0,.14),0 1px 3px 1px rgba(0,0,0,.06)}
.chip .v{font-size:15px;line-height:20px;font-weight:700;color:var(--lvl)}
.chip .l{font-size:10px;font-weight:500;letter-spacing:.6px;color:#6E6A85;line-height:normal}
.sec{display:flex;align-items:center;justify-content:space-between}
.sec .t{font-size:14px;font-weight:500;line-height:20px}
.sec .a{font-size:13px;font-weight:600;color:#6B5CE7}
.hrow{display:flex;gap:10px;overflow:hidden;margin-right:-20px}
.hcard{flex:none;width:236px;border-radius:16px;background:#fff;padding:12px 14px;display:flex;flex-direction:column;gap:6px;box-shadow:0 1px 2px rgba(0,0,0,.1)}
.hcard .d{font-size:11px;font-weight:600;color:#6E6A85;letter-spacing:.5px}
.hcard .t{font-size:13px;line-height:18px;color:#1A1525}
.mark{font-size:11px;font-weight:600;border-radius:10px;padding:2px 8px;align-self:flex-start;background:#EDE9FF;color:#6B5CE7;line-height:16px}
.mark.no{background:#F3EEEA;color:#8A6D3B}
.lcard{border-radius:20px;background:#fff;padding:14px 16px;display:flex;flex-direction:column}
.lrow{display:flex;align-items:center;gap:10px;padding:10px 0;border-top:1px solid #EEECFB}
.lrow:first-of-type{border-top:0}
.lrow .d{width:54px;font-size:11px;font-weight:600;color:#6E6A85;letter-spacing:.4px;flex:none}
.lrow .t{flex:1;font-size:13px;line-height:18px}
.strip{display:flex;justify-content:space-between;padding:0 2px}
.day{width:44px;display:flex;flex-direction:column;align-items:center;gap:5px;font-size:11px;font-weight:500;color:#6E6A85}
.day i{width:30px;height:30px;border-radius:50%;background:#EDE9FF;display:flex;align-items:center;justify-content:center;font-style:normal;font-size:16px;font-family:"Noto Color Emoji"}
.day.today i{background:#6B5CE7;box-shadow:0 0 0 3px #DDD9F8;color:#fff;font-family:Roboto;font-size:13px;font-weight:700}
.day.future i{background:transparent;border:1.5px dashed #DDD9F8}
.day.today{color:#1A1525;font-weight:700}
.sheet{position:absolute;left:0;right:0;bottom:0;height:200px;border-radius:24px 24px 0 0;background:#fff;box-shadow:0 -4px 22px rgba(40,28,90,.14);padding:10px 20px;z-index:2}
.handle{width:36px;height:4px;border-radius:2px;background:#DDD9F8;margin:0 auto 12px}
.slim{border-radius:20px;background:#fff;padding:14px 16px;display:flex;align-items:center;gap:12px}
.slim .q{font-size:14px;font-weight:500;flex:1}
.mini{width:44px;height:40px;border-radius:12px;background:#F0EEFF;display:flex;align-items:center;justify-content:center;font-size:22px;font-family:"Noto Color Emoji"}
.line1{border-radius:16px;background:#fff;padding:12px 14px;display:flex;align-items:center;justify-content:space-between;font-size:13px;color:#1A1525;box-shadow:0 1px 2px rgba(0,0,0,.1)}
.line1 b{font-weight:700} .line1 .m{color:#6E6A85} .line1 .arr{color:#6B5CE7;font-weight:700}
"""

HIST = [("ВЧЕРА, ПТ", "Спокойный день по экрану, сон ближе к норме.", "Совпало", ""),
        ("ЧТ", "Плотное утро: 51 разблокировка до полудня при обычных 15.", "Совпало", ""),
        ("СР", "Поздний вечер с телефоном, утро, скорее всего, медленнее.", "Не совсем", "no")]
STATS = list(B.MOCK["stats"])
FORECAST = B.MOCK["forecast"]
GREET = B.MOCK["greeting"]
FEELS = [("😊", "Отлично"), ("😐", "Нормально"), ("😮‍💨", "Тяжело")]


def feels():
    return '<div class="feels">' + "".join(f'<div class="feel"><span class="e emoji">{e}</span><span class="l">{l}</span></div>' for e, l in FEELS) + "</div>"


def checkin_card(title="Как прошёл день?"):
    return f'<div class="card checkin"><div class="title-s">{title}</div>{feels()}</div>'


def tiles():
    return '<div class="stats">' + "".join(B.stat_tile(*s) for s in STATS) + "</div>"


def chips(neutral=False):
    cols = {"good": B.C["good"], "warn": B.C["warn"], "bad": B.C["bad"]}
    if neutral:
        cols = {k: "#4A4370" for k in cols}
    return '<div class="chips">' + "".join(f'<div class="chip" style="--lvl:{cols[lv]}"><div class="v">{v}</div><div class="l">{lab.upper()}</div></div>' for lab, v, lv in STATS) + "</div>"


def hero(extra="", text_style=""):
    return (f'<div class="hero" id="hero" style="position:relative">{extra}<div class="label">Прогноз на сегодня</div><div class="text" style="{text_style}">{FORECAST}</div>'
            '<div class="why">Почему такой прогноз <span>▾</span></div><div class="more">Хочу ещё ✦</div></div>')


def timeline_card():
    return ('<div class="card"><div class="row"><div class="title-s">Сегодня по часам</div><div class="label-s">экран, минут в час</div></div>'
            f'{B.timeline_svg()}<div class="hours"><span>0</span><span>6</span><span>12</span><span>18</span><span>24</span></div>'
            '<div class="pills"><div class="pill filled">Разбор вчера</div><div class="pill">Разбор сегодня</div></div></div>')


def hist_cards():
    return '<div class="hrow">' + "".join(f'<div class="hcard"><div class="d">{d}</div><div class="t">{t}</div><span class="mark {c}">{m}</span></div>' for d, t, m, c in HIST) + "</div>"


def hist_list(n=3):
    return '<div class="lcard">' + "".join(f'<div class="lrow"><div class="d">{d}</div><div class="t">{t}</div><span class="mark {c}">{m}</span></div>' for d, t, m, c in HIST[:n]) + "</div>"


def orb(size):
    return f'<div class="obox" style="width:{size}px;height:{size}px">{B.orb_svg(size)}</div>'


def screen(body, content_style="", extra="", clock=None):
    return f'''<!doctype html><html lang="ru"><head><meta charset="utf-8"><meta name="viewport" content="width=412"><style>{CSS}{EXTRA_CSS}</style></head><body>
<div class="screen" id="screen">{B.waves_svg()}
<div class="status"><span>{clock or B.MOCK["clock"]}</span><span class="icons">{B.status_icons()}</span></div><div class="camera"></div>
<div class="content" style="{content_style}">{body}</div>{extra}<div class="gesture"></div></div>
<script>const h=document.getElementById('hero');if(h)h.style.setProperty('--angle',(90+Math.atan2(h.offsetHeight,h.offsetWidth)*180/Math.PI)+'deg');</script></body></html>'''


def v0():
    return screen(f'<div class="top"><div class="greet">{GREET}</div><div class="orb-box">{B.orb_svg()}</div></div>{hero()}{tiles()}{timeline_card()}{checkin_card()}{hist_list(2)}')


def v1():
    body = (f'<div class="greet" style="font-size:28px;line-height:34px;padding-top:4px">{GREET}</div>'
            f'<div style="display:flex;flex-direction:column;align-items:center;gap:8px;text-align:center">{orb(170)}'
            f'<div class="cap" style="margin-top:14px">ПРОГНОЗ НА СЕГОДНЯ</div><div class="fc" style="max-width:350px">{FORECAST}</div>'
            '<div class="links"><span>Почему такой прогноз ▾</span><span>Хочу ещё ✦</span></div></div>'
            f'{chips()}{checkin_card()}<div class="sec"><div class="t">Прошлые прогнозы</div><div class="a">Все →</div></div>{hist_cards()}')
    return screen(body, "gap:14px")


def v2():
    o = f'<div class="obox" style="position:absolute;right:10px;top:-34px;width:112px;height:112px">{B.orb_svg(112)}</div>'
    body = (f'<div class="greet" style="font-size:28px;line-height:34px;padding-top:4px">{GREET}</div>'
            + hero(o, "padding-right:112px;margin-top:0")
            + f'{tiles()}{checkin_card()}<div class="sec"><div class="t">История прогнозов</div><div class="a">Все →</div></div>{hist_list(3)}')
    return screen(body, "gap:14px")


def v3():
    days = [("Пн", "26", "😊"), ("Вт", "27", "😐"), ("Ср", "28", "😮‍💨"), ("Чт", "29", "😐"), ("Пт", "30", "😊")]
    strip = '<div class="strip">' + "".join(f'<div class="day"><i>{f}</i>{n}<span>{d}</span></div>'.replace(f"<i>{f}</i>{n}<span>{d}</span>", f"<i>{f}</i><span>{d}</span>") for d, n, f in days)
    strip += '<div class="day today"><i>31</i><span>Сб</span></div><div class="day future"><i></i><span>Вс</span></div></div>'
    body = (f'<div class="top"><div class="greet" style="font-size:28px;line-height:34px">{GREET}</div><div class="obox" style="width:64px;height:64px;margin:0 6px 0 12px">{B.orb_svg(64)}</div></div>'
            f'{strip}{hero()}{tiles()}{checkin_card()}<div class="sec"><div class="t">Разбор вчера</div><div class="a">Открыть →</div></div>')
    return screen(body, "gap:14px")


def v4():
    body = (f'<div class="greet" style="font-size:28px;line-height:34px;padding-top:4px">{GREET}</div>'
            f'<div style="display:flex;justify-content:center;margin:6px 0 10px">{orb(176)}</div>'
            f'<div class="card" style="gap:10px;margin-top:6px"><div class="cap">ПРОГНОЗ НА СЕГОДНЯ</div><div class="fc">{FORECAST}</div>'
            '<div class="links"><span>Почему такой прогноз ▾</span><span>Хочу ещё ✦</span></div></div>'
            '<div class="line1"><span><b>1ч 12м</b> <span class="m">экран</span> · <b>6ч 38м</b> <span class="m">сон</span> · <b>23</b> <span class="m">разблок.</span></span><span class="arr">›</span></div>'
            '<div class="slim"><div class="q">Как прошёл день?</div><div class="mini">😊</div><div class="mini">😐</div><div class="mini">😮‍💨</div></div>'
            '<div class="line1"><span><b>История</b> <span class="m">прогнозы и разборы</span></span><span class="arr">›</span></div>')
    return screen(body, "gap:16px")


def v5():
    sheet = ('<div class="sheet"><div class="handle"></div><div class="sec"><div class="t">История прогнозов</div><div class="a">Все →</div></div>'
             + "".join(f'<div class="lrow"><div class="d">{d}</div><div class="t">{t}</div><span class="mark {c}">{m}</span></div>' for d, t, m, c in HIST[:2]) + "</div>")
    body = (f'<div class="greet" style="font-size:28px;line-height:34px;padding-top:4px">{GREET}</div>'
            f'<div style="display:flex;flex-direction:column;align-items:center;gap:6px;text-align:center">{orb(130)}'
            f'<div class="cap" style="margin-top:8px">ПРОГНОЗ НА СЕГОДНЯ</div><div class="fc" style="max-width:350px">{FORECAST}</div>'
            '<div class="links"><span>Почему такой прогноз ▾</span><span>Хочу ещё ✦</span></div></div>'
            f'{chips()}{checkin_card()}')
    return screen(body, "gap:14px", sheet)


def v6():
    cx = 40 + 11 * ((B.W - 2 * B.PAD - 40 - 2 * 23) / 24 + 2) + 6      # центр текущего часа (11) в dp от левого края экрана
    left = cx - 40 - 48                                                # смещение орба внутри карточки (padding 20 + контент 20)
    day = ('<div class="card" style="padding:16px 20px 14px;gap:6px"><div class="row"><div class="title-s">Сегодня, сб 31 окт</div><div class="label-s">экран, минут в час</div></div>'
           f'<div style="position:relative;height:150px"><div class="obox" style="position:absolute;left:{left:.0f}px;top:4px;width:96px;height:96px">{B.orb_svg(96)}</div>'
           f'<div style="position:absolute;left:0;bottom:0">{B.timeline_svg()}</div></div>'
           '<div class="hours"><span>0</span><span>6</span><span>12</span><span>18</span><span>24</span></div></div>')
    fc = (f'<div class="card" style="gap:10px"><div class="cap">ПРОГНОЗ НА СЕГОДНЯ</div><div class="fc">{FORECAST}</div>'
          '<div class="links"><span>Почему такой прогноз ▾</span><span>Хочу ещё ✦</span></div></div>')
    acc = '<div class="line1"><span><b>Прошлые дни</b> <span class="m">прогнозы и разборы</span></span><span class="arr">▾</span></div>'
    return screen(f'<div class="greet" style="font-size:28px;line-height:34px;padding-top:4px">{GREET}</div>{day}{fc}{chips()}{checkin_card()}{acc}', "gap:12px")


def sheet_peek(rows=2):
    return ('<div class="sheet" style="height:190px"><div class="handle"></div><div class="sec"><div class="t">История</div><div class="a">Все →</div></div>'
            + "".join(f'<div class="lrow"><div class="d">{d}</div><div class="t">{t}</div><span class="mark {c}">{m}</span></div>' for d, t, m, c in HIST[:rows]) + "</div>")


def v7():
    body = (f'<div class="greet" style="font-size:28px;line-height:34px;padding-top:4px">{GREET}</div>'
            f'<div style="display:flex;justify-content:center;margin-top:2px">{orb(124)}</div>'
            f'<div class="card" style="gap:10px;margin-top:4px"><div class="cap">ПРОГНОЗ НА СЕГОДНЯ</div><div class="fc">{FORECAST}</div>'
            '<div class="links"><span>Почему такой прогноз ▾</span><span>Хочу ещё ✦</span></div></div>'
            f'{chips(True)}'
            '<div class="slim"><div class="q">Как прошёл день?</div><div class="mini">😊</div><div class="mini">😐</div><div class="mini">😮‍💨</div></div>')
    return screen(body, "gap:12px", sheet_peek())


def v8():
    loop = ('<div class="card" style="gap:10px"><div class="cap">УТРЕННИЙ ПРОГНОЗ</div><div class="fc" style="font-size:15px;line-height:22px">Плотное утро, ровнее вечер.</div>'
            '<div class="q" style="font-size:14px;font-weight:500">Совпало с днём?</div><div class="pills"><div class="pill filled">Совпало</div><div class="pill">Не совсем</div></div></div>')
    big = f'<div class="card checkin" style="gap:12px;padding:22px 20px"><div class="title-s" style="font-size:18px;line-height:24px">Как прошёл день?</div>{feels()}</div>'
    body = (f'<div class="greet" style="font-size:28px;line-height:34px;padding-top:4px">Добрый вечер</div>'
            f'<div style="display:flex;justify-content:center;margin-top:2px">{orb(124)}</div>'
            f'{big}{loop}')
    return screen(body, "gap:12px", sheet_peek(1), clock="21:40")


VARIANTS = [
    ("v0_current", "Текущий экран (контроль)",
     "Сверху приветствие и маленький орб справа (48 dp). Дальше крупная фиолетовая карточка прогноза, три плитки метрик, график «Сегодня по часам» с кнопками «Разбор вчера/сегодня», карточка чек-ина. История прогнозов видна только внизу после прокрутки. Всё в одной плотной колонке.", v0),
    ("v1_hero_orb", "Герой-орб по центру",
     "Крупный орб (170 dp) по центру как герой экрана, прогноз текстом прямо под ним без карточки, ссылки «Почему такой прогноз» и «Хочу ещё». Метрики свёрнуты в три компактные плашки, ниже чек-ин и горизонтальная лента карточек «Прошлые прогнозы» (свайп). График по часам спрятан за вторым уровнем.", v1),
    ("v2_orb_on_card", "Орб на карточке прогноза",
     "Орб (112 dp) крупно выступает из верхнего правого угла фиолетовой карточки прогноза, текст обтекает его слева. Ниже три плитки метрик, чек-ин и список «История прогнозов» (3 строки). График по часам спрятан за вторым уровнем.", v2),
    ("v3_day_strip", "Полоса дней сверху",
     "Над карточкой прогноза полоса недели: прошлые дни с эмодзи чек-ина, сегодняшний день выделен, будущий пунктиром. Касание дня открывает его прогноз и разбор (история как навигация по времени). Орб средний (64 dp) рядом с приветствием. Дальше карточка прогноза, плитки, чек-ин и строка «Разбор вчера». График по часам спрятан.", v3),
    ("v4_minimal", "Минимальный: четыре блока",
     "Орб (176 dp) по центру, одна светлая карточка прогноза, метрики одной строкой «экран · сон · разблокировки ›» (по касанию раскрываются плитки и график), слим-чек-ин с тремя кнопками в одну строку, строка «История ›» (прогнозы и разборы по касанию). Минимум элементов на экране.", v4),
    ("v6_day_path", "Орб едет по ленте дня",
     "Орб (96 dp) сидит на сегодняшней ленте «по часам» над текущим часом и отражает состояние дня (как персонаж на графике у Gentler Streak). Под лентой светлая карточка прогноза, метрики плашками, чек-ин и свёрнутая строка «Прошлые дни ▾» (аккордеон, как «Looking Back» у Oura), история по касанию.", v6),
    ("v7_hybrid_morning", "Гибрид: утро/день",
     "Собрано по критике судей. Орб средний (124 dp) по центру, прогноз в светлой карточке с текстом по левому краю, метрики тремя нейтральными плашками (без оценочных цветов), слим-чек-ин одной строкой (днём не доминирует), история нижней шторкой-выглядывающей (ручка, заголовок и две строки).", v7),
    ("v8_hybrid_evening", "Гибрид: вечер (21:40)",
     "Тот же каркас вечером, 21:40: чек-ин «Как прошёл день?» поднят наверх крупной карточкой под орбом, ниже карточка «Утренний прогноз: совпало с днём?» с двумя кнопками, история нижней шторкой (одна строка). Метрики и график спрятаны на втором уровне. Экран показан именно вечером.", v8),
    ("v5_bottom_sheet", "История нижней шторкой",
     "Орб (130 dp) по центру, прогноз текстом под ним, метрики плашками, чек-ин. История живёт в нижней шторке, которая всегда выглядывает снизу: ручка, заголовок «История прогнозов» и две строки; тянется вверх до полного экрана.", v5),
]


def render(html_path, png_path):
    subprocess.run([CHROME, "--headless=new", "--disable-gpu", "--hide-scrollbars", "--force-device-scale-factor=2", "--window-size=412,915",
                    "--virtual-time-budget=5000", f"--screenshot={png_path}", f"file://{html_path}"], capture_output=True, timeout=120)


def main():
    only = set(sys.argv[1:])
    OUT.mkdir(parents=True, exist_ok=True)
    cards = []
    for vid, name, desc, fn in VARIANTS:
        if only and vid not in only:
            cards.append(f'<div class="c"><img src="{vid}.png"><h3>{H.escape(name)}</h3><p>{H.escape(desc)}</p></div>')
            continue
        hp, pp = OUT / f"{vid}.html", OUT / f"{vid}.png"
        hp.write_text(fn(), encoding="utf-8")
        render(hp, pp)
        print(vid, "OK" if pp.exists() else "НЕ ОТРИСОВАЛСЯ", flush=True)
        cards.append(f'<div class="c"><img src="{vid}.png"><h3>{H.escape(name)}</h3><p>{H.escape(desc)}</p></div>')
    (OUT / "viewer.html").write_text("<!doctype html><meta charset=utf-8><title>Варианты главного экрана</title><style>body{font:14px/1.5 -apple-system,sans-serif;background:#F7F6FF;margin:20px;color:#1A1525}.g{display:flex;gap:18px;flex-wrap:wrap}.c{width:300px;background:#fff;border-radius:14px;padding:10px;box-shadow:0 1px 4px #0001}.c img{width:100%;border-radius:10px}h3{margin:8px 0 4px;font-size:15px}p{margin:0;font-size:12px;color:#6E6A85}</style><h1>Варианты главного экрана</h1><div class=g>" + "".join(cards) + "</div>", encoding="utf-8")
    print("viewer:", OUT / "viewer.html")


if __name__ == "__main__":
    main()
