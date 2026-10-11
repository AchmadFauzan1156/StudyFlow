"""Membangun aset brand StudyFlow dari satu definisi monogram SF.

Pemakaian: uv run --with fonttools --with uharfbuzz python build_brand.py <root-proyek>
"""
import json
import os
import sys

import uharfbuzz as hb
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.ttLib import TTFont

ROOT = sys.argv[1]
BRAND = os.path.join(ROOT, "docs", "brand")
FONT = os.path.join(ROOT, "shared", "src", "commonMain", "composeResources", "font", "plus_jakarta_sans_bold.ttf")

# ---- Warna (selaras dengan design system aplikasi) ----
TEAL = "#0F766E"          # primary terang
TEAL_STEM = "#0D9488"     # batang F di latar terang
TEAL_LIGHT = "#5EEAD4"    # primary gelap; batang F di latar teal/gelap
INK = "#1C1B19"
PAPER_TEXT_DARK = "#F2F0EC"
DARK_BG = "#121413"
WHITE = "#FFFFFF"

# ---- Geometri monogram (grid desain) ----
STROKE = 12.0
R = 8.5
S_PATH = f"M52 37 H30 A{R} {R} 0 0 0 30 54 H42 A{R} {R} 0 0 1 42 71 H20"
STEM = (62, 31, 10, 46, 5)        # x, y, w, h, r
ARMS = [(76, 31, 19, 12, 6), (76, 48, 16, 12, 6)]
BOUNDS = (14, 31, 95, 77)         # termasuk ketebalan garis S
CX = (BOUNDS[0] + BOUNDS[2]) / 2
CY = (BOUNDS[1] + BOUNDS[3]) / 2

ICON_SCALE = 0.78                 # ikon persegi penuh (iOS, desktop, PNG): tanda ±58% lebar ikon
# Launcher Android hanya menampilkan 72 dari 108 dp kanvas; tanda ±67% bagian yang terlihat agar setara ikon lain.
ADAPTIVE_SCALE = 0.6


def rrect_path(x, y, w, h, r):
    return (f"M{x + r} {y} H{x + w - r} A{r} {r} 0 0 1 {x + w} {y + r} V{y + h - r} "
            f"A{r} {r} 0 0 1 {x + w - r} {y + h} H{x + r} A{r} {r} 0 0 1 {x} {y + h - r} "
            f"V{y + r} A{r} {r} 0 0 1 {x + r} {y} Z")


def mark_svg_elems(main, stem):
    parts = [f'<path d="{S_PATH}" fill="none" stroke="{main}" stroke-width="{STROKE:g}" '
             f'stroke-linecap="round" stroke-linejoin="round"/>',
             f'<path d="{rrect_path(*STEM)}" fill="{stem}"/>']
    parts += [f'<path d="{rrect_path(*a)}" fill="{main}"/>' for a in ARMS]
    return "".join(parts)


def transform_for(canvas_cx, canvas_cy, scale):
    return canvas_cx - CX * scale, canvas_cy - CY * scale


def write(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(text)


def svg_doc(view_w, view_h, body, w=None, h=None):
    size = f' width="{w}" height="{h}"' if w else ""
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {view_w:g} {view_h:g}"{size}>'
            f'{body}</svg>\n')


# ---- 1. Master mark (latar transparan, ruang aman 8 unit) ----
PAD = 8
mw, mh = BOUNDS[2] - BOUNDS[0] + 2 * PAD, BOUNDS[3] - BOUNDS[1] + 2 * PAD


def mark_doc(main, stem):
    tx, ty = PAD - BOUNDS[0], PAD - BOUNDS[1]
    return svg_doc(mw, mh, f'<g transform="translate({tx:g} {ty:g})">{mark_svg_elems(main, stem)}</g>')


marks = {
    "studyflow-mark.svg": mark_doc(TEAL, TEAL_STEM),
    "studyflow-mark-on-dark.svg": mark_doc(WHITE, TEAL_LIGHT),
    "studyflow-mark-mono-black.svg": mark_doc(INK, INK),
    "studyflow-mark-mono-white.svg": mark_doc(WHITE, WHITE),
}
for name, doc in marks.items():
    write(os.path.join(BRAND, "logo", name), doc)

# ---- 2. Ikon aplikasi (persegi membulat 108, untuk dokumentasi, iOS, desktop) ----


def icon_doc(bg, main, stem, radius=24, full_bleed=False):
    tx, ty = transform_for(54, 54, ICON_SCALE)
    rect = f'<rect width="108" height="108" fill="{bg}"/>' if full_bleed else f'<rect width="108" height="108" rx="{radius}" fill="{bg}"/>'
    return svg_doc(108, 108, rect + f'<g transform="translate({tx:.3f} {ty:.3f}) scale({ICON_SCALE})">{mark_svg_elems(main, stem)}</g>')


write(os.path.join(BRAND, "logo", "studyflow-app-icon.svg"), icon_doc(TEAL, WHITE, TEAL_LIGHT))
write(os.path.join(BRAND, "logo", "studyflow-app-icon-dark.svg"), icon_doc(DARK_BG, WHITE, TEAL_LIGHT))
# iOS memakai persegi penuh tanpa sudut (sistem yang membulatkan) dan tanpa transparansi.
write(os.path.join(BRAND, "logo", "studyflow-app-icon-ios.svg"), icon_doc(TEAL, WHITE, TEAL_LIGHT, full_bleed=True))

# ---- 3. Logo horizontal dengan wordmark (teks diubah menjadi kurva) ----
font = TTFont(FONT)
glyph_set = font.getGlyphSet()
upem = font["head"].unitsPerEm
cap_height = font["OS/2"].sCapHeight
blob = hb.Blob.from_file_path(FONT)
hb_font = hb.Font(hb.Face(blob))
buf = hb.Buffer()
buf.add_str("StudyFlow")
buf.guess_segment_properties()
hb.shape(hb_font, buf, {"kern": True, "liga": True})

TEXT_CAP = 34.0                     # tinggi huruf kapital dalam unit desain (tanda tinggi 46)
scale = TEXT_CAP / cap_height
gap = 16.0
mark_w = BOUNDS[2] - BOUNDS[0]
text_x0 = PAD + mark_w + gap
baseline = PAD + (BOUNDS[3] - BOUNDS[1]) / 2 + TEXT_CAP / 2
glyph_order = font.getGlyphOrder()
pen = SVGPathPen(glyph_set)
x_cursor = 0
for info, pos in zip(buf.glyph_infos, buf.glyph_positions):
    gname = glyph_order[info.codepoint]
    # Font memakai sumbu y ke atas; SVG ke bawah.
    tpen = TransformPen(pen, (scale, 0, 0, -scale, text_x0 + (x_cursor + pos.x_offset) * scale, baseline - pos.y_offset * scale))
    glyph_set[gname].draw(tpen)
    x_cursor += pos.x_advance
text_w = x_cursor * scale
word_path = pen.getCommands()
hw = text_x0 + text_w + PAD
hh = mh


def horizontal_doc(main, stem, text_color):
    tx, ty = PAD - BOUNDS[0], PAD - BOUNDS[1]
    body = (f'<g transform="translate({tx:g} {ty:g})">{mark_svg_elems(main, stem)}</g>'
            f'<path d="{word_path}" fill="{text_color}"/>')
    return svg_doc(round(hw, 2), hh, body)


write(os.path.join(BRAND, "logo", "studyflow-horizontal.svg"), horizontal_doc(TEAL, TEAL_STEM, INK))
write(os.path.join(BRAND, "logo", "studyflow-horizontal-on-dark.svg"), horizontal_doc(WHITE, TEAL_LIGHT, PAPER_TEXT_DARK))
write(os.path.join(BRAND, "logo", "studyflow-horizontal-mono-black.svg"), horizontal_doc(INK, INK, INK))
write(os.path.join(BRAND, "logo", "studyflow-horizontal-mono-white.svg"), horizontal_doc(WHITE, WHITE, WHITE))

# ---- 4. Vector drawable Android (adaptive icon) dan untuk Compose ----


def vector_group(scale, tx, ty, main, stem, inner_indent="        "):
    i = inner_indent
    lines = [
        f'    <group android:scaleX="{scale}" android:scaleY="{scale}" android:translateX="{tx:.3f}" android:translateY="{ty:.3f}">',
        f'{i}<path android:pathData="{S_PATH}" android:strokeColor="{main}" android:strokeWidth="{STROKE:g}" '
        f'android:strokeLineCap="round" android:strokeLineJoin="round" android:fillColor="#00000000"/>',
        f'{i}<path android:pathData="{rrect_path(*STEM)}" android:fillColor="{stem}"/>',
    ]
    lines += [f'{i}<path android:pathData="{rrect_path(*a)}" android:fillColor="{main}"/>' for a in ARMS]
    lines.append("    </group>")
    return "\n".join(lines)


def vector_doc(viewport_w, viewport_h, width_dp, height_dp, group):
    return (f'<?xml version="1.0" encoding="utf-8"?>\n'
            f'<!-- Dibuat oleh docs/brand/build_brand.py; jangan diedit manual. -->\n'
            f'<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            f'    android:width="{width_dp}dp" android:height="{height_dp}dp"\n'
            f'    android:viewportWidth="{viewport_w:g}" android:viewportHeight="{viewport_h:g}">\n{group}\n</vector>\n')


tx, ty = transform_for(54, 54, ADAPTIVE_SCALE)
android_res = os.path.join(ROOT, "androidApp", "src", "main", "res")
write(os.path.join(BRAND, "android", "drawable", "ic_launcher_foreground.xml"),
      vector_doc(108, 108, 108, 108, vector_group(ADAPTIVE_SCALE, tx, ty, WHITE, TEAL_LIGHT)))
write(os.path.join(BRAND, "android", "drawable", "ic_launcher_monochrome.xml"),
      vector_doc(108, 108, 108, 108, vector_group(ADAPTIVE_SCALE, tx, ty, "#FFFFFFFF", "#FFFFFFFF")))
write(os.path.join(BRAND, "android", "values", "ic_launcher_background.xml"),
      '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n    <color name="ic_launcher_background">' + TEAL + '</color>\n</resources>\n')
adaptive = ('<?xml version="1.0" encoding="utf-8"?>\n'
            '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
            '    <background android:drawable="@color/ic_launcher_background"/>\n'
            '    <foreground android:drawable="@drawable/ic_launcher_foreground"/>\n'
            '    <monochrome android:drawable="@drawable/ic_launcher_monochrome"/>\n'
            '</adaptive-icon>\n')
write(os.path.join(BRAND, "android", "mipmap-anydpi-v26", "ic_launcher.xml"), adaptive)
write(os.path.join(BRAND, "android", "mipmap-anydpi-v26", "ic_launcher_round.xml"), adaptive)

# Untuk dipakai di dalam aplikasi (sidebar): tanda saja, viewport rapat.
mtx, mty = PAD - BOUNDS[0], PAD - BOUNDS[1]
write(os.path.join(BRAND, "compose", "studyflow_mark.xml"),
      vector_doc(mw, mh, round(mw / 2), round(mh / 2), vector_group(1, mtx, mty, TEAL, TEAL_STEM)))
write(os.path.join(BRAND, "compose", "studyflow_mark_on_dark.xml"),
      vector_doc(mw, mh, round(mw / 2), round(mh / 2), vector_group(1, mtx, mty, WHITE, TEAL_LIGHT)))

# Ikon aplikasi lengkap (latar teal membulat) untuk ikon jendela desktop.
itx, ity = transform_for(54, 54, ICON_SCALE)
app_icon_group = vector_group(ICON_SCALE, itx, ity, WHITE, TEAL_LIGHT)
app_icon_bg = f'    <path android:pathData="{rrect_path(0, 0, 108, 108, 24)}" android:fillColor="{TEAL}"/>'
app_icon_group = app_icon_bg + "\n" + app_icon_group
write(os.path.join(BRAND, "compose", "studyflow_app_icon.xml"), vector_doc(108, 108, 108, 108, app_icon_group))

# ---- 5. Daftar ekspor PNG (dirender lewat browser agar latar transparan) ----
exports = []
for size in (1024, 512, 256, 128):
    exports.append(("logo/studyflow-app-icon.svg", f"png/app-icon/studyflow-app-icon-{size}.png", size, size, True))
    exports.append(("logo/studyflow-app-icon-dark.svg", f"png/app-icon/studyflow-app-icon-dark-{size}.png", size, size, True))
for w in (1024, 512, 256):
    hgt = round(w * mh / mw)
    for name in ("studyflow-mark", "studyflow-mark-on-dark", "studyflow-mark-mono-black", "studyflow-mark-mono-white"):
        exports.append((f"logo/{name}.svg", f"png/mark/{name}-{w}.png", w, hgt, True))
for w in (1600, 800):
    hgt = round(w * hh / hw)
    for name in ("studyflow-horizontal", "studyflow-horizontal-on-dark", "studyflow-horizontal-mono-black", "studyflow-horizontal-mono-white"):
        exports.append((f"logo/{name}.svg", f"png/horizontal/{name}-{w}.png", w, hgt, True))
# iOS: satu ikon 1024 tanpa transparansi (Xcode 14+ menurunkan ukuran lain).
exports.append(("logo/studyflow-app-icon-ios.svg", "ios/AppIcon.appiconset/AppIcon-1024.png", 1024, 1024, False))
# Windows .ico dan ikon jendela desktop dibangun dari PNG ini.
for size in (16, 24, 32, 48, 64, 128, 256):
    exports.append(("logo/studyflow-app-icon.svg", f"desktop/png/icon-{size}.png", size, size, True))
write(os.path.join(BRAND, "exports.json"), json.dumps(exports, indent=1))

write(os.path.join(BRAND, "ios", "AppIcon.appiconset", "Contents.json"), json.dumps({
    "images": [{"filename": "AppIcon-1024.png", "idiom": "universal", "platform": "ios", "size": "1024x1024"}],
    "info": {"author": "xcode", "version": 1},
}, indent=2) + "\n")
print("ok", len(exports), "ekspor; wordmark lebar", round(text_w, 1))
