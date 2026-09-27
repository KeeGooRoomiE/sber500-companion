#!/usr/bin/env python3
"""
Home screen of the Android app, rebuilt as static HTML for the landing's screenshot.

Everything mirrors the Compose code 1:1 in dp (1 CSS px = 1 dp): ui/HomeScreen.kt, home/HomeStats.kt,
home/HomeReview.kt (timeline), home/HomeCheckIn.kt, motion/Backdrop.kt (waves) and
motion/CompanionOrb.kt (orb) — the animated parts are frozen at one moment. Only mock data is used,
no real person's numbers. Run:  python3 web/screens/build.py  →  web/screens/home.html
then  node web/screens/render.cjs  →  PNGs.
"""
import math, os

# ── Mock data (a calm-ish late morning of a made-up person) ─────────────────
MOCK = {
    "clock": "11:24",
    "greeting": "Доброе утро,<br>Саша",
    "forecast": ("Вчера было плотное утро: 51 разблокировка до полудня при обычных 15. "
                 "После таких дней у тебя обычно ровнее вечер, а фокус скорее придёт ближе к обеду."),
    # (label, value text, level) — level colours as HealthLevel on the light theme
    "stats": [("Экран", "1ч 12м", "good"), ("Сон", "6ч 38м", "warn"), ("Разблок.", "23", "good")],
    "now_hour": 11,
    "hourly_screen": [18, 6, 0, 0, 0, 0, 0, 21, 34, 17, 27, 23] + [0] * 12,
}

# ── Theme (ui/theme/Theme.kt, light) ─────────────────────────────────────────
C = dict(primary="#6B5CE7", primaryContainer="#EDE9FF", bg="#F7F6FF", surface="#FFFFFF",
         onSurface="#1A1525", muted="#6E6A85", outline="#DDD9F8", surfaceVariant="#F0EEFF",
         good="#1F7A50", warn="#B35B00", bad="#B22020")
W, H = 412, 915           # Pixel-class phone, dp
STATUS = 32               # status bar height, dp
PAD = 20                  # LazyColumn horizontal padding


def waves_svg():
    """motion/Backdrop.kt, scene Home (yStart .22), light theme, frozen at a pleasant phase."""
    out = []
    for i in range(8):
        amp = 10 + i * 9
        y_base = H * (0.22 + i * 0.108)
        phase = i * 7.3 + 0.9 + i * 1.2           # initial phase + a little time + PHASE_OFFSET
        pts = []
        x = 0
        while x <= W + 4:
            y = y_base + math.sin(x * .008 + phase) * amp + math.sin(x * .004 + phase * .6) * amp * .45
            pts.append(f"{x:.1f},{y:.1f}")
            x += 4
        hue = ((339 - 12 * i) % 360 + 360) % 360
        sat, light = 62 + 2.5 * i, 68 - 2 * i
        out.append(f'<path d="M{" L".join(pts)} L{W},{H} L0,{H} Z" fill="hsla({hue},{sat}%,{light}%,0.065)"/>')
    return f'<svg class="waves" viewBox="0 0 {W} {H}" width="{W}" height="{H}">{"".join(out)}</svg>'


def orb_svg(size=48, t=0.0):
    """motion/CompanionOrb.kt at time t, default palette (no check-in yet)."""
    light, mid, deep = "#9B8AFF", "#6B5CE7", "#3A2F8A"
    cx = cy = size / 2
    r = size / 2 * (1 + .02 * (1 + math.sin(t * 1.5)))
    glow_pulse = 1 + .06 * math.sin(t * .9 + 1)
    gx, gy, gr = cx, cy + r * .25, r * 1.4 * glow_pulse
    pts = []
    for i in range(73):
        a = i / 72 * 2 * math.pi
        wob = 1 + .028 * math.sin(3 * a + t * .8) + .018 * math.sin(5 * a - t * 1.1 + 1.7) + .010 * math.sin(2 * a + t * .5)
        pts.append(f"{cx + math.cos(a) * r * wob:.2f},{cy + math.sin(a) * r * wob:.2f}")
    hl = -2.25 + .35 * math.sin(t * .45)
    hx, hy = cx + math.cos(hl) * r * .5, cy + math.sin(hl) * r * .5
    sw = t * .6
    sx, sy = cx + math.cos(sw) * r * .35, cy + math.sin(sw) * r * .35
    body = "M" + " L".join(pts) + " Z"
    pad = 30   # the glow is drawn outside the orb's bounds, as on the phone
    return f'''<svg class="orb" width="{size + 2 * pad}" height="{size + 2 * pad}" viewBox="{-pad} {-pad} {size + 2 * pad} {size + 2 * pad}">
  <defs>
    <radialGradient id="glow" gradientUnits="userSpaceOnUse" cx="{gx}" cy="{gy}" r="{gr}">
      <stop offset="0" stop-color="{mid}" stop-opacity=".35"/><stop offset="1" stop-color="{mid}" stop-opacity="0"/>
    </radialGradient>
    <radialGradient id="body" gradientUnits="userSpaceOnUse" cx="{hx:.2f}" cy="{hy:.2f}" r="{r * 1.75:.2f}">
      <stop offset="0" stop-color="#fff" stop-opacity=".95"/><stop offset=".08" stop-color="#fff" stop-opacity=".85"/>
      <stop offset=".30" stop-color="{light}"/><stop offset=".62" stop-color="{mid}"/><stop offset="1" stop-color="{deep}"/>
    </radialGradient>
    <radialGradient id="shimmer" gradientUnits="userSpaceOnUse" cx="{sx:.2f}" cy="{sy:.2f}" r="{r * .7:.2f}">
      <stop offset="0" stop-color="{light}" stop-opacity=".28"/><stop offset="1" stop-color="{light}" stop-opacity="0"/>
    </radialGradient>
  </defs>
  <circle cx="{gx}" cy="{gy}" r="{gr:.2f}" fill="url(#glow)"/>
  <path d="{body}" fill="url(#body)"/>
  <path d="{body}" fill="url(#shimmer)"/>
</svg>'''


def timeline_svg():
    """home/HomeReview.kt DayTimelineCard canvas: 24 bars, 2 dp gaps, 64 dp tall."""
    w_total, h = W - 2 * PAD - 40, 64
    gap = 2
    w = (w_total - gap * 23) / 24
    out = []
    for hr, minutes in enumerate(MOCK["hourly_screen"]):
        x = hr * (w + gap)
        if hr > MOCK["now_hour"]:
            out.append(f'<rect x="{x:.2f}" y="{h - 2}" width="{w:.2f}" height="2" rx="1" fill="{C["outline"]}" fill-opacity=".35"/>')
            continue
        bh = max(h * min(max(minutes, 0), 60) / 60, 2)
        fill = C["primary"] if hr == MOCK["now_hour"] else C["primaryContainer"]
        out.append(f'<rect x="{x:.2f}" y="{h - bh:.2f}" width="{w:.2f}" height="{bh:.2f}" rx="3" fill="{fill}"/>')
    return f'<svg width="{w_total}" height="{h}" viewBox="0 0 {w_total} {h}">{"".join(out)}</svg>'


def status_icons():
    ink = C["onSurface"]
    wifi = f'''<svg width="17" height="17" viewBox="0 0 24 24"><path fill="{ink}" d="M12 20.5 1.2 8.9C4.1 6.2 7.9 4.6 12 4.6s7.9 1.6 10.8 4.3L12 20.5z"/></svg>'''
    signal = f'''<svg width="16" height="17" viewBox="0 0 24 24"><path fill="{ink}" d="M2 22h20V2L2 22z"/></svg>'''
    battery = f'''<svg width="10" height="17" viewBox="0 0 10 18"><rect x="3" y="0" width="4" height="2" rx=".6" fill="{ink}"/><rect x=".7" y="1.7" width="8.6" height="15.6" rx="1.8" fill="none" stroke="{ink}" stroke-width="1.4"/><rect x="2.2" y="5.2" width="5.6" height="10.6" rx=".8" fill="{ink}"/></svg>'''
    return wifi + signal + battery


def stat_tile(label, value, level):
    col = C[level]
    return f'''<div class="tile" style="--lvl:{col}">
      <div class="dot"></div>
      <div class="val">{value}</div>
      <div class="lab">{label.upper()}</div>
    </div>'''


def build():
    stats = "".join(stat_tile(*s) for s in MOCK["stats"])
    html = f'''<!doctype html>
<html lang="ru">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width={W}">
<title>Companion — главный экран (мок)</title>
<style>
  /* Roboto (variable, from Google Fonts) next to the page: renders the same offline and in CI */
  @font-face {{ font-family: Roboto; font-weight: 100 900; font-display: block; src: url(fonts/roboto-cyrillic.woff2) format("woff2");
               unicode-range: U+0301, U+0400-045F, U+0490-0491, U+04B0-04B1, U+2116; }}
  @font-face {{ font-family: Roboto; font-weight: 100 900; font-display: block; src: url(fonts/roboto-latin.woff2) format("woff2");
               unicode-range: U+0000-00FF, U+0131, U+0152-0153, U+02BB-02BC, U+02C6, U+02DA, U+02DC, U+0304, U+0308, U+0329, U+2000-206F, U+20AC, U+2122, U+2191, U+2193, U+2212, U+2215, U+FEFF, U+FFFD; }}
  /* Generated by build.py — edit the generator, not this file. 1 CSS px = 1 dp. */
  * {{ box-sizing: border-box; margin: 0; padding: 0; }}
  html, body {{ background: transparent; }}
  body {{ font-family: Roboto, sans-serif; -webkit-font-smoothing: antialiased; color: {C["onSurface"]}; }}
  .emoji {{ font-family: "Noto Color Emoji"; }}

  .screen {{ position: relative; width: {W}px; height: {H}px; overflow: hidden; background: {C["bg"]}; }}
  .waves {{ position: absolute; inset: 0; }}

  .status {{ position: absolute; top: 0; left: 0; right: 0; height: {STATUS}px; display: flex; align-items: center;
            justify-content: space-between; padding: 0 18px 0 22px; font-size: 14px; font-weight: 500; letter-spacing: .1px; z-index: 2; }}
  .status .icons {{ display: flex; gap: 6px; align-items: center; }}

  .content {{ position: absolute; top: {STATUS + 16}px; left: {PAD}px; right: {PAD}px; display: flex; flex-direction: column; gap: 16px; }}

  /* TopBar */
  .top {{ display: flex; justify-content: space-between; align-items: flex-start; padding-top: 4px; }}
  .greet {{ font-weight: 300; font-size: 34px; line-height: 42px; letter-spacing: -0.5px; flex: 1; }}
  .orb-box {{ width: 48px; height: 48px; margin: 4px 0 0 12px; position: relative; flex: none; }}
  .orb {{ position: absolute; left: -30px; top: -30px; overflow: visible; }}

  /* MorningCard */
  .hero {{ border-radius: 20px; padding: 20px; color: #fff; display: flex; flex-direction: column; gap: 12px;
           background: linear-gradient(var(--angle, 124deg), {C["primary"]}, #8B7CF8); }}
  .hero .label {{ font-size: 12px; font-weight: 600; letter-spacing: .4px; color: rgba(255,255,255,.75); line-height: normal; }}
  .hero .text {{ font-size: 16px; line-height: 24px; }}
  .hero .why {{ font-size: 14px; font-weight: 600; letter-spacing: .1px; color: rgba(255,255,255,.9); display: flex; gap: 4px; align-items: center; padding: 2px 0; line-height: normal; }}
  .more {{ align-self: flex-start; border-radius: 14px; background: rgba(255,255,255,.16); border: 1px solid rgba(255,255,255,.5);
           padding: 10px 16px; font-size: 14px; font-weight: 600; letter-spacing: .1px; line-height: normal; }}

  /* StatsRow */
  .stats {{ display: flex; gap: 10px; }}
  .tile {{ flex: 1; min-width: 0; border-radius: 16px; padding: 14px 12px; display: flex; flex-direction: column; gap: 2px;
           background: linear-gradient(color-mix(in srgb, var(--lvl) 10%, transparent), color-mix(in srgb, var(--lvl) 10%, transparent)), {C["surface"]};
           box-shadow: 0 1px 2px rgba(0,0,0,.18), 0 1px 3px 1px rgba(0,0,0,.08); }}
  .tile .dot {{ width: 8px; height: 8px; border-radius: 50%; background: var(--lvl); margin-bottom: 4px; }}
  .tile .val {{ font-size: 20px; line-height: 26px; font-weight: 700; color: var(--lvl); font-feature-settings: "tnum"; white-space: nowrap; }}
  .tile .lab {{ font-size: 11px; font-weight: 500; letter-spacing: .6px; color: {C["muted"]}; white-space: nowrap; line-height: normal; }}

  /* Cards on surface */
  .card {{ border-radius: 20px; background: {C["surface"]}; padding: 20px; display: flex; flex-direction: column; gap: 12px; }}
  .row {{ display: flex; align-items: center; }}
  .title-s {{ font-size: 14px; line-height: 20px; font-weight: 500; letter-spacing: .1px; flex: 1; }}
  .label-s {{ font-size: 11px; font-weight: 500; letter-spacing: .5px; color: {C["muted"]}; line-height: normal; }}
  .hours {{ display: flex; }}
  .hours span {{ flex: 1; font-size: 11px; font-weight: 500; letter-spacing: .5px; color: {C["muted"]}; text-align: center; line-height: normal; }}
  .hours span:first-child {{ text-align: left; }} .hours span:last-child {{ text-align: right; }}
  .pills {{ display: flex; gap: 10px; }}
  .pill {{ flex: 1; border-radius: 14px; border: 1px solid {C["primary"]}; padding: 11px 16px; text-align: center;
           font-size: 14px; font-weight: 600; letter-spacing: .1px; color: {C["primary"]}; line-height: normal; }}
  .pill.filled {{ background: {C["primary"]}; color: #fff; }}

  /* CheckInSection (morning: not highlighted) */
  .checkin {{ gap: 14px; }}
  .feels {{ display: flex; gap: 10px; }}
  .feel {{ flex: 1; border-radius: 10px; background: {C["surfaceVariant"]}; padding: 14px 4px; display: flex; flex-direction: column;
           align-items: center; gap: 4px; }}
  .feel .e {{ font-size: 26px; line-height: normal; }}
  .feel .l {{ font-size: 11px; font-weight: 400; letter-spacing: .5px; color: {C["muted"]}; line-height: normal; }}

  .gesture {{ position: absolute; bottom: 8px; left: 50%; width: 108px; height: 4px; margin-left: -54px; border-radius: 2px;
              background: rgba(26,21,37,.38); z-index: 2; }}

  /* Framed variant: ?frame=1 */
  .phone {{ display: none; }}
  body.framed {{ padding: 40px; }}
  body.framed .phone {{ display: block; position: relative; width: {W + 28}px; height: {H + 28}px; border-radius: 58px; padding: 14px;
                        background: linear-gradient(145deg, #2a2733, #121016); box-shadow: 0 0 0 2px #3a3644 inset, 0 30px 60px rgba(40,28,90,.28); }}
  body.framed .screen {{ border-radius: 44px; }}
  body.framed .camera {{ position: absolute; top: 12px; left: 50%; width: 11px; height: 11px; margin-left: -5.5px; border-radius: 50%;
                         background: #0c0b10; box-shadow: 0 0 0 1.5px #1d1b24; z-index: 3; }}
</style>
</head>
<body>
<div class="phone-host">
<div class="phone-frame">
<div class="screen" id="screen">
  {waves_svg()}
  <div class="status"><span>{MOCK["clock"]}</span><span class="icons">{status_icons()}</span></div>
  <div class="camera"></div>
  <div class="content">
    <div class="top">
      <div class="greet">{MOCK["greeting"]}</div>
      <div class="orb-box">{orb_svg()}</div>
    </div>

    <div class="hero" id="hero">
      <div class="label">Прогноз на сегодня</div>
      <div class="text">{MOCK["forecast"]}</div>
      <div class="why">Почему такой прогноз <span>▾</span></div>
      <div class="more">Хочу ещё ✦</div>
    </div>

    <div class="stats">{stats}</div>

    <div class="card">
      <div class="row"><div class="title-s">Сегодня по часам</div><div class="label-s">экран, минут в час</div></div>
      {timeline_svg()}
      <div class="hours"><span>0</span><span>6</span><span>12</span><span>18</span><span>24</span></div>
      <div class="pills"><div class="pill filled">Разбор вчера</div><div class="pill">Разбор сегодня</div></div>
    </div>

    <div class="card checkin">
      <div class="title-s">Как прошёл день?</div>
      <div class="feels">
        <div class="feel"><span class="e emoji">😊</span><span class="l">Отлично</span></div>
        <div class="feel"><span class="e emoji">😐</span><span class="l">Нормально</span></div>
        <div class="feel"><span class="e emoji">😮‍💨</span><span class="l">Тяжело</span></div>
      </div>
    </div>
  </div>
  <div class="gesture"></div>
</div>
</div>
</div>
<script>
  // Compose Brush.linearGradient runs corner to corner (top-left → bottom-right); match it exactly.
  const hero = document.getElementById('hero');
  hero.style.setProperty('--angle', (90 + Math.atan2(hero.offsetHeight, hero.offsetWidth) * 180 / Math.PI) + 'deg');
  if (new URLSearchParams(location.search).get('frame')) {{
    document.body.classList.add('framed');
    document.querySelector('.phone-frame').classList.add('phone');
  }}
</script>
</body>
</html>
'''
    here = os.path.dirname(os.path.abspath(__file__))
    with open(os.path.join(here, "home.html"), "w") as f:
        f.write(html)
    print("home.html written")


if __name__ == "__main__":
    build()
