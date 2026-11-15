#!/usr/bin/env python3
"""
Captions and encodes the promo frames from PromoRender into a vertical video (1080x1920, H.264).
Captions are drawn with Pillow (with Arabic shaping), faded in and out, then piped to ffmpeg.

Frames: ./gradlew :app:testDebugUnitTest --tests '*PromoRender*' -Dfoldmotion.promoOut=<dir>
Usage:  tools/promo/promo.py <frames dir> <out.mp4> en|ar
"""
import os
import subprocess
import sys
from PIL import Image, ImageDraw, ImageFont

FRAMES, OUT, LANG = sys.argv[1], sys.argv[2], sys.argv[3] if len(sys.argv) > 3 else "en"
FPS = int(open(os.path.join(FRAMES, "fps.txt")).read().strip())
BOLD = "/usr/share/fonts/opentype/inter/Inter-Bold.otf" if LANG == "en" else "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"
REG = "/usr/share/fonts/opentype/inter/Inter-Medium.otf" if LANG == "en" else "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"
TEXT = {
    "en": ["Liquid glass for your Fold", "Open it, and the drops flow apart", "Tap it. Tilt it. It's glass.",
           "Close it, and they gather again", "The motion is in the picture", "Fold Motion", "Private by design. No account, no ads.",
           "Real places, alive", "The sun rises as you open it"],
    "ar": ["زجاج سائل لهاتف الفولد", "افتحه… فتنساب القطرات متفرقة", "المسه. أمِله. إنه زجاج.",
           "أغلقه… فتتجمع من جديد", "الحركة داخل الصورة نفسها", "Fold Motion", "خصوصية تامة. بلا حساب ولا إعلانات.",
           "أماكن حقيقية، حيّة", "تشرق الشمس حين تفتحه"],
}[LANG]
# (text index, start s, end s, y, size, bold) following PromoRender's timeline.
CAPTIONS = [(0, 0.2, 2.5, 150, 60, True), (1, 2.7, 5.6, 150, 58, True), (2, 5.8, 10.1, 150, 60, True),
            (3, 10.3, 12.6, 150, 58, True), (4, 12.8, 15.5, 150, 58, True),
            (8, 15.7, 18.0, 150, 58, True), (7, 18.2, 23.0, 150, 60, True),
            (5, 23.3, 26.1, 820, 124, True), (6, 23.6, 26.1, 990, 46, False)]

fonts = {}


def font(size, bold):
    key = (size, bold)
    if key not in fonts:
        fonts[key] = ImageFont.truetype(BOLD if bold else REG, size)
    return fonts[key]


def alpha(t, a, b):
    if t < a or t > b:
        return 0.0
    return min(1.0, (t - a) / 0.4, (b - t) / 0.4)


files = sorted(f for f in os.listdir(FRAMES) if f.endswith(".png"))
ff = subprocess.Popen(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", "1080x1920",
                       "-r", str(FPS), "-i", "-", "-c:v", "libx264", "-preset", "slow", "-crf", "20", "-pix_fmt", "yuv420p",
                       "-movflags", "+faststart", "-an", OUT], stdin=subprocess.PIPE)
for i, name in enumerate(files):
    t = i / FPS
    frame = Image.open(os.path.join(FRAMES, name)).convert("RGB")
    layer = Image.new("RGBA", frame.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    for idx, a, b, y, size, bold in CAPTIONS:
        k = alpha(t, a, b)
        if k <= 0:
            continue
        text = TEXT[idx]
        kw = {"direction": "rtl", "language": "ar"} if LANG == "ar" and idx != 5 else {}
        d.text((540, y), text, font=font(size, bold), fill=(255, 255, 255, int(255 * k)), anchor="mt", **kw)
    frame = Image.alpha_composite(frame.convert("RGBA"), layer).convert("RGB")
    ff.stdin.write(frame.tobytes())
ff.stdin.close()
sys.exit(ff.wait())
