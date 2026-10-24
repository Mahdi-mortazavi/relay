#!/usr/bin/env python3
"""Draws the README cover: docs/assets/cover.svg and cover-mobile.svg.

The cover is Relay's first impression, so it follows the iOS 27 / Liquid
Glass language the apps themselves aim at: an aurora that drifts behind
floating glass, two devices, and the link between them carrying traffic.

Usage:  python3 scripts/readme-cover.py docs/assets

It also writes social-preview.svg, the same design at 1280x640, which is
uploaded by hand as a PNG under Settings -> Social preview.

Rules, each learned by getting it wrong on an earlier banner:

1. Everything is visible at rest. Every animation's first frame is the
   resting frame, so a renderer that runs no animation, or freezes on the
   first frame, still shows a finished picture. Nothing fades in from 0.
2. GitHub shows this through <img>: no script, no external font or
   stylesheet. Motion is CSS @keyframes inside the file (so reduced motion
   can switch it off) plus SMIL for the packets that ride the link's curve.
3. No filters on anything that moves. An animated SVG image is repainted
   whole on every frame, and a blur on a large area costs that every time.
   Shadows and glows are gradients instead.
4. Persian text carries no direction attribute. An RTL base direction moves
   the anchor to the text's other edge, which is how a Persian line once
   ran off the canvas. The run still shapes and orders correctly without it.
"""
import sys
from pathlib import Path

OUT = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("docs/assets")

SANS = ("-apple-system, BlinkMacSystemFont, 'SF Pro Display', 'Segoe UI Variable Display', "
        "'Segoe UI', Inter, 'Helvetica Neue', Arial, sans-serif")
FARSI = "'SF Arabic', 'Segoe UI', Tahoma, Vazirmatn, 'Iranian Sans', sans-serif"
MONO_DIGITS = "'SF Pro Rounded', -apple-system, 'Segoe UI Variable Display', 'Segoe UI', Inter, sans-serif"

# One accent, as in the apps; the indigo only ever appears inside the aurora
# and at the far end of the headline's gradient.
TEAL, CYAN, INDIGO, VIOLET = "#4ADFBF", "#37C6E0", "#6C7CFF", "#A87BFF"

STYLE = """
  <style>
    .drift-a { animation: drift-a 26s ease-in-out infinite alternate; }
    .drift-b { animation: drift-b 31s ease-in-out infinite alternate; }
    .drift-c { animation: drift-c 23s ease-in-out infinite alternate; }
    .drift-d { animation: drift-d 37s ease-in-out infinite alternate; }
    @keyframes drift-a { 0% { transform: translate(0, 0) } 100% { transform: translate(140px, 60px) } }
    @keyframes drift-b { 0% { transform: translate(0, 0) } 100% { transform: translate(-160px, 40px) } }
    @keyframes drift-c { 0% { transform: translate(0, 0) } 100% { transform: translate(90px, -70px) } }
    @keyframes drift-d { 0% { transform: translate(0, 0) } 100% { transform: translate(-80px, -50px) } }

    /* Glass floats like it has mass: slow, eased both ways, never a bounce. */
    .float-1 { animation: float 7s cubic-bezier(.45,0,.55,1) infinite; }
    .float-2 { animation: float 8.5s cubic-bezier(.45,0,.55,1) 1.2s infinite; }
    .float-3 { animation: float 6.5s cubic-bezier(.45,0,.55,1) 2.4s infinite; }
    @keyframes float { 0%, 100% { transform: translateY(0) } 50% { transform: translateY(-7px) } }

    .ring { transform-box: fill-box; transform-origin: center;
            animation: ring 3.2s cubic-bezier(.2,.8,.2,1) infinite; }
    .ring-2 { animation-delay: 1.6s; }
    @keyframes ring { 0% { transform: scale(1); opacity: .5 } 100% { transform: scale(1.9); opacity: 0 } }

    .flow { animation: flow 1.1s linear infinite; }
    @keyframes flow { to { stroke-dashoffset: -22 } }

    .live { animation: live 2.4s ease-in-out infinite; }
    @keyframes live { 0%, 100% { opacity: 1 } 50% { opacity: .35 } }

    .bar { transform-box: fill-box; transform-origin: 50% 100%;
           animation: bar 1.8s cubic-bezier(.45,0,.55,1) infinite alternate; }
    @keyframes bar { 0% { transform: scaleY(1) } 100% { transform: scaleY(.35) } }

    /* A specular glint crosses the headline now and then, the way light runs
       along a glass edge when you tilt the phone. */
    .glint { animation: glint 7s cubic-bezier(.4,0,.2,1) 1s infinite; }
    @keyframes glint { 0% { transform: translateX(0) } 45%, 100% { transform: translateX(1100px) } }

    @media (prefers-reduced-motion: reduce) {
      * { animation: none !important; }
      .packet { display: none; }
    }
    /* Reduced transparency: frostier glass, the same layout. */
    @media (prefers-reduced-transparency: reduce) {
      .glass-body { fill: #1A2433; fill-opacity: .92; }
    }
    @media (prefers-contrast: more) {
      .glass-edge { stroke: #FFFFFF; stroke-opacity: .7; }
      .muted { fill: #FFFFFF; }
    }
  </style>"""


def defs():
    return f"""
  <defs>
    <linearGradient id="ground" x1="0" y1="0" x2="0" y2="1">
      <stop offset="0" stop-color="#070B14"/>
      <stop offset="1" stop-color="#04060B"/>
    </linearGradient>
    <radialGradient id="aur-teal"><stop offset="0" stop-color="{TEAL}" stop-opacity=".42"/><stop offset="1" stop-color="{TEAL}" stop-opacity="0"/></radialGradient>
    <radialGradient id="aur-cyan"><stop offset="0" stop-color="{CYAN}" stop-opacity=".30"/><stop offset="1" stop-color="{CYAN}" stop-opacity="0"/></radialGradient>
    <radialGradient id="aur-indigo"><stop offset="0" stop-color="{INDIGO}" stop-opacity=".30"/><stop offset="1" stop-color="{INDIGO}" stop-opacity="0"/></radialGradient>
    <radialGradient id="aur-violet"><stop offset="0" stop-color="{VIOLET}" stop-opacity=".18"/><stop offset="1" stop-color="{VIOLET}" stop-opacity="0"/></radialGradient>
    <radialGradient id="vignette" cx=".5" cy=".45" r=".75">
      <stop offset=".55" stop-color="#000" stop-opacity="0"/><stop offset="1" stop-color="#000" stop-opacity=".55"/>
    </radialGradient>

    <linearGradient id="title" x1="0" y1="0" x2="0" y2="1">
      <stop offset="0" stop-color="#FFFFFF"/><stop offset="1" stop-color="#C3D2E3"/>
    </linearGradient>
    <linearGradient id="title-accent" x1="0" y1="0" x2="1" y2="0">
      <stop offset="0" stop-color="{TEAL}"/><stop offset=".55" stop-color="{CYAN}"/><stop offset="1" stop-color="{INDIGO}"/>
    </linearGradient>
    <linearGradient id="glint" x1="0" y1="0" x2="1" y2="0">
      <stop offset="0" stop-color="#fff" stop-opacity="0"/><stop offset=".5" stop-color="#fff" stop-opacity=".75"/><stop offset="1" stop-color="#fff" stop-opacity="0"/>
    </linearGradient>

    <!-- Liquid Glass, 2026: a diffused fill, a darkened outer edge, and a
         brighter specular rim on the leading top edge. -->
    <linearGradient id="glass-fill" x1="0" y1="0" x2="0" y2="1">
      <stop offset="0" stop-color="#FFFFFF" stop-opacity=".17"/><stop offset="1" stop-color="#FFFFFF" stop-opacity=".06"/>
    </linearGradient>
    <linearGradient id="glass-tint" x1="0" y1="0" x2="1" y2="1">
      <stop offset="0" stop-color="{TEAL}" stop-opacity=".30"/><stop offset="1" stop-color="{CYAN}" stop-opacity=".14"/>
    </linearGradient>
    <linearGradient id="specular" x1="0" y1="0" x2="1" y2="1">
      <stop offset="0" stop-color="#FFFFFF" stop-opacity=".9"/>
      <stop offset=".3" stop-color="#FFFFFF" stop-opacity=".18"/>
      <stop offset=".65" stop-color="#FFFFFF" stop-opacity="0"/>
      <stop offset="1" stop-color="#FFFFFF" stop-opacity=".28"/>
    </linearGradient>
    <linearGradient id="sheen" x1="0" y1="0" x2="1" y2="1">
      <stop offset="0" stop-color="#FFFFFF" stop-opacity=".10"/><stop offset="1" stop-color="#FFFFFF" stop-opacity="0"/>
    </linearGradient>
    <radialGradient id="shadow"><stop offset="0" stop-color="#000" stop-opacity=".55"/><stop offset="1" stop-color="#000" stop-opacity="0"/></radialGradient>

    <linearGradient id="icon" x1="0" y1="0" x2="1" y2="1">
      <stop offset="0" stop-color="#5BF0CF"/><stop offset=".55" stop-color="{CYAN}"/><stop offset="1" stop-color="#3A8FE0"/>
    </linearGradient>
    <linearGradient id="titanium" x1="0" y1="0" x2="1" y2="1">
      <stop offset="0" stop-color="#8A96A8"/><stop offset=".35" stop-color="#2B3442"/><stop offset=".7" stop-color="#4A5566"/><stop offset="1" stop-color="#1C232E"/>
    </linearGradient>
    <linearGradient id="screen" x1="0" y1="0" x2="0" y2="1">
      <stop offset="0" stop-color="#0B1424"/><stop offset="1" stop-color="#050910"/>
    </linearGradient>
    <linearGradient id="aluminium" x1="0" y1="0" x2="0" y2="1">
      <stop offset="0" stop-color="#5D6878"/><stop offset=".4" stop-color="#2E3644"/><stop offset="1" stop-color="#151A22"/>
    </linearGradient>
    <linearGradient id="beam" x1="0" y1="0" x2="1" y2="0">
      <stop offset="0" stop-color="{TEAL}"/><stop offset="1" stop-color="{CYAN}"/>
    </linearGradient>
    <radialGradient id="glow"><stop offset="0" stop-color="{TEAL}" stop-opacity=".55"/><stop offset="1" stop-color="{TEAL}" stop-opacity="0"/></radialGradient>
    <radialGradient id="packet"><stop offset="0" stop-color="#FFFFFF"/><stop offset=".35" stop-color="#9FFBE6"/><stop offset="1" stop-color="{TEAL}" stop-opacity="0"/></radialGradient>
  </defs>"""


def t(x, y, s, size, *, weight=400, fill="#FFFFFF", anchor="start", family=SANS, spacing=None, cls=None, opacity=None):
    s = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    extra = ""
    if spacing is not None:
        extra += f' letter-spacing="{spacing}"'
    if cls:
        extra += f' class="{cls}"'
    if opacity is not None:
        extra += f' fill-opacity="{opacity}"'
    return (f'<text x="{x}" y="{y}" text-anchor="{anchor}" font-family="{family}" font-size="{size}" '
            f'font-weight="{weight}" fill="{fill}"{extra}>{s}</text>')


def glass(x, y, w, h, r, *, tint=False, shadow=True):
    """One Liquid Glass surface: shadow, body, darkened edge, specular rim."""
    out = []
    if shadow:
        out.append(f'<ellipse cx="{x + w / 2}" cy="{y + h + h * 0.08}" rx="{w * 0.52}" ry="{max(8, h * 0.28)}" fill="url(#shadow)"/>')
    out.append(f'<rect class="glass-body" x="{x}" y="{y}" width="{w}" height="{h}" rx="{r}" fill="url(#glass-fill)"/>')
    if tint:
        out.append(f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="{r}" fill="url(#glass-tint)"/>')
    out.append(f'<rect x="{x - .5}" y="{y - .5}" width="{w + 1}" height="{h + 1}" rx="{r + .5}" fill="none" stroke="#000" stroke-opacity=".45"/>')
    out.append(f'<rect class="glass-edge" x="{x + .6}" y="{y + .6}" width="{w - 1.2}" height="{h - 1.2}" rx="{r - .6}" fill="none" stroke="url(#specular)" stroke-width="1.2"/>')
    return "".join(out)


def chip(x, y, label, *, icon=None, cls="float-1", width=None, tint=False, farsi=False):
    w = width or (len(label) * 8.4 + (52 if icon else 34))
    h = 36
    inner = [glass(x, y, w, h, h / 2, tint=tint)]
    tx = x + 17
    if icon == "lock":
        inner.append(f'<g transform="translate({x + 15}, {y + 10})" fill="none" stroke="{TEAL}" stroke-width="1.7" stroke-linecap="round">'
                     f'<path d="M3.5 7 V5 a4 4 0 0 1 8 0 V7"/><rect x="1.5" y="7" width="12" height="9" rx="2.6" fill="{TEAL}" stroke="none"/></g>')
        tx = x + 37
    elif icon == "dot":
        inner.append(f'<circle class="live" cx="{x + 20}" cy="{y + 18}" r="4" fill="{TEAL}"/>')
        tx = x + 32
    inner.append(t(tx, y + 23.5, label, 14.5, weight=600, family=FARSI if farsi else SANS, spacing=".2"))
    return f'<g class="{cls}">{"".join(inner)}</g>'


def chip_width(label, icon=None):
    return len(label) * 8.4 + (52 if icon else 34)


def chip_row(x, y, items, *, anchor="start", gap=12):
    """Lays chips out left to right from x, or centred on x."""
    widths = [chip_width(label, kw.get("icon")) for label, kw in items]
    total = sum(widths) + gap * (len(items) - 1)
    cx = x - total / 2 if anchor == "middle" else x
    out = []
    for (label, kw), w in zip(items, widths):
        out.append(chip(round(cx, 1), y, label, width=w, **kw))
        cx += w + gap
    return "".join(out)


def app_icon(x, y, s):
    """The mark on a squircle, with the same glass rim as everything else."""
    r = s * 0.2237  # iOS icon corner ratio
    k = s / 64
    return (f'<g transform="translate({x}, {y})">'
            f'<ellipse cx="{s / 2}" cy="{s * 1.04}" rx="{s * .48}" ry="{s * .16}" fill="url(#shadow)"/>'
            f'<rect width="{s}" height="{s}" rx="{r}" fill="url(#icon)"/>'
            f'<rect x=".6" y=".6" width="{s - 1.2}" height="{s - 1.2}" rx="{r - .6}" fill="none" stroke="url(#specular)" stroke-width="1.2"/>'
            f'<g transform="translate({s / 2}, {s / 2}) scale({k})">'
            f'<path d="M-11 11 L0 -12 L11 11 Z" fill="#06231F" fill-opacity=".9"/><circle cx="0" cy="-5.5" r="4.6" fill="#06231F"/></g>'
            f'</g>')


def scene():
    """Phone in front of a laptop, the link arcing between them. 560 x 430."""
    p = []
    # Laptop, behind.
    lx, ly, lw, lh = 150, 40, 380, 236
    p.append(f'<ellipse cx="{lx + lw / 2}" cy="{ly + lh + 30}" rx="{lw * .62}" ry="26" fill="url(#shadow)"/>')
    p.append(f'<rect x="{lx}" y="{ly}" width="{lw}" height="{lh}" rx="16" fill="#0D121A" stroke="url(#titanium)" stroke-width="2"/>')
    sx, sy, sw, sh = lx + 10, ly + 10, lw - 20, lh - 20
    p.append(f'<clipPath id="laptop-screen"><rect x="{sx}" y="{sy}" width="{sw}" height="{sh}" rx="7"/></clipPath>')
    p.append(f'<g clip-path="url(#laptop-screen)"><rect x="{sx}" y="{sy}" width="{sw}" height="{sh}" fill="url(#screen)"/>'
             f'<circle class="drift-c" cx="{sx + sw * .8}" cy="{sy + 30}" r="170" fill="url(#aur-indigo)"/>'
             f'<circle class="drift-a" cx="{sx + sw * .55}" cy="{sy + sh}" r="150" fill="url(#aur-teal)"/>'
             f'<path d="M{sx} {sy} H{sx + sw * .42} L{sx + sw * .18} {sy + sh} H{sx} Z" fill="url(#sheen)"/></g>')
    p.append(f'<path d="M{lx - 26} {ly + lh + 2} H{lx + lw + 26} l-10 14 a6 6 0 0 1 -5 3 H{lx - 11} a6 6 0 0 1 -5 -3 Z" fill="url(#aluminium)"/>')
    p.append(f'<rect x="{lx + lw / 2 - 34}" y="{ly + lh + 2}" width="68" height="4" rx="2" fill="#0B0F15" fill-opacity=".7"/>')

    # The PC's Relay window: chrome, so it is glass.
    wx, wy, ww, wh = 300, 74, 206, 160
    p.append(f'<g class="float-2">{glass(wx, wy, ww, wh, 18)}')
    p.append(t(wx + 18, wy + 30, "Relay", 15, weight=700))
    p.append(f'<g>{glass(wx + ww - 118, wy + 13, 104, 26, 13, tint=True, shadow=False)}'
             f'<path d="M{wx + ww - 106} {wy + 26.5} l3.5 3.5 l6.5 -7.5" fill="none" stroke="#FFFFFF" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>'
             f'{t(wx + ww - 90, wy + 30.5, "Connected", 11.5, weight=600)}</g>')
    # Live throughput: bars, not numbers, because any number here would be made up.
    bars = [34, 52, 40, 64, 48, 72, 58, 80, 62, 70, 54, 76]
    for i, bh in enumerate(bars):
        bx = wx + 18 + i * 14.5
        p.append(f'<rect class="bar" style="animation-delay:{(i * 0.17) % 1.8:.2f}s" x="{bx}" y="{wy + 128 - bh * .9}" '
                 f'width="8" height="{bh * .9}" rx="4" fill="url(#beam)" fill-opacity="{0.55 + i * 0.035:.2f}"/>')
    p.append(t(wx + 18, wy + 148, "Every app · TCP + UDP", 10.5, weight=500, fill="#DCE6F2", opacity=".75", cls="muted"))
    p.append('</g>')

    # Phone, in front.
    px, py, pw, ph = 22, 70, 168, 338
    p.append(f'<ellipse cx="{px + pw / 2}" cy="{py + ph + 10}" rx="{pw * .6}" ry="20" fill="url(#shadow)"/>')
    p.append(f'<rect x="{px}" y="{py}" width="{pw}" height="{ph}" rx="36" fill="#05080D" stroke="url(#titanium)" stroke-width="3"/>')
    qx, qy, qw, qh = px + 8, py + 8, pw - 16, ph - 16
    p.append(f'<clipPath id="phone-screen"><rect x="{qx}" y="{qy}" width="{qw}" height="{qh}" rx="28"/></clipPath>')
    p.append(f'<g clip-path="url(#phone-screen)"><rect x="{qx}" y="{qy}" width="{qw}" height="{qh}" fill="url(#screen)"/>'
             f'<circle class="drift-b" cx="{qx + qw * .2}" cy="{qy + qh * .85}" r="160" fill="url(#aur-teal)"/>'
             f'<circle class="drift-d" cx="{qx + qw}" cy="{qy + 40}" r="130" fill="url(#aur-indigo)"/>'
             f'<path d="M{qx} {qy} H{qx + qw * .7} L{qx} {qy + qh * .55} Z" fill="url(#sheen)"/></g>')
    p.append(f'<circle cx="{px + pw / 2}" cy="{py + 22}" r="5" fill="#000" stroke="#1E2632"/>')
    p.append(t(qx + 18, qy + 26, "9:41", 11, weight=600))
    # Status capsule, the two digits, and the Stop control.
    cx = px + pw / 2
    p.append(f'<g class="float-3">{glass(cx - 46, qy + 42, 92, 28, 14, tint=True, shadow=False)}'
             f'<circle class="live" cx="{cx - 30}" cy="{qy + 56}" r="3.5" fill="#FFFFFF"/>'
             f'{t(cx - 20, qy + 60.5, "Sharing", 12, weight=600)}</g>')
    p.append(f'<circle class="ring" cx="{cx}" cy="{qy + 152}" r="44" fill="none" stroke="{TEAL}" stroke-width="1.5" stroke-opacity=".7"/>')
    p.append(f'<circle class="ring ring-2" cx="{cx}" cy="{qy + 152}" r="44" fill="none" stroke="{TEAL}" stroke-width="1.5" stroke-opacity=".7"/>')
    p.append(f'<circle cx="{cx}" cy="{qy + 152}" r="44" fill="{TEAL}" fill-opacity=".08" stroke="{TEAL}" stroke-opacity=".35"/>')
    p.append(t(cx, qy + 170, "11", 50, weight=700, anchor="middle", family=MONO_DIGITS, spacing="-1"))
    p.append(t(cx, qy + 222, "Type this on your PC", 10.5, weight=500, fill="#DCE6F2", anchor="middle", opacity=".75", cls="muted"))
    p.append(f'{glass(cx - 44, qy + qh - 66, 88, 34, 17, shadow=False)}{t(cx, qy + qh - 44, "Stop", 13, weight=600, anchor="middle")}')
    p.append(f'<rect x="{cx - 34}" y="{qy + qh - 14}" width="68" height="4" rx="2" fill="#FFFFFF" fill-opacity=".55"/>')

    # The link: an encrypted arc from phone to PC, traffic riding it.
    arc = f"M{px + pw - 4} 196 C 226 70, 268 58, {wx} 120"
    p.append(f'<path d="{arc}" fill="none" stroke="url(#beam)" stroke-opacity=".22" stroke-width="7" stroke-linecap="round"/>')
    p.append(f'<path id="link" class="flow" d="{arc}" fill="none" stroke="url(#beam)" stroke-width="2" stroke-dasharray="4 7" stroke-linecap="round"/>')
    for begin in ("0s", "0.8s", "1.6s"):
        p.append(f'<g class="packet" opacity="0"><circle r="9" fill="url(#packet)"/>'
                 f'<animateMotion dur="2.4s" begin="{begin}" repeatCount="indefinite" calcMode="spline" keyTimes="0;1" keySplines=".45 0 .55 1"><mpath href="#link"/></animateMotion>'
                 f'<animate attributeName="opacity" values="0;1;1;0" keyTimes="0;.15;.8;1" dur="2.4s" begin="{begin}" repeatCount="indefinite"/></g>')
    p.append(f'<g class="float-1"><circle cx="248" cy="92" r="40" fill="url(#glow)"/>{glass(229, 73, 38, 38, 19)}'
             f'<g transform="translate(240, 82)" fill="none" stroke="#FFFFFF" stroke-width="2" stroke-linecap="round">'
             f'<path d="M4 9 V6.5 a4.5 4.5 0 0 1 9 0 V9"/><rect x="2" y="9" width="13" height="10" rx="3" fill="#FFFFFF" stroke="none"/></g></g>')
    return "".join(p)


def aurora(w, h):
    r = min(w, h)  # sized to the short side, or a tall frame washes out
    return (f'<rect width="{w}" height="{h}" fill="url(#ground)"/>'
            f'<circle class="drift-a" cx="{w * .62}" cy="{h * .30}" r="{r * .85}" fill="url(#aur-teal)"/>'
            f'<circle class="drift-b" cx="{w * .95}" cy="{h * .85}" r="{r * .75}" fill="url(#aur-indigo)"/>'
            f'<circle class="drift-c" cx="{w * .15}" cy="{h * .95}" r="{r * .70}" fill="url(#aur-cyan)"/>'
            f'<circle class="drift-d" cx="{w * .40}" cy="{-h * .05}" r="{r * .60}" fill="url(#aur-violet)"/>'
            f'<rect width="{w}" height="{h}" fill="url(#vignette)"/>')


HEADER = """<!--
  Generated by scripts/readme-cover.py — edit the script, not this file.
  iOS 27 / Liquid Glass. Every frame of motion starts from the resting frame,
  so this is a finished picture with animation switched off.
-->"""

LABEL = ("Relay — your phone's internet, on your PC. Android to Windows over an encrypted "
         "WireGuard tunnel; works with your phone's VPN. اینترنت گوشی‌ات، روی کامپیوترت.")


def headline(x, y1, y2, size, anchor, clip_id):
    line2 = t(x, y2, "on your PC.", size, weight=700, fill="url(#title-accent)", anchor=anchor, spacing=f"{-size * .03:.1f}")
    return (t(x, y1, "Your phone's internet,", size, weight=700, fill="url(#title)", anchor=anchor, spacing=f"{-size * .03:.1f}")
            + line2
            + f'<clipPath id="{clip_id}">{t(x, y2, "on your PC.", size, weight=700, anchor=anchor, spacing=f"{-size * .03:.1f}")}</clipPath>'
            + f'<g clip-path="url(#{clip_id})"><rect class="glint" x="-260" y="{y2 - size}" width="160" height="{size * 1.3}" fill="url(#glint)"/></g>')


CHIPS = [
    ("No root", dict(cls="float-1")),
    ("No account", dict(cls="float-2")),
    ("No server", dict(cls="float-3")),
    ("Open source", dict(icon="dot", cls="float-1", tint=True)),
]


def wide(H=600, radius=28):
    """The wide cover; at H=640 with square corners it is the social preview."""
    W = 1280
    dy = (H - 600) / 2
    left = 84
    body = [
        aurora(W, H),
        f'<g transform="translate(0, {dy})">',
        app_icon(left, 72, 60),
        t(left + 76, 112, "Relay", 32, weight=650, spacing="-.4"),
        headline(left, 236, 300, 56, "start", "accent-wide"),
        t(left, 362, "اینترنت گوشی‌ات، روی کامپیوترت", 27, weight=600, fill="#A6F3E1", family=FARSI),
        t(left, 414, "Android → Windows · encrypted WireGuard · works with your VPN", 17.5, weight=500,
          fill="#DCE6F2", opacity=".72", cls="muted"),
        chip_row(left, 450, CHIPS),
        f'<g transform="translate(694, 96)">{scene()}</g>',
        '</g>',
    ]
    return svg(W, H, body, radius)


def narrow():
    W, H = 720, 940
    cx = W / 2
    body = [
        aurora(W, H),
        app_icon(cx - 98, 64, 52),
        t(cx - 34, 100, "Relay", 30, weight=650, spacing="-.4"),
        headline(cx, 196, 252, 46, "middle", "accent-narrow"),
        t(cx, 308, "اینترنت گوشی‌ات، روی کامپیوترت", 25, weight=600, fill="#A6F3E1", anchor="middle", family=FARSI),
        t(cx, 352, "Android → Windows · WireGuard · works with your VPN", 16.5, weight=500,
          fill="#DCE6F2", anchor="middle", opacity=".72", cls="muted"),
        chip_row(cx, 382, CHIPS, anchor="middle", gap=10),
        f'<g transform="translate({cx - 280}, 452)">{scene()}</g>',
    ]
    return svg(W, H, body)


def svg(w, h, body, radius=28):
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {w} {h}" width="{w}" height="{h}" role="img" '
            f'aria-label="{LABEL}">\n{HEADER}\n  <title>Relay — your phone\'s internet, on your PC</title>'
            f'{STYLE}{defs()}\n  <clipPath id="frame"><rect width="{w}" height="{h}" rx="{radius}"/></clipPath>\n'
            f'  <g clip-path="url(#frame)">\n  ' + "\n  ".join(body) + "\n  </g>\n</svg>\n")


OUT.mkdir(parents=True, exist_ok=True)
(OUT / "cover.svg").write_text(wide(), encoding="utf-8")
(OUT / "cover-mobile.svg").write_text(narrow(), encoding="utf-8")
# Uploaded by hand as a PNG (Settings -> Social preview); render this at 1280x640.
(OUT / "social-preview.svg").write_text(wide(H=640, radius=0), encoding="utf-8")
print("cover.svg, cover-mobile.svg and social-preview.svg written to", OUT)
