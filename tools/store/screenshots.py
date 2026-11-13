#!/usr/bin/env python3
"""
Google Play screenshots from the app's own UI tour (app/build/screenshots/ui, written by
UiScreenshotTest): each screen in a phone outline on a dark background with a caption.
Phone shots are 1080x1920 (Play allows at most 2:1, the cover screen alone is 2.33:1); large-screen
shots are 1920x1080 with the open main screen.

Usage: tools/store/screenshots.py <ui screenshots dir> <out dir>
"""
import os
import sys
from PIL import Image, ImageDraw, ImageFilter, ImageFont

UI, OUT = sys.argv[1], sys.argv[2]
os.makedirs(OUT, exist_ok=True)
FONTS = {
    "en": ("/usr/share/fonts/opentype/inter/Inter-Bold.otf", "/usr/share/fonts/opentype/inter/Inter-Medium.otf"),
    "ar": ("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"),
}
PHONE = [
    ("01_home", {"en": ("Wallpapers made for the Fold", "Every one shown closed and open"), "ar": ("خلفيات صُنعت للفولد", "كل خلفية مطوية ومفتوحة")}),
    ("01b_discover_detail", {"en": ("See it fold before you choose", "Live 3D preview, ten fold animations"), "ar": ("شاهدها تنطوي قبل أن تختار", "معاينة ثلاثية الأبعاد حيّة، عشر حركات طيّ")}),
    ("03c_create_liquid", {"en": ("Live liquid you can touch", "Tilt it, tap it, half-open it"), "ar": ("سائل حيّ يمكنك لمسه", "أمِله، المسه، افتحه نصف فتحة")}),
    ("02b_create_style", {"en": ("Pick how it unfolds", "Ripple, Flow, Glass and more"), "ar": ("اختر كيف تتفتّح", "تموّج، انسياب، زجاج والمزيد")}),
    ("03b_create_video", {"en": ("Your photos and videos", "3D photos and video loops, made on your phone"), "ar": ("صورك وفيديوهاتك", "صور ثلاثية الأبعاد وفيديو، تُصنع على هاتفك")}),
]
LARGE = [
    ("01_home", {"en": ("Made for the open screen", "Four columns of live wallpapers"), "ar": ("مصمم للشاشة المفتوحة", "أربعة أعمدة من الخلفيات الحيّة")}),
    ("01b_discover_detail", {"en": ("Fold and unfold, live", "Preview every style on the wallpaper"), "ar": ("اطوِ وافتح مباشرة", "عاين كل حركة على الخلفية")}),
]
BG = (8, 9, 13)


def backdrop(size, accent):
    w, h = size
    img = Image.new("RGB", size, BG)
    glow = Image.new("RGB", size, BG)
    d = ImageDraw.Draw(glow)
    d.ellipse([w * 0.1, h * 0.25, w * 0.9, h * 1.05], fill=accent)
    glow = glow.filter(ImageFilter.GaussianBlur(min(w, h) * 0.18))
    return Image.blend(img, glow, 0.35)


def accent_of(shot):
    small = shot.resize((32, 32)).convert("RGB")
    px = sorted(small.getdata(), key=lambda c: max(c) - min(c))
    r, g, b = px[-1]
    return (int(r * 0.6), int(g * 0.6), int(b * 0.6))


def framed(shot, box_h, radius):
    w, h = shot.size
    scale = box_h / h
    shot = shot.resize((int(w * scale), int(h * scale)), Image.LANCZOS)
    w, h = shot.size
    bezel = max(10, int(w * 0.025))
    body = Image.new("RGBA", (w + 2 * bezel, h + 2 * bezel), (0, 0, 0, 0))
    d = ImageDraw.Draw(body)
    d.rounded_rectangle([0, 0, body.width - 1, body.height - 1], radius + bezel, fill=(12, 14, 19, 255), outline=(120, 135, 150, 255), width=3)
    mask = Image.new("L", shot.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, w - 1, h - 1], radius, fill=255)
    body.paste(shot, (bezel, bezel), mask)
    return body


def caption(img, lang, title, sub, top, max_w):
    bold, reg = FONTS[lang]
    d = ImageDraw.Draw(img)
    size = 76
    while True:
        f1 = ImageFont.truetype(bold, size)
        if d.textlength(title, font=f1, direction="rtl" if lang == "ar" else None) <= max_w or size < 40:
            break
        size -= 4
    f2 = ImageFont.truetype(reg, int(size * 0.55))
    kw = {"direction": "rtl", "language": "ar"} if lang == "ar" else {}
    cx = img.width // 2 if max_w >= img.width * 0.7 else int(max_w / 2 + 70)
    d.text((cx, top), title, font=f1, fill=(255, 255, 255), anchor="mt", **kw)
    d.text((cx, top + int(size * 1.35)), sub, font=f2, fill=(178, 186, 198), anchor="mt", **kw)


for lang in ("en", "ar"):
    for i, (name, text) in enumerate(PHONE, 1):
        path = os.path.join(UI, f"cover_{lang}_{name}.png")
        if not os.path.exists(path):
            continue
        shot = Image.open(path).convert("RGB")
        img = backdrop((1080, 1920), accent_of(shot))
        phone = framed(shot, 1460, 46)
        img.paste(phone, ((1080 - phone.width) // 2, 1920 - phone.height - 40), phone)
        caption(img, lang, *text[lang], 90, 980)
        img.save(os.path.join(OUT, f"phone_{lang}_{i}.png"))
    for i, (name, text) in enumerate(LARGE, 1):
        path = os.path.join(UI, f"main_{lang}_{name}.png")
        if not os.path.exists(path):
            continue
        shot = Image.open(path).convert("RGB")
        img = backdrop((1920, 1080), accent_of(shot))
        phone = framed(shot, 960, 40)
        img.paste(phone, (1920 - phone.width - 70, (1080 - phone.height) // 2), phone)
        caption(img, lang, *text[lang], 420, 1920 - phone.width - 180)
        img.save(os.path.join(OUT, f"large_{lang}_{i}.png"))
print("done")
