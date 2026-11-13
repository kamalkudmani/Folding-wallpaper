package com.foldmotion.wallpapers.render

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.foldmotion.core.pack.LiquidSpec
import com.foldmotion.core.scene.WorldRect
import kotlin.math.PI
import kotlin.math.ceil

/**
 * Live liquid (pack v6), drawn on the GPU with an AGSL shader.
 *
 * The liquid lives in world space (the main screen's long side is 1), so the cover screen shows
 * the very liquid the main screen shows around it. Each frame [prepare] records the shader into a
 * small [RenderNode] (a fraction of the screen's pixels: liquid is smooth, so it upscales without
 * loss) kept as a compositing layer; [draw] then places that layer wherever the layer's pose puts
 * it, as many times as a fold style needs (tiles, a turning page), for the price of one render.
 *
 * Needs a hardware canvas and Android 13; otherwise [draw] returns false and the caller draws the
 * pack's poster.
 */
class LiquidField {

    private var node: RenderNode? = null
    private var shader: Any? = null
    private val paint = Paint()
    private var nodeW = 0
    private var nodeH = 0

    @Volatile
    private var broken = false

    /** True once a frame has been recorded and can be drawn. */
    var ready = false
        private set

    val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !broken

    /**
     * Records this frame's liquid for a layer covering [layerWorld], at about [pixelsW] x
     * [pixelsH] screen pixels divided by [downscale].
     */
    fun prepare(spec: LiquidSpec, layerWorld: WorldRect, pixelsW: Float, pixelsH: Float, frame: LiquidFrame, downscale: Float = DOWNSCALE) {
        ready = false
        if (!supported || pixelsW < 2f || pixelsH < 2f) return
        try {
            record(spec, layerWorld, ceil(pixelsW / downscale).toInt().coerceIn(8, 2048), ceil(pixelsH / downscale).toInt().coerceIn(8, 2048), frame)
            ready = true
        } catch (e: Throwable) {
            Log.w(TAG, "live liquid unavailable, showing the poster", e)
            broken = true
        }
    }

    /** Nothing to draw this frame: the poster shows. */
    fun clear() {
        ready = false
    }

    /** Draws the recorded liquid stretched over [dst]; false when there is nothing to draw here. */
    fun draw(canvas: Canvas, dst: RectF, alpha: Float = 1f): Boolean {
        val n = node ?: return false
        if (!ready || !canvas.isHardwareAccelerated || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        n.alpha = alpha.coerceIn(0f, 1f)
        canvas.save()
        canvas.translate(dst.left, dst.top)
        canvas.scale(dst.width() / nodeW, dst.height() / nodeH)
        canvas.drawRenderNode(n)
        canvas.restore()
        return true
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun record(spec: LiquidSpec, world: WorldRect, w: Int, h: Int, frame: LiquidFrame) {
        val s = (shader as? RuntimeShader) ?: RuntimeShader(AGSL).also { shader = it }
        configure(s, spec, world, w, h, frame)
        val n = node ?: RenderNode("liquid").also {
            node = it
            // Rendered once into an offscreen layer at its own (small) size, then composited.
            it.setUseCompositingLayer(true, null)
        }
        nodeW = w
        nodeH = h
        n.setPosition(0, 0, w, h)
        val c = n.beginRecording(w, h)
        try {
            paint.shader = s
            c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        } finally {
            n.endRecording()
        }
    }

    companion object {
        private const val TAG = "LiquidField"

        /** Screen pixels per liquid pixel. */
        const val DOWNSCALE = 2.5f

        /** Sets every uniform of [AGSL] for one frame. Shared with offscreen poster rendering. */
        @RequiresApi(Build.VERSION_CODES.TIRAMISU)
        fun configure(s: RuntimeShader, spec: LiquidSpec, world: WorldRect, w: Int, h: Int, frame: LiquidFrame) {
            s.setFloatUniform("origin", world.left, world.top)
            s.setFloatUniform("perPx", world.width / w, world.height / h)
            s.setFloatUniform("size", w.toFloat(), h.toFloat())
            val t = ((frame.timeMs / 1000.0) % LiquidSpec.PERIOD_SECONDS).toFloat()
            s.setFloatUniform("time", t)
            val turns = LiquidSpec.turns(spec.speed)
            val k = (2.0 * PI / LiquidSpec.PERIOD_SECONDS).toFloat()
            s.setFloatUniform("freq", turns[0] * k, turns[1] * k, turns[2] * k, turns[3] * k)
            s.setFloatUniform("span", spec.span)
            s.setFloatUniform("warpK", spec.warp)
            s.setFloatUniform("seedOff", (spec.seed * 0.618034f) % 61f + 3f, (spec.seed * 0.414214f) % 53f + 5f)
            s.setFloatUniform("flow", if (spec.tilt) frame.flowX else 0f, if (spec.tilt) frame.flowY else 0f)
            s.setFloatUniform("hinge", if (spec.hinge) frame.hinge else 0f)
            s.setFloatUniform("crease", frame.creaseX)
            val r = if (spec.touch) frame.ripples else emptyList()
            for (i in 0 until MAX_RIPPLES) {
                val rp = r.getOrNull(i)
                if (rp == null) s.setFloatUniform("r$i", 0f, 0f, 0f, 0f)
                else s.setFloatUniform("r$i", rp.x, rp.y, rp.ageSeconds, rp.strength)
            }
            fun color(i: Int) = try {
                Color.parseColor(spec.palette[i])
            } catch (e: Exception) {
                Color.GRAY
            }
            s.setColorUniform("c0", color(0))
            s.setColorUniform("c1", color(1))
            s.setColorUniform("c2", color(2))
            s.setColorUniform("c3", color(3))
            s.setFloatUniform("spec", spec.specular)
            s.setFloatUniform("gloss", spec.gloss)
            s.setFloatUniform("bright", spec.brightness)
        }

        const val MAX_RIPPLES = 4

        /**
         * The liquid. Gradient noise on a float hash (no tables, no sine hash, so it behaves the
         * same on every GPU), three-octave fbm, warped into itself twice (the shapes), then lit as
         * a height field from three samples: a key light, a crisp gloss, a soft sheen and a
         * tinted rim. Time runs on circles of whole turns per ten minutes, so it is exactly
         * periodic and never loses precision. Ripples travel out from taps and push the liquid
         * along their crests; a half-open hinge draws the liquid into the fold.
         */
        val AGSL = """
uniform float2 origin;
uniform float2 perPx;
uniform float2 size;
uniform float time;
uniform float4 freq;
uniform float span;
uniform float warpK;
uniform float2 seedOff;
uniform float2 flow;
uniform float hinge;
uniform float crease;
uniform float4 r0;
uniform float4 r1;
uniform float4 r2;
uniform float4 r3;
layout(color) uniform half4 c0;
layout(color) uniform half4 c1;
layout(color) uniform half4 c2;
layout(color) uniform half4 c3;
uniform float spec;
uniform float gloss;
uniform float bright;

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

float fbm(float2 p) {
    float total = 0.0;
    float amp = 0.5;
    for (int i = 0; i < 3; i++) {
        total += amp * gnoise(p);
        p = float2(0.825 * p.x - 0.565 * p.y, 0.565 * p.x + 0.825 * p.y) * 2.02 + float2(3.1, 1.7);
        amp *= 0.5;
    }
    return total;
}

float ring(float4 r, float2 w) {
    if (r.w <= 0.0) return 0.0;
    float d = length(w - r.xy);
    float x = d - r.z * 0.32;
    return sin(x * 55.0) * exp(-x * x * 260.0) * exp(-r.z * 1.1) * r.w;
}

float2 ringDir(float4 r, float2 w) {
    float2 d = w - r.xy;
    float l = length(d);
    return l > 0.0001 ? d / l : float2(0.0, 0.0);
}

// x: height 0..1, y: warp strength, z: eddies.
float3 liquid(float2 w) {
    float dx = w.x - crease;
    w.x -= dx * hinge * 0.45 * exp(-dx * dx * 30.0);
    float a = ring(r0, w);
    float b = ring(r1, w);
    float c = ring(r2, w);
    float d = ring(r3, w);
    float2 push = ringDir(r0, w) * a + ringDir(r1, w) * b + ringDir(r2, w) * c + ringDir(r3, w) * d;
    float2 p = w * span + seedOff + flow + push * 0.09;
    float2 o1 = 0.13 * float2(cos(time * freq.x), sin(time * freq.x));
    float2 o2 = 0.11 * float2(cos(time * freq.y + 2.1), sin(time * freq.y + 2.1));
    float2 t1 = 0.19 * float2(cos(time * freq.z + 1.3), sin(time * freq.z + 1.3));
    float2 t2 = 0.16 * float2(cos(time * freq.w + 4.2), sin(time * freq.w + 4.2));
    float2 q = float2(fbm(p + o1), fbm(p + float2(5.2, 1.3) + o2));
    float2 r = float2(fbm(p + warpK * q + float2(1.7, 9.2) + t1), fbm(p + warpK * q + float2(8.3, 2.8) + t2));
    float f = fbm(p + warpK * r);
    f = clamp((f + 0.05) / 0.36 + 0.5, 0.0, 1.0);
    f += (a + b + c + d) * 0.16;
    f += hinge * 0.22 * exp(-dx * dx * 60.0);
    return float3(clamp(f, 0.0, 1.0), length(q), abs(r.x - r.y));
}

float smooth01(float e0, float e1, float v) {
    float k = clamp((v - e0) / (e1 - e0), 0.0, 1.0);
    return k * k * (3.0 - 2.0 * k);
}

half4 main(float2 xy) {
    float2 w = origin + xy * perPx;
    float e = 0.0025;
    float3 L = liquid(w);
    float fx = liquid(w + float2(e, 0.0)).x;
    float fy = liquid(w + float2(0.0, e)).x;
    float f = L.x;

    float t1 = smooth01(0.18, 0.55, f);
    float t2 = smooth01(0.50, 0.85, f);
    float3 col = mix(float3(c0.rgb), float3(c1.rgb), t1);
    col = mix(col, float3(c2.rgb), t2);
    float ed = smooth01(0.08, 0.26, L.z) * 0.5;
    col = mix(col, float3(c3.rgb), ed);

    float slope = 0.0183 / e;
    float3 n = normalize(float3(-(fx - f) * slope, -(fy - f) * slope, 1.0));
    float3 l = normalize(float3(-0.42, -0.58, 0.70));
    float ndl = clamp(dot(n, l), 0.0, 1.0);
    float3 hv = normalize(l + float3(0.0, 0.0, 1.0));
    float ndh = clamp(dot(n, hv), 0.0, 1.0);
    float sp = pow(ndh, gloss) * spec;
    float soft = pow(ndh, gloss * 0.12) * 0.18;
    float fres = pow(clamp(1.0 - n.z, 0.0, 1.0), 1.5) * 0.55;
    float occl = smooth01(0.05, 0.6, f);
    col = col * (0.18 + 1.05 * pow(ndl, 1.4)) * (0.35 + 0.65 * occl) * bright;
    col = col + (sp + soft) * float3(c3.rgb) + fres * float3(c2.rgb) * 0.6;

    float2 v = xy / size - 0.5;
    col *= 1.0 - 0.22 * clamp(dot(v, v) * 2.2, 0.0, 1.0);
    col = pow(clamp(col, 0.0, 1.0), float3(1.0 / 1.08));
    return half4(half3(col), 1.0);
}
"""
    }
}

/** What moves the liquid this frame besides time. */
data class LiquidFrame(
    val timeMs: Long,
    val flowX: Float = 0f,
    val flowY: Float = 0f,
    /** 0 flat .. 1 strongly half-open (main screen only). */
    val hinge: Float = 0f,
    /** World x of the fold line. */
    val creaseX: Float = 0.45f,
    val ripples: List<LiquidMotion.Ripple> = emptyList(),
)
