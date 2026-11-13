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
    "en": ["Live wallpapers for foldables", "Unfold it, and it pours across", "Tap it. Tilt it. It's liquid.",
           "Ten fold animations", "Half open: it pools in the fold", "Fold Motion", "Private by design. No account, no ads."],
    "ar": ["خلفيات حيّة للهواتف القابلة للطي", "افتحه… فينسكب السائل", "المسه. أمِله. إنه سائل.",
           "عشر حركات طيّ", "نصف مفتوح: يتجمّع عند خط الطي", "Fold Motion", "خصوصية تامة. بلا حساب ولا إعلانات."],
}[LANG]
# (text index, start s, end s, y, size, bold) following PromoRender's timeline.
CAPTIONS = [(0, 0.2, 3.0, 150, 60, True), (1, 3.2, 6.0, 150, 60, True), (2, 6.2, 10.0, 150, 60, True),
            (3, 10.1, 12.7, 150, 60, True), (4, 12.8, 16.0, 150, 58, True), (5, 16.2, 19.1, 820, 124, True),
            (6, 16.5, 19.1, 990, 46, False)]

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
