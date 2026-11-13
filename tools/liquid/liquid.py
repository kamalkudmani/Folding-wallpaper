#!/usr/bin/env python3
"""
Liquid wallpaper generator for Fold Motion.

Renders perfectly looping "liquid" videos: domain-warped fractal noise (the technique of
I. Quilez) gives the flowing shapes, the result is lit as a height field so it reads as glossy
liquid with specular highlights, and every time-dependent term runs on a circle, so frame N is
frame 0 and the clip loops without a seam (no cross-fade needed in the app).

Usage: liquid.py <name> <out.mp4> [--seconds 8] [--fps 30]
Requires numpy and ffmpeg. Output: 1296x1440 H.264, the same window geometry as the app's
built-in video packs (main screen = full width, cover = slice).
"""
import argparse
import math
import subprocess
import sys
from multiprocessing import Pool

import numpy as np

W, H = 1296, 1440          # final size (main screen aspect 0.9)
RW, RH = 540, 600          # render size; liquid is smooth, so it is upscaled with lanczos
SPAN = 1.25                # noise units across the short side
WARP = 2.6                 # domain-warp strength
HEIGHT = 11.0 * RW / 540    # height-field relief for the lighting (scaled with resolution)

PALETTES = {
    # name: (c0 deep, c1 body, c2 swirl, c3 highlight colour, specular strength, gloss sharpness, brightness)
    "aurora":  ((0.02, 0.05, 0.10), (0.05, 0.55, 0.55), (0.45, 0.35, 0.95), (0.70, 1.00, 0.95), 0.55, 42.0, 1.10),
    "gold":    ((0.03, 0.02, 0.01), (0.55, 0.33, 0.08), (0.95, 0.72, 0.30), (1.00, 0.95, 0.80), 0.95, 70.0, 1.15),
    "rose":    ((0.16, 0.04, 0.08), (0.86, 0.36, 0.44), (1.00, 0.76, 0.70), (1.00, 0.92, 0.90), 0.55, 40.0, 1.05),
    "abyss":   ((0.00, 0.01, 0.05), (0.01, 0.12, 0.38), (0.00, 0.62, 0.82), (0.60, 1.00, 1.00), 0.60, 50.0, 0.92),
    "lava":    ((0.03, 0.00, 0.01), (0.42, 0.04, 0.02), (1.00, 0.40, 0.05), (1.00, 0.85, 0.50), 0.45, 30.0, 0.95),
    "pearl":   ((0.02, 0.02, 0.03), (0.35, 0.36, 0.42), (0.85, 0.86, 0.92), (1.00, 1.00, 1.00), 0.85, 60.0, 1.05),
    "neon":    ((0.04, 0.01, 0.08), (0.80, 0.10, 0.75), (0.10, 0.85, 1.00), (1.00, 0.80, 1.00), 0.55, 44.0, 1.10),
    "emerald": ((0.00, 0.04, 0.03), (0.02, 0.45, 0.30), (0.35, 0.90, 0.65), (0.85, 1.00, 0.90), 0.65, 48.0, 1.05),
}

SEEDS = {"aurora": 11, "gold": 23, "rose": 37, "abyss": 41, "lava": 53, "pearl": 67, "neon": 71, "emerald": 89}


class Noise:
    """2D gradient (Perlin) noise, vectorised with numpy."""

    def __init__(self, seed):
        rng = np.random.default_rng(seed)
        p = rng.permutation(256).astype(np.int32)
        self.perm = np.concatenate([p, p])
        a = rng.random(256) * 2 * np.pi
        self.gx = np.cos(a)
        self.gy = np.sin(a)

    def __call__(self, x, y):
        xi = np.floor(x).astype(np.int32)
        yi = np.floor(y).astype(np.int32)
        xf = x - xi
        yf = y - yi
        xi &= 255
        yi &= 255
        x1 = (xi + 1) & 255
        y1 = (yi + 1) & 255
        perm, gx, gy = self.perm, self.gx, self.gy

        def g(ix, iy, dx, dy):
            h = perm[perm[ix] + iy]
            return gx[h] * dx + gy[h] * dy

        n00 = g(xi, yi, xf, yf)
        n10 = g(x1, yi, xf - 1, yf)
        n01 = g(xi, y1, xf, yf - 1)
        n11 = g(x1, y1, xf - 1, yf - 1)
        u = xf * xf * xf * (xf * (xf * 6 - 15) + 10)
        v = yf * yf * yf * (yf * (yf * 6 - 15) + 10)
        a = n00 + u * (n10 - n00)
        b = n01 + u * (n11 - n01)
        return a + v * (b - a)


def fbm(noise, x, y, octaves=4):
    total = np.zeros_like(x)
    amp = 0.5
    # Rotate each octave a little so the grid never shows.
    c, s = math.cos(0.6), math.sin(0.6)
    for _ in range(octaves):
        total += amp * noise(x, y)
        x, y = (c * x - s * y) * 2.02 + 3.1, (s * x + c * y) * 2.02 + 1.7
        amp *= 0.5
    return total


def frame(args):
    name, index, count = args
    c0, c1, c2, c3, spec_k, gloss, bright = [np.array(v) if isinstance(v, tuple) else v for v in PALETTES[name]]
    noise = Noise(SEEDS[name])
    theta = 2 * math.pi * index / count
    # Every time term is periodic in theta: the clip loops perfectly.
    def circ(r, phase, k=1):
        return r * math.cos(k * theta + phase), r * math.sin(k * theta + phase)

    ys, xs = np.mgrid[0:RH, 0:RW].astype(np.float64)
    aspect = RW / RH
    # Big, slow forms: a small span of noise space and few octaves read as silk, not as foil.
    x = xs / RW * SPAN * aspect
    y = ys / RH * SPAN
    # Small circles: the warp amplifies motion a lot, so these keep the flow calm.
    o1 = circ(0.13, 0.0)
    o2 = circ(0.11, 2.1)
    t1 = circ(0.19, 1.3)
    t2 = circ(0.16, 4.2, 2)
    qx = fbm(noise, x + o1[0], y + o1[1], 3)
    qy = fbm(noise, x + 5.2 + o2[0], y + 1.3 + o2[1], 3)
    rx = fbm(noise, x + WARP * qx + 1.7 + t1[0], y + WARP * qy + 9.2 + t1[1], 3)
    ry = fbm(noise, x + WARP * qx + 8.3 + t2[0], y + WARP * qy + 2.8 + t2[1], 3)
    # Raw fbm here spans about -0.25..0.15 (median -0.08); stretch it to 0..1 with fixed constants so
    # every frame uses the same mapping (no flicker, the loop stays exact).
    f = np.clip((fbm(noise, x + WARP * rx, y + WARP * ry, 3) + 0.06) / 0.38 + 0.5, 0, 1)

    # Colour: a three-stop ramp over the height (deep shadow -> body -> swirl), with the highlight
    # colour pulled into the eddies of the second warp.
    def smooth(a, b, v):
        k = np.clip((v - a) / (b - a), 0, 1)
        return k * k * (3 - 2 * k)

    t1 = smooth(0.18, 0.55, f)[..., None]
    t2 = smooth(0.50, 0.85, f)[..., None]
    col = c0 * (1 - t1) + c1 * t1
    col = col * (1 - t2) + c2 * t2
    e = (smooth(0.08, 0.26, np.abs(rx - ry)) * 0.5)[..., None]
    col = col * (1 - e) + c3 * e

    # Light the surface as a liquid: normal from the height field, a key light, a crisp gloss and a
    # tinted rim. Shadows are pushed deep so the forms read as glossy ribbons with real depth.
    h = f * HEIGHT
    # A light 3x3 blur of the height field removes pinched micro-ridges from the gloss.
    hp = np.pad(h, 1, mode="edge")
    h = sum(hp[1 + j:RH + 1 + j, 1 + i:RW + 1 + i] for j in (-1, 0, 1) for i in (-1, 0, 1)) / 9.0
    dy, dx = np.gradient(h)
    nx, ny, nz = -dx, -dy, np.ones_like(h)
    inv = 1.0 / np.sqrt(nx * nx + ny * ny + nz * nz)
    nx, ny, nz = nx * inv, ny * inv, nz * inv
    lx, ly, lz = -0.42, -0.58, 0.70
    ndl = np.clip(nx * lx + ny * ly + nz * lz, 0, 1)
    hx, hy, hz = lx, ly, lz + 1.0
    hn = math.sqrt(hx * hx + hy * hy + hz * hz)
    ndh = np.clip((nx * hx + ny * hy + nz * hz) / hn, 0, 1)
    spec = (ndh ** gloss) * spec_k
    soft = (ndh ** (gloss * 0.12)) * 0.18
    fres = np.clip(1 - nz, 0, 1) ** 1.5 * 0.55
    occl = smooth(0.05, 0.6, f)[..., None]
    col = col * (0.18 + 1.05 * ndl[..., None] ** 1.4) * (0.35 + 0.65 * occl) * bright
    col = col + (spec + soft)[..., None] * c3 + fres[..., None] * c2 * 0.6
    # Gentle vignette keeps the eye on the middle of both screens.
    vx = (xs / RW - 0.5)
    vy = (ys / RH - 0.5)
    vig = 1 - 0.35 * np.clip((vx * vx + vy * vy) * 2.2, 0, 1)
    col = col * vig[..., None]
    out = np.clip(col, 0, 1) ** (1 / 1.08)
    return (out * 255 + 0.5).astype(np.uint8).tobytes()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("name", choices=sorted(PALETTES))
    ap.add_argument("out")
    ap.add_argument("--seconds", type=float, default=10.0)
    ap.add_argument("--fps", type=int, default=30)
    ap.add_argument("--crf", type=int, default=21)
    ap.add_argument("--preview", action="store_true", help="write a 4-frame strip (png) instead of a video")
    a = ap.parse_args()
    count = int(a.seconds * a.fps)
    if a.preview:
        idx = [0, count // 4, count // 2, 3 * count // 4]
        with Pool() as pool:
            frames = pool.map(frame, [(a.name, i, count) for i in idx])
        strip = np.concatenate([np.frombuffer(f, np.uint8).reshape(RH, RW, 3) for f in frames], axis=1)
        p = subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-f", "rawvideo", "-pix_fmt", "rgb24",
                            "-s", f"{RW * 4}x{RH}", "-i", "-", "-vf", "scale=iw/2:ih/2", a.out], input=strip.tobytes())
        sys.exit(p.returncode)
    ff = subprocess.Popen(
        [
            "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
            "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{RW}x{RH}", "-r", str(a.fps), "-i", "-",
            "-vf", f"scale={W}:{H}:flags=lanczos,gblur=sigma=0.7,format=yuv420p",
            "-c:v", "libx264", "-preset", "slow", "-crf", str(a.crf), "-pix_fmt", "yuv420p",
            "-movflags", "+faststart", "-an", a.out,
        ],
        stdin=subprocess.PIPE,
    )
    with Pool() as pool:
        for data in pool.imap(frame, [(a.name, i, count) for i in range(count)], chunksize=4):
            ff.stdin.write(data)
    ff.stdin.close()
    sys.exit(ff.wait())


if __name__ == "__main__":
    main()
