package com.foldmotion.wallpapers.render

import android.graphics.Color
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import com.foldmotion.core.motion.GlassLayout
import com.foldmotion.core.pack.LiquidSpec
import com.foldmotion.core.scene.WorldRect
import kotlin.math.PI

/**
 * Realistic live scenes (renderer 8), each an AGSL shader in world space (the main screen's
 * height is 1, its width about 0.9; the cover shows the slice of it on the right), so the cover
 * and the main screen show one continuous place.
 *
 * Their fold motion is the picture itself, driven by one value, `open` (0 when the phone is
 * closed, 1 when it is open, a little past 1 while a spring settles):
 *  - Ocean: the sun (or moon) rises over the horizon, its path of light on the waves with it.
 *  - Sky: the clouds part around the sun and drift to the left; sunbeams come through.
 *  - Aurora: the lights unfurl across the sky from the right, mirrored in the lake.
 *  - Rain: the fogged window clears from the right, showing the city lights and the drops.
 *  - Space: sunrise over a planet: the sun climbs over its limb and daylight sweeps across it.
 * Between folds the place keeps living: waves roll, clouds drift, the lights ripple, drops run
 * down the glass, the planet turns. Every motion runs on whole turns per ten minutes, so the
 * picture repeats exactly and time never loses precision.
 */
object LiveScenes {

    /** Screen pixels per scene pixel (they are soft, except the rain's drops). */
    fun downscale(look: String) = if (look == LiquidSpec.LOOK_RAIN) 1.8f else 2.2f

    fun agsl(look: String): String = COMMON + when (look) {
        LiquidSpec.LOOK_OCEAN -> OCEAN
        LiquidSpec.LOOK_SKY -> SKY
        LiquidSpec.LOOK_AURORA -> AURORA
        LiquidSpec.LOOK_RAIN -> RAIN
        else -> SPACE
    }

    /** How far the scene has opened for this frame: the same spring as the glass drops. */
    fun openOf(frame: LiquidFrame): Float = GlassLayout.spreadOf(0, 1, frame.kind, frame.progress)

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun configure(s: RuntimeShader, spec: LiquidSpec, world: WorldRect, w: Int, h: Int, frame: LiquidFrame) {
        s.setFloatUniform("origin", world.left, world.top)
        s.setFloatUniform("perPx", world.width / w, world.height / h)
        s.setFloatUniform("time", ((frame.timeMs / 1000.0) % LiquidSpec.PERIOD_SECONDS).toFloat())
        s.setFloatUniform("tau", (2.0 * PI / LiquidSpec.PERIOD_SECONDS).toFloat())
        s.setFloatUniform("open", openOf(frame))
        val tilt = spec.tilt
        s.setFloatUniform("tilt", if (tilt) frame.tiltX.coerceIn(-1f, 1f) else 0f, if (tilt) frame.tiltY.coerceIn(-1f, 1f) else 0f)
        s.setFloatUniform("seedOff", (spec.seed * 0.618034f) % 61f + 3f, (spec.seed * 0.414214f) % 53f + 5f)
        s.setFloatUniform("variant", LiquidSpec.SCENE_FORMS[spec.look]?.indexOf(spec.form)?.coerceAtLeast(0)?.toFloat() ?: 0f)
        s.setFloatUniform("bright", spec.brightness)
        s.setFloatUniform("amount", (spec.warp / 5f).coerceIn(0f, 1f))
        fun color(i: Int) = try {
            Color.parseColor(spec.palette[i])
        } catch (e: Exception) {
            Color.GRAY
        }
        s.setColorUniform("c0", color(0))
        s.setColorUniform("c1", color(1))
        s.setColorUniform("c2", color(2))
        s.setColorUniform("c3", color(3))
    }

    private const val COMMON = """
uniform float2 origin;
uniform float2 perPx;
uniform float time;
uniform float tau;
uniform float open;
uniform float2 tilt;
uniform float2 seedOff;
uniform float variant;
uniform float bright;
uniform float amount;
layout(color) uniform half4 c0;
layout(color) uniform half4 c1;
layout(color) uniform half4 c2;
layout(color) uniform half4 c3;

float hash12(float2 p) {
    float3 p3 = fract(float3(p.x, p.y, p.x) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

float2 hash22(float2 p) {
    float3 p3 = fract(float3(p.x, p.y, p.x) * float3(0.1031, 0.1030, 0.0973));
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.xx + p3.yz) * p3.zy) * 2.0 - 1.0;
}

float gnoise(float2 p) {
    float2 i = floor(p);
    float2 f = p - i;
    float2 u = f * f * f * (f * (f * 6.0 - 15.0) + 10.0);
    float a = dot(hash22(i), f);
    float b = dot(hash22(i + float2(1.0, 0.0)), f - float2(1.0, 0.0));
    float c = dot(hash22(i + float2(0.0, 1.0)), f - float2(0.0, 1.0));
    float d = dot(hash22(i + float2(1.0, 1.0)), f - float2(1.0, 1.0));
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

float fbm4(float2 p) {
    float t = 0.0;
    float a = 0.5;
    for (int i = 0; i < 4; i++) {
        t += a * gnoise(p);
        p = float2(0.8 * p.x - 0.6 * p.y, 0.6 * p.x + 0.8 * p.y) * 2.03 + float2(1.7, 9.2);
        a *= 0.5;
    }
    return t;
}

float fbm6(float2 p) {
    float t = 0.0;
    float a = 0.5;
    for (int i = 0; i < 6; i++) {
        t += a * gnoise(p);
        p = float2(0.8 * p.x - 0.6 * p.y, 0.6 * p.x + 0.8 * p.y) * 2.03 + float2(1.7, 9.2);
        a *= 0.5;
    }
    return t;
}

// Stars on a world grid, twinkling on whole turns.
float stars(float2 w, float density) {
    float2 g = w * 130.0;
    float2 id = floor(g);
    float2 f = fract(g) - 0.5;
    float h = hash12(id + seedOff);
    float on = step(1.0 - density, h);
    float2 off = float2(hash12(id + 7.1), hash12(id + 3.3)) - 0.5;
    float d = length(f - off * 0.6);
    float tw = 0.65 + 0.35 * sin(time * tau * floor(15.0 + 40.0 * fract(h * 7.0)) + h * 50.0);
    float size = 0.10 + 0.10 * fract(h * 31.0);
    return on * smoothstep(size, 0.0, d) * tw * (0.35 + 0.65 * fract(h * 13.0));
}

float3 finish(float3 col, float2 xy) {
    col *= bright;
    float n = fract(sin(dot(xy, float2(12.9898, 78.233))) * 43758.5453);
    col += (n - 0.5) * (1.5 / 255.0);
    return clamp(col, 0.0, 1.0);
}
"""

    /** Ocean: a camera above the sea; sky with a cloud deck; waves from a sum of swells. */
    private const val OCEAN = """
const float HZ = 0.60;
const float CX = 0.45;
const float FOC = 0.95;

float3 sunDir() {
    float sy = mix(0.010, 0.150, open) + tilt.y * 0.008;
    return normalize(float3(0.25 - tilt.x * 0.01, sy, FOC));
}

float3 skyCol(float3 d, float3 sd, float night) {
    float e = clamp(d.y, 0.0, 1.0);
    float3 hor = float3(c1.rgb);
    float3 col = mix(hor, float3(c0.rgb), pow(clamp(e / 0.55, 0.0, 1.0), 0.55));
    float s = max(dot(d, sd), 0.0);
    float3 sun = float3(c2.rgb);
    col += sun * (pow(s, 10.0) * 0.24 + pow(s, 140.0) * 0.45) * (1.0 - 0.55 * night);
    col = mix(col, mix(hor, sun, 0.25 * (1.0 - night)), exp(-e * 22.0) * 0.45);
    return col;
}

float cloudAt(float3 d) {
    if (d.y <= 0.002) return 0.0;
    float2 q = d.xz / d.y * 0.16 + seedOff + 3.5 * float2(cos(time * tau), sin(time * tau));
    float n = fbm6(q * float2(1.0, 2.4) + 0.4 * fbm4(q * 1.3));
    float lo = 0.46 - 0.50 * amount;
    return smoothstep(lo, lo + 0.30, n + 0.3) * smoothstep(0.0, 0.10, d.y);
}

float3 cloudCol(float3 d, float3 sd, float night) {
    float glow = pow(max(dot(d, sd), 0.0), 3.0);
    float hi = clamp(d.y / 0.4, 0.0, 1.0);
    float3 base = mix(float3(c1.rgb) * 0.9, float3(c0.rgb) * 0.9 + float3(c1.rgb) * 0.2, hi);
    float3 lit = mix(base, float3(c2.rgb) * 1.1, 0.15 + 0.75 * glow);
    if (night > 0.5) lit = mix(float3(c0.rgb) * 1.9 + 0.02, float3(c2.rgb) * 0.55, glow);
    return lit;
}

half4 main(float2 xy) {
    float2 w = origin + xy * perPx;
    float night = step(0.5, variant);
    float3 d = normalize(float3(w.x - CX, HZ - w.y + tilt.y * 0.008, FOC));
    float3 sd = sunDir();
    float3 col;
    if (d.y > 0.0) {
        col = skyCol(d, sd, night);
        col += night * stars(w, 0.05) * smoothstep(0.03, 0.25, d.y);
        float s = dot(d, sd);
        float size = night > 0.5 ? 0.00050 : 0.00034;
        float disk = smoothstep(1.0 - size * 1.25, 1.0 - size, s);
        col = mix(col, float3(c2.rgb) * (night > 0.5 ? 1.05 : 1.7), disk);
        float cl = cloudAt(d);
        col = mix(col, cloudCol(d, sd, night), cl * 0.9);
    } else {
        float t = 0.05 / -d.y;
        float2 P = float2(d.x, d.z) * t * 18.0;
        float2 g = float2(0.0);
        for (int i = 0; i < 8; i++) {
            float fi = float(i);
            float k = 1.5 * pow(1.45, fi);
            float a = (fi < 1.0 ? 0.045 : 0.075) / k * exp(-t * k * 0.05);
            float ang = (fract(fi * 0.618 + 0.13) - 0.5) * 2.4;
            float2 dir = float2(sin(ang), cos(ang));
            float ph = dot(dir, P) * k + time * tau * floor(55.0 + 20.0 * fi) + fi * 1.7;
            g += a * k * cos(ph) * dir;
        }
        float e = 0.08;
        float2 R = P * float2(4.0, 6.0) + seedOff + float2(0.0, time * tau * 60.0 * 0.05);
        float n0 = fbm4(R);
        g += float2(fbm4(R + float2(e, 0.0)) - n0, fbm4(R + float2(0.0, e)) - n0) / e * 0.02 * exp(-t * 0.7);
        g *= exp(-t * 0.22);
        float3 n = normalize(float3(-g.x, 1.0, -g.y));
        float3 r = reflect(d, n);
        r.y = abs(r.y) + 0.002;
        float3 refl = skyCol(r, sd, night);
        refl = mix(refl, cloudCol(r, sd, night), cloudAt(r) * 0.75);
        float fres = 0.02 + 0.98 * pow(1.0 - max(dot(-d, n), 0.0), 5.0);
        float3 deep = float3(c3.rgb) * (0.7 + 0.3 * n.y);
        col = mix(deep, refl, clamp(fres * 1.1, 0.0, 1.0));
        float sp = max(dot(r, sd), 0.0);
        col += float3(c2.rgb) * (pow(sp, 350.0) * 2.2 + pow(sp, 25.0) * 0.15) * (night > 0.5 ? 0.75 : 1.0) * (1.0 - 0.6 * amount * amount);
        col = mix(col, skyCol(float3(d.x, 0.0, d.z), sd, night), exp(-(-d.y) * 55.0) * 0.8);
    }
    return half4(half3(finish(col, xy)), 1.0);
}
"""

    /** Sky: a cloudscape over low hills; the clouds part around the sun as the phone opens. */
    private const val SKY = """
const float HZ = 0.86;
const float CX = 0.45;
const float FOC = 1.0;

float2 sunScreen() {
    return float2(0.70 + tilt.x * 0.006, mix(0.50, 0.40, clamp(open, 0.0, 1.2)) + tilt.y * 0.006);
}

float density(float2 q, float2 sunQ) {
    float n = fbm6(q + 0.7 * float2(fbm4(q * 0.7 + 3.1), fbm4(q * 0.7 + 7.7)));
    // Opening parts the clouds around the sun.
    float part = clamp(open, 0.0, 1.2) * 0.20 * exp(-dot(q - sunQ, q - sunQ) * 2.5);
    float lo = 0.12 - 0.55 * amount + part;
    return smoothstep(lo, lo + 0.30, n);
}

half4 main(float2 xy) {
    float2 w = origin + xy * perPx;
    float night = step(0.5, variant);
    float2 ss = sunScreen();
    float3 d = normalize(float3(w.x - CX, HZ - w.y, FOC));
    float3 sd = normalize(float3(ss.x - CX, HZ - ss.y, FOC));
    float e = clamp(d.y, 0.0, 1.0);
    float3 col = mix(float3(c1.rgb), float3(c0.rgb), pow(clamp(e / 0.75, 0.0, 1.0), 0.6));
    float s = max(dot(d, sd), 0.0);
    col += float3(c2.rgb) * (pow(s, 14.0) * 0.22 + pow(s, 300.0) * 0.5) * (1.0 - 0.5 * night);
    col += night * stars(w, 0.06) * smoothstep(0.05, 0.3, e);
    float disk = smoothstep(0.99972, 0.99982, s);
    col = mix(col, float3(c2.rgb) * (night > 0.5 ? 1.0 : 1.5), disk);

    // Clouds on a deck above, drifting left as the phone opens and slowly between folds.
    float cl = 0.0;
    float3 cc = float3(0.0);
    if (d.y > 0.004) {
        float2 drift = float2(clamp(open, 0.0, 1.2) * 0.9, 0.0) + 4.0 * float2(cos(time * tau), sin(time * tau));
        float2 q = d.xz / d.y * 0.42 + seedOff + drift;
        float2 sunQ = sd.xz / sd.y * 0.42 + seedOff + drift;
        cl = density(q, sunQ);
        float2 toSun = normalize(sunQ - q + 0.0001) * 0.08;
        float lit = clamp((cl - density(q + toSun, sunQ)) * 1.4 + 0.45, 0.0, 1.0);
        float rim = pow(s, 8.0);
        float3 shade = mix(float3(c0.rgb) * 0.6 + float3(c1.rgb) * 0.25, float3(c1.rgb) * 0.6, 0.3);
        float3 light = mix(float3(c1.rgb) * 1.05, float3(c2.rgb), 0.3 + 0.55 * rim);
        if (night > 0.5) {
            shade = float3(c0.rgb) * 1.5 + 0.015;
            light = mix(float3(c1.rgb) * 1.3, float3(c2.rgb) * 0.65, rim);
        }
        float edgeGlow = (1.0 - cl) * rim;
        cc = mix(shade, light, lit) + float3(c2.rgb) * edgeGlow * 0.8;
        cl *= smoothstep(0.004, 0.09, d.y);
    }
    col = mix(col, cc, cl * 0.95);

    // Sunbeams through the gaps, stronger as the clouds part.
    float2 v = w - ss;
    float ang = atan(v.y, v.x);
    float beams = smoothstep(0.2, 0.9, 0.5 + 0.5 * gnoise(float2(ang * 5.0, 3.0)) + 0.3 * gnoise(float2(ang * 11.0, 9.0)));
    float fall = exp(-length(v) * 2.2);
    col += float3(c2.rgb) * beams * fall * (0.01 + 0.07 * clamp(open, 0.0, 1.0)) * (1.0 - night) * (1.0 - cl * 0.6);

    // Low hills, hazy with distance.
    float hill = HZ - 0.035 - 0.05 * (0.5 + fbm4(float2(w.x * 2.6, 1.3) + seedOff));
    float near = HZ + 0.03 - 0.06 * (0.5 + fbm4(float2(w.x * 4.5, 7.7) + seedOff));
    if (w.y > hill) {
        float3 land = mix(float3(c3.rgb), float3(c1.rgb), 0.35);
        col = mix(col, land, smoothstep(hill, hill + 0.004, w.y));
    }
    if (w.y > near) {
        col = mix(col, float3(c3.rgb) * 0.7, smoothstep(near, near + 0.004, w.y));
    }
    return half4(half3(finish(col, xy)), 1.0);
}
"""

    /** Aurora: curtains of light over snowy peaks and a forest, mirrored in a still lake. */
    private const val AURORA = """
const float LAKE = 0.80;

float ridgeY(float x) {
    float r = 1.0 - abs(gnoise(float2(x * 2.2, 1.7) + seedOff));
    float r2 = 1.0 - abs(gnoise(float2(x * 6.0, 4.3) + seedOff));
    return 0.76 - 0.19 * r * r * r - 0.035 * r2 * r2;
}

float3 above(float2 p) {
    float3 col = mix(float3(c0.rgb), float3(c1.rgb), smoothstep(0.0, LAKE, p.y));
    col += stars(p, 0.07) * (1.0 - smoothstep(0.3, 0.6, p.y));
    float edge = mix(0.50, -0.30, clamp(open, 0.0, 1.2));
    float unfurl = smoothstep(edge - 0.12, edge + 0.12, p.x);
    float3 au = float3(0.0);
    for (int j = 0; j < 2; j++) {
        float fj = float(j);
        float ph = time * tau * floor(5.0 + 3.0 * fj);
        float base = 0.30 + 0.10 * fj
            + 0.06 * sin(p.x * (4.0 + 2.5 * fj) + fj * 1.9 + ph)
            + 0.07 * gnoise(float2(p.x * 3.5 + fj * 5.0, fj * 3.0) + 0.5 * float2(cos(time * tau * 2.0), sin(time * tau * 2.0)))
            + 0.02 * gnoise(float2(p.x * 11.0, fj * 5.0 + 2.0));
        float up = base - p.y;
        float hh = 0.035 + 0.02 * fj;
        float along = smoothstep(-0.25, 0.45, gnoise(float2(p.x * 3.0 + fj * 3.0 + time * tau * 3.0, fj * 7.0)));
        float rays = smoothstep(-0.35, 0.65, gnoise(float2(p.x * 46.0 + fj * 7.0 + 2.0 * sin(time * tau * 9.0 + p.x * 3.0), p.y * 1.2)));
        float lower = smoothstep(-0.004 - 0.02 * (1.0 - along), 0.006, up);
        float curtain = lower * (exp(-max(up, 0.0) / hh) * (0.35 + 0.65 * rays) + 0.45 * rays * exp(-max(up, 0.0) / (hh * 4.0)));
        float3 cj = mix(float3(c2.rgb), float3(c3.rgb), smoothstep(0.02, 0.20, up) * 0.9);
        au += cj * curtain * along * (1.0 - 0.3 * fj);
    }
    col += au * unfurl * (0.8 + 0.6 * amount);
    // The lights' glow on the low sky.
    col += float3(c2.rgb) * 0.06 * unfurl * smoothstep(0.35, 0.66, p.y);
    float ry = ridgeY(p.x);
    if (p.y > ry) {
        float depth = p.y - ry;
        float facet = smoothstep(-0.4, 0.4, gnoise(float2(p.x * 14.0, p.y * 5.0) + seedOff));
        float3 rock = float3(c0.rgb) * 0.5 + float3(c1.rgb) * 0.12 * facet;
        // Faint snow near the summits, broken by gullies, catching the lights.
        float gully = smoothstep(0.05, 0.45, gnoise(float2(p.x * 30.0, p.y * 10.0) + seedOff));
        float snow = smoothstep(0.045, 0.0, depth) * gully;
        float3 snowc = mix(float3(0.30, 0.34, 0.42), float3(c2.rgb) * 0.45 + 0.08, 0.2 + 0.4 * unfurl);
        col = mix(rock, snowc, snow * 0.55);
    }
    float cell = floor(p.x * 90.0);
    float fx = fract(p.x * 90.0) - 0.5;
    float hgt = 0.012 + 0.03 * hash12(float2(cell, 2.0) + seedOff);
    float tree = LAKE - 0.004 - hgt * (1.0 - abs(fx) * 2.0);
    if (p.y > min(tree, LAKE - 0.005)) col = float3(c0.rgb) * 0.3;
    return col;
}

half4 main(float2 xy) {
    float2 w = origin + xy * perPx + float2(tilt.x, tilt.y) * 0.004;
    float3 col;
    if (w.y < LAKE) {
        col = above(w);
    } else {
        float depth = w.y - LAKE;
        float wob = 0.003 * sin(depth * 400.0 / (depth * 6.0 + 0.2) + time * tau * 40.0) * smoothstep(0.0, 0.05, depth);
        float2 m = float2(w.x + wob, LAKE - depth * 1.02);
        col = above(m) * (0.8 - 0.25 * smoothstep(0.0, 0.2, depth));
        col = mix(col, float3(c0.rgb) * 0.5, 0.1);
        col += 0.025 * smoothstep(0.5, 1.0, gnoise(float2(w.x * 80.0, depth * 300.0) + seedOff));
    }
    return half4(half3(finish(col, xy)), 1.0);
}
"""

    /** Rain: city lights out of focus behind a wet window; fog clears as the phone opens. */
    private const val RAIN = """
float3 bokeh(float2 p, float blur) {
    float3 col = mix(float3(c0.rgb), float3(c1.rgb), smoothstep(0.1, 1.0, p.y));
    float glow = smoothstep(0.0, 0.5, fbm4(p * 2.2 + seedOff) + 0.15);
    col += float3(c1.rgb) * glow * 0.45 * smoothstep(0.15, 0.95, p.y);
    for (int L = 0; L < 3; L++) {
        float fl = float(L);
        float sc = 4.5 + 3.5 * fl;
        float2 g = p * sc + float2(fl * 3.7, fl * 1.3);
        float2 id0 = floor(g);
        for (int oy = -1; oy <= 1; oy++) {
            for (int ox = -1; ox <= 1; ox++) {
                float2 id = id0 + float2(float(ox), float(oy));
                float h = hash12(id + fl * 17.0 + seedOff);
                float2 c = id + 0.5 + (float2(hash12(id + 1.3), hash12(id + 4.1)) - 0.5) * 0.8;
                float r = (0.16 + 0.24 * hash12(id + 9.2)) * (1.0 + blur * 0.6);
                float dd = length(g - c);
                float soft = r * (0.05 + 0.45 * blur);
                float disk = smoothstep(r, r - soft, dd) * (0.75 + 0.25 * smoothstep(r * 0.5, r, dd));
                float pick = hash12(id + 2.2);
                float3 lc = pick < 0.45 ? float3(c2.rgb) : (pick < 0.85 ? float3(c3.rgb) : float3(1.0, 0.92, 0.8));
                float2 cw = c / sc;
                float street = smoothstep(0.2, 0.8, cw.y) * smoothstep(-0.15, 0.3, gnoise(cw * 3.0 + seedOff));
                float on = step(0.55 + 0.1 * fl, h) * street;
                float pulse = 0.85 + 0.15 * sin(time * tau * floor(10.0 + 30.0 * h) + h * 20.0);
                col += lc * disk * on * pulse * (0.45 + 0.6 * fract(h * 9.0)) / (1.0 + blur * 0.7);
            }
        }
    }
    return col;
}

// The drop over this point: (lens direction x, y, radius, coverage).
float4 drops(float2 p) {
    float4 res = float4(0.0);
    float2 g = p / 0.045;
    float2 id = floor(g);
    float h = hash12(id + seedOff + 11.0);
    float2 c = id + 0.5 + (float2(hash12(id + 5.5), hash12(id + 8.8)) - 0.5) * 0.6;
    float r = 0.12 + 0.26 * hash12(id + 2.9);
    float2 v = (g - c) / r;
    float m = step(0.62 - 0.35 * amount, h) * smoothstep(1.0, 0.82, length(v));
    res = float4(v, r * 0.045, m);
    float g2 = floor(p.x / 0.06);
    float hc = hash12(float2(g2, 3.0) + seedOff);
    float speed = floor(18.0 + 30.0 * hc);
    float y = fract(time / 600.0 * speed + hc) * 1.5 - 0.25;
    float x = (g2 + 0.5 + 0.25 * sin(p.y * 22.0 + hc * 9.0)) * 0.06;
    float2 dv = (p - float2(x, y)) / float2(0.009, 0.012);
    float big = smoothstep(1.0, 0.8, length(dv)) * step(0.35, hc);
    if (big > res.w) res = float4(dv, 0.01, big);
    float above = y - p.y;
    if (above > 0.0 && above < 0.3 && hc > 0.35) {
        float ty = fract(p.y / 0.018);
        float th = hash12(float2(g2, floor(p.y / 0.018)) + seedOff);
        float2 tv = float2((p.x - x) / 0.0035, (ty - 0.5) / 0.22);
        float tm = smoothstep(1.0, 0.75, length(tv)) * step(0.45, th) * (1.0 - above / 0.3);
        if (tm > res.w) res = float4(tv, 0.0035, tm);
    }
    return res;
}

half4 main(float2 xy) {
    float2 w = origin + xy * perPx;
    float edge = mix(0.48, -0.30, clamp(open, 0.0, 1.2));
    float clear = smoothstep(edge - 0.08, edge + 0.08, w.x + 0.03 * gnoise(w * 8.0 + seedOff));
    float2 par = float2(tilt.x, tilt.y) * 0.01;
    float3 col = bokeh(w + par, mix(1.4, 0.6, clear));
    float4 dr = drops(w);
    float wet = dr.w * clear;
    if (wet > 0.0) {
        // A drop is a little lens: the city seen through it, upside down and sharper.
        float3 seen = bokeh(w + par - dr.xy * dr.z * 3.0 - float2(0.0, 0.03), 0.12) * 1.1 + 0.025;
        float len = length(dr.xy);
        float ring = smoothstep(0.55, 1.0, len);
        float shine = exp(-dot(dr.xy - float2(-0.25, -0.4), dr.xy - float2(-0.25, -0.4)) * 14.0);
        float low = exp(-dot(dr.xy - float2(0.1, 0.55), dr.xy - float2(0.1, 0.55)) * 8.0);
        seen = seen * (1.0 - 0.45 * ring) + 0.35 * shine + float3(c1.rgb) * 0.4 * low;
        col = mix(col, seen, wet);
    }
    float lum = dot(col, float3(0.299, 0.587, 0.114));
    float3 fogged = mix(col, float3(lum) * 0.7 + float3(c1.rgb) * 0.35 + 0.05, 0.45);
    fogged += 0.025 * gnoise(w * 60.0 + seedOff);
    col = mix(fogged, col, clear);
    return half4(half3(finish(col, xy)), 1.0);
}
"""

    /** Space: the top of a planet below, a nebula and stars above; sunrise over its limb. */
    private const val SPACE = """
const float2 PC = float2(0.45, 1.58);
const float PR = 1.0;

float3 surface(float3 n, float v) {
    // Texture with its axis towards the viewer, so the visible cap has no pole to pinch.
    float lon = atan(n.x, n.y) + time * tau * 2.0;
    float2 q = float2(lon * 2.6, n.z * 3.4) + seedOff;
    float3 base = float3(c3.rgb);
    if (v < 0.5) {
        float land = smoothstep(0.03, 0.12, fbm6(q * 1.3));
        float3 ground = mix(float3(0.20, 0.34, 0.15), float3(0.52, 0.43, 0.27), smoothstep(-0.1, 0.3, fbm4(q * 3.0)));
        float3 col = mix(base * 0.75, ground, land);
        float cl = smoothstep(0.02, 0.38, fbm6(q * 1.8 + float2(time * tau * 3.0, 1.7)));
        return mix(col, float3(0.93), cl * 0.8);
    } else if (v < 1.5) {
        float n1 = fbm6(q * 1.5);
        float3 col = mix(base * 0.55, base * 1.15, smoothstep(-0.35, 0.35, n1));
        float dark = smoothstep(0.0, 0.4, fbm4(q * 0.8 + 3.0));
        col = mix(col, col * 0.62, dark * 0.6);
        return col * (1.0 - 0.06 * smoothstep(0.8, 0.95, 1.0 - abs(gnoise(q * 9.0))));
    } else if (v < 2.5) {
        float band = sin(n.z * 26.0 + 1.8 * fbm4(q * float2(1.6, 0.5)));
        float3 b = mix(base, float3(1.0, 0.95, 0.85), 0.45);
        float3 col = mix(base * 0.7, b, 0.5 + 0.5 * band);
        float2 spot = float2((lon - 1.5) * 1.2, n.z - 0.35);
        col = mix(col, float3(0.72, 0.36, 0.24), smoothstep(0.16, 0.0, length(spot * float2(0.7, 2.6))) * 0.8);
        return col;
    }
    float cr = 1.0 - abs(gnoise(q * 3.0));
    float3 col = mix(base * 1.05, base * 0.8, smoothstep(0.94, 0.99, cr));
    col = mix(col, base * 0.85, smoothstep(0.0, 0.5, fbm4(q * 1.2)) * 0.5);
    return col + 0.04 * fbm4(q * 6.0);
}

half4 main(float2 xy) {
    float2 w = origin + xy * perPx;
    float2 par = float2(tilt.x, tilt.y) * 0.006;
    float neb = fbm6(w * 2.0 + seedOff + 0.5 * float2(cos(time * tau), sin(time * tau)));
    float neb2 = fbm4(w * 3.5 + seedOff * 1.7);
    float3 col = float3(c0.rgb) + float3(c1.rgb) * smoothstep(-0.15, 0.5, neb) * 0.9 + float3(c2.rgb) * smoothstep(0.15, 0.6, neb2) * 0.12;
    col += stars(w + par * 0.3, 0.09);

    float o = clamp(open, 0.0, 1.2);
    float2 L2 = float2(0.342, 0.94);
    float lz = mix(-0.70, -0.28, o);
    float3 L = normalize(float3(L2 * sqrt(1.0 - lz * lz), lz));
    float2 rel = float2(w.x - PC.x, PC.y - w.y) / PR - float2(par.x, -par.y);
    float r2 = dot(rel, rel);
    float3 atm = float3(c2.rgb);
    if (r2 < 1.0) {
        float3 n = float3(rel, sqrt(1.0 - r2));
        float ndl = dot(n, L);
        float day = smoothstep(-0.06, 0.25, ndl);
        float3 surf = surface(n, variant);
        col = surf * (0.015 + 0.95 * day * clamp(ndl * 1.3, 0.0, 1.0));
        if (variant < 0.5) {
            float lon = atan(n.x, n.y) + time * tau * 2.0;
            float2 q = float2(lon * 2.6, n.z * 3.4) + seedOff;
            float land = smoothstep(0.03, 0.12, fbm6(q * 1.3));
            float lights = smoothstep(0.55, 0.9, gnoise(q * 30.0)) * land;
            col += float3(1.0, 0.72, 0.38) * lights * (1.0 - day) * 0.4;
        }
        float rim = pow(1.0 - n.z, 2.4);
        float litRim = smoothstep(-0.2, 0.6, dot(normalize(rel), normalize(L2)) + lz * 0.3);
        col += atm * rim * (0.05 + 0.6 * litRim) * smoothstep(-0.3, 0.3, ndl + 0.25);
    } else {
        float dd = sqrt(r2) - 1.0;
        float side = max(dot(normalize(rel), normalize(L2)), 0.0);
        float glow = exp(-dd / 0.02) * (0.05 + 0.75 * pow(side, 4.0) * (0.4 + 0.6 * o));
        col += atm * glow;
    }
    float sunOff = mix(-0.02, 0.035, o);
    float2 sunW = PC + PR * float2(L2.x, -L2.y) * (1.0 + sunOff);
    float2 sv = w - sunW;
    float sdist = length(sv);
    float lift = smoothstep(-0.015, 0.03, sunOff);
    float3 sunC = float3(1.0, 0.95, 0.86);
    float star = exp(-abs(sv.x) / 0.0022) * exp(-abs(sv.y) / 0.08) + exp(-abs(sv.y) / 0.0022) * exp(-abs(sv.x) / 0.08);
    col += sunC * (exp(-sdist / 0.010) * 1.5 + exp(-sdist / 0.06) * 0.3 + star * 0.45) * lift;
    col += atm * exp(-sdist / 0.16) * 0.2 * (0.3 + 0.7 * lift);
    return half4(half3(finish(col, xy)), 1.0);
}
"""
}
