#!/usr/bin/env python3
"""Draws the README's diagram and feature icons as SVG.

Why a script and not hand-edited files: the English and Persian diagrams are
the same picture mirrored, and keeping two hand-drawn copies in step is how
they would drift. Change the geometry or a label here and run it again.

Usage:  python3 scripts/readme-assets.py docs/assets

It writes docs/assets/readme/*.svg. The cover and the social preview are
drawn by scripts/readme-cover.py.

Same rules as the cover (scripts/readme-cover.py): everything visible at rest, motion is
SMIL only (GitHub shows these through <img>), nothing outside the viewBox,
and Persian text is centred so an RTL base direction cannot push it off.
"""
import sys
from pathlib import Path

OUT = Path(sys.argv[1])
(OUT / "readme").mkdir(parents=True, exist_ok=True)

LATIN = "'Segoe UI', Inter, system-ui, -apple-system, Helvetica, Arial, sans-serif"
PERSIAN = "Vazirmatn, 'Segoe UI', Tahoma, 'Iranian Sans', sans-serif"

DEFS = """
  <defs>
    <linearGradient id="ground" x1="0" y1="0" x2="1" y2="1">
      <stop offset="0%" stop-color="#0B1220"/>
      <stop offset="55%" stop-color="#0E1A2B"/>
      <stop offset="100%" stop-color="#0A1520"/>
    </linearGradient>
    <linearGradient id="accent" x1="0" y1="0" x2="1" y2="0">
      <stop offset="0%" stop-color="#4ADFBF"/>
      <stop offset="100%" stop-color="#37C6E0"/>
    </linearGradient>
    <radialGradient id="halo" cx="50%" cy="50%" r="50%">
      <stop offset="0%" stop-color="#4ADFBF" stop-opacity="0.13"/>
      <stop offset="100%" stop-color="#4ADFBF" stop-opacity="0"/>
    </radialGradient>
    <filter id="soften" x="-80%" y="-80%" width="260%" height="260%">
      <feGaussianBlur stdDeviation="3"/>
    </filter>
  </defs>"""


def esc(s):
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def text(x, y, s, size, *, fa=False, weight=400, fill="#F2F7FC", spacing=None, anchor="middle"):
    family = PERSIAN if fa else LATIN
    extra = ' direction="rtl" unicode-bidi="embed"' if fa else ""
    ls = f' letter-spacing="{spacing}"' if spacing is not None else ""
    return (f'<text x="{x}" y="{y}" text-anchor="{anchor}" font-family="{family}" '
            f'font-size="{size}" font-weight="{weight}" fill="{fill}"{ls}{extra}>{esc(s)}</text>')


def chips(cx, y, labels, *, fa=False, size=15):
    """A centred row of pill labels, in reading order for either direction."""
    per_char = 7.0 if fa else 8.6
    if fa:
        labels = list(reversed(labels))
    widths = [max(64, len(l) * per_char + 30) for l in labels]
    gap = 10
    total = sum(widths) + gap * (len(labels) - 1)
    x = cx - total / 2
    out = []
    for label, w in zip(labels, widths):
        out.append(f'<rect x="{x:.1f}" y="{y - 22}" width="{w:.1f}" height="34" rx="17" '
                   f'fill="#4ADFBF" fill-opacity="0.08" stroke="#4ADFBF" stroke-opacity="0.35"/>')
        out.append(text(round(x + w / 2, 1), y, label, size, fa=fa, weight=600, fill="#BFF5E8"))
        x += w + gap
    return "\n  ".join(out)


def phone(cx, cy):
    x, y = cx - 50, cy - 84
    return f"""<g transform="translate({x}, {y})">
    <rect width="100" height="168" rx="20" fill="#0E1726" stroke="#26384E" stroke-width="1.6"/>
    <rect x="37" y="12" width="26" height="4" rx="2" fill="#26384E"/>
    <circle cx="50" cy="84" r="24" fill="none" stroke="#4ADFBF" stroke-opacity="0.28" stroke-width="1.6"/>
    <circle cx="50" cy="84" r="8.5" fill="#4ADFBF">
      <animate attributeName="r" values="8.5;10;8.5" dur="2.6s" repeatCount="indefinite"/>
    </circle>
  </g>"""


def laptop(cx, cy):
    x, y = cx - 72, cy - 58
    return f"""<g transform="translate({x}, {y})">
    <rect width="144" height="100" rx="10" fill="#0E1726" stroke="#26384E" stroke-width="1.6"/>
    <circle cx="72" cy="46" r="16" fill="none" stroke="#4ADFBF" stroke-opacity="0.30" stroke-width="2"/>
    <circle cx="72" cy="46" r="6" fill="#4ADFBF" opacity="0.85">
      <animate attributeName="opacity" values="0.85;1;0.85" dur="2.1s" begin="0.6s" repeatCount="indefinite"/>
    </circle>
    <rect x="-14" y="104" width="172" height="8" rx="4" fill="#0E1726" stroke="#26384E" stroke-width="1.4"/>
  </g>"""


def link(x1, x2, y):
    """The encrypted link with packets travelling from x1 to x2."""
    mid = (x1 + x2) / 2
    out = [f'<line x1="{x1}" y1="{y}" x2="{x2}" y2="{y}" stroke="#4ADFBF" stroke-opacity="0.30" '
           f'stroke-width="2" stroke-dasharray="6 8">'
           f'<animate attributeName="stroke-dashoffset" values="{28 if x2 > x1 else -28};0" dur="1.6s" repeatCount="indefinite"/></line>']
    for begin in ("0s", "1.2s"):
        out.append(f'<circle cy="{y}" r="4" fill="#7CF5DC" filter="url(#soften)" opacity="0">'
                   f'<animate attributeName="cx" values="{x1};{x2}" dur="2.4s" begin="{begin}" repeatCount="indefinite"/>'
                   f'<animate attributeName="opacity" values="0;1;1;0" keyTimes="0;0.12;0.85;1" dur="2.4s" begin="{begin}" repeatCount="indefinite"/></circle>')
    out.append(f'<circle cx="{mid}" cy="{y}" r="24" fill="#0E1726" stroke="#4ADFBF" stroke-opacity="0.5"/>')
    out.append(f'<path d="M{mid - 7.5} {y - 2} v-5 a7.5 7.5 0 0 1 15 0 v5" fill="none" stroke="#4ADFBF" stroke-width="2.2"/>')
    out.append(f'<rect x="{mid - 9.5}" y="{y - 2}" width="19" height="15" rx="3" fill="#4ADFBF"/>')
    return "\n  ".join(out)


def how_it_works(fa):
    W, H = 1200, 430
    cy = 190
    left, right = 200, 1000
    if fa:
        phone_x, pc_x = right, left
        t = dict(
            phone="گوشی تو", pc="کامپیوتر تو", tunnel="تونل رمزنگاری‌شدهٔ WireGuard",
            src=["اینترنت همراه", "وای‌فای", "وی‌پی‌ان"], via=["همان وای‌فای", "هات‌اسپات", "کابل USB"],
            apps=["همهٔ برنامه‌ها", "TCP + UDP"],
            cap_src="هر اتصالی که گوشی دارد", cap_via="هرچه دو دستگاه را به هم وصل می‌کند",
            cap_apps="بازی، تماس و نصب‌کننده‌ها هم",
            foot="بدون سرور، بدون حساب کاربری · کلیدها برای هر جفت‌شدن ساخته و بعدش نابود می‌شوند",
            label="نمودار کارکرد رله: اینترنت گوشی، از جمله وی‌پی‌ان، از راه تونل رمزنگاری‌شدهٔ WireGuard به همهٔ برنامه‌های کامپیوتر می‌رسد.",
        )
    else:
        phone_x, pc_x = left, right
        t = dict(
            phone="YOUR PHONE", pc="YOUR PC", tunnel="Encrypted WireGuard tunnel",
            src=["Mobile data", "Wi-Fi", "VPN"], via=["Same Wi-Fi", "Hotspot", "USB cable"],
            apps=["Every app", "TCP + UDP"],
            cap_src="whatever the phone is online with", cap_via="over whatever already connects them",
            cap_apps="games, calls and installers too",
            foot="No server, no account in between · keys are made per pairing and destroyed after",
            label="How Relay works: the phone's internet, including its VPN, reaches every app on the PC through an encrypted WireGuard tunnel.",
        )
    caps = 13 if not fa else 16
    head_fill = "#8FA3B8"
    sp = "1.6" if not fa else None
    a, b = (phone_x + 62, pc_x - 90) if not fa else (phone_x - 62, pc_x + 90)
    parts = [
        f'<rect width="{W}" height="{H}" rx="24" fill="url(#ground)"/>',
        f'<circle cx="{phone_x}" cy="{cy}" r="170" fill="url(#halo)"/>',
        f'<circle cx="{pc_x}" cy="{cy}" r="170" fill="url(#halo)"/>',
        text(phone_x, 66, t["phone"], caps, fa=fa, weight=700, fill=head_fill, spacing=sp),
        text(pc_x, 66, t["pc"], caps, fa=fa, weight=700, fill=head_fill, spacing=sp),
        link(a, b, cy),
        text(600, cy - 46, t["tunnel"], 21 if not fa else 22, fa=fa, weight=600),
        phone(phone_x, cy),
        laptop(pc_x, cy),
        chips(phone_x, 322, t["src"], fa=fa),
        chips(600, 322, t["via"], fa=fa),
        chips(pc_x, 322, t["apps"], fa=fa),
        text(phone_x, 364, t["cap_src"], 14, fa=fa, fill="#8FA3B8"),
        text(600, 364, t["cap_via"], 14, fa=fa, fill="#8FA3B8"),
        text(pc_x, 364, t["cap_apps"], 14, fa=fa, fill="#8FA3B8"),
        f'<line x1="80" y1="390" x2="1120" y2="390" stroke="#26384E" stroke-width="1"/>',
        text(600, 414, t["foot"], 14, fa=fa, fill="#6F8399"),
    ]
    body = "\n  ".join(parts)
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {W} {H}" width="{W}" height="{H}" '
            f'role="img" aria-label="{esc(t["label"])}">\n  <title>{esc(t["label"])}</title>{DEFS}\n  {body}\n</svg>\n')


ICONS = {
    # Every app: four tiles.
    "apps": '<rect x="18" y="18" width="12" height="12" rx="3.5"/><rect x="34" y="18" width="12" height="12" rx="3.5"/>'
            '<rect x="18" y="34" width="12" height="12" rx="3.5"/><rect x="34" y="34" width="12" height="12" rx="3.5"/>',
    # Honest status: a pulse line through a ring.
    "status": '<circle cx="32" cy="32" r="15"/><path d="M19 32 h6 l3 -7 l5 13 l3 -6 h9"/>',
    # Keeps going: two chasing arrows.
    "roam": '<path d="M20 28 a13 13 0 0 1 23 -6"/><path d="M44 16 v7 h-7"/>'
            '<path d="M44 36 a13 13 0 0 1 -23 6"/><path d="M20 48 v-7 h7"/>',
    # Leak protection: a shield with a tick.
    "shield": '<path d="M32 15 l13 5 v10 c0 9 -6 15 -13 19 c-7 -4 -13 -10 -13 -19 v-10 z"/><path d="M26 32 l4.5 4.5 l8 -9"/>',
    # One tap: a bolt.
    "tap": '<path d="M35 14 l-13 20 h10 l-3 16 l13 -20 h-10 z"/>',
    # Two languages: a globe.
    "globe": '<circle cx="32" cy="32" r="15"/><path d="M17 32 h30"/><path d="M32 17 c-8 9 -8 21 0 30 c8 -9 8 -21 0 -30"/>',
}


def icon(name, glyph):
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64" width="64" height="64" aria-hidden="true">'
            f'{DEFS}<defs><linearGradient id="stroke" gradientUnits="userSpaceOnUse" x1="16" y1="16" x2="48" y2="48">'
            f'<stop offset="0" stop-color="#4ADFBF"/><stop offset="1" stop-color="#37C6E0"/></linearGradient></defs>'
            # userSpaceOnUse: a bounding-box gradient on a straight line has a
            # zero-height box and draws nothing at all.
            f'<rect x="1" y="1" width="62" height="62" rx="17" fill="url(#ground)" stroke="#26384E"/>'
            f'<g fill="none" stroke="url(#stroke)" stroke-width="2.6" stroke-linecap="round" stroke-linejoin="round">'
            f'{glyph}</g></svg>\n')


(OUT / "readme" / "how-it-works.svg").write_text(how_it_works(False), encoding="utf-8")
(OUT / "readme" / "how-it-works-fa.svg").write_text(how_it_works(True), encoding="utf-8")
for name, glyph in ICONS.items():
    (OUT / "readme" / f"icon-{name}.svg").write_text(icon(name, glyph), encoding="utf-8")
print("written to", OUT)
