package com.foldmotion.wallpapers.render

import android.graphics.Canvas
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.foldmotion.core.motion.RevealSpec

/**
 * The liquid reveals: RIPPLE, FLOW and GLASS. The frame is drawn once as usual, then bent by an
 * AGSL shader that moves pixels around (refraction) and lights the liquid's edges, so the picture
 * itself behaves like water, honey or melting glass rather than being masked.
 *
 * The content is recorded into a [RenderNode] and the shader runs as its [RenderEffect] on the
 * GPU, so it needs a hardware canvas (the wallpaper's and the in-app previews' are). On a software
 * canvas (Android rejects runtime shaders there) and before Android 13 (no runtime shaders),
 * [draw] returns false and the caller draws a close cousin without one.
 */
internal class LiquidReveal {

    private var node: RenderNode? = null
    private val shaders = HashMap<String, Any>()

    /** Set once a shader failed to build or draw on this phone; from then on, the fallback. */
    @Volatile
    private var broken = false

    /**
     * Draws [content] (background and layers, in canvas coordinates) bent by [reveal] at reveal
     * progress [rp]. [originX]/[originY] is the ripple's origin in pixels.
     *
     * @return false when nothing was drawn and the caller must draw the content itself.
     */
    fun draw(
        canvas: Canvas,
        width: Int,
        height: Int,
        reveal: RevealSpec,
        rp: Float,
        originX: Float,
        originY: Float,
        glow: Int,
        content: (Canvas) -> Unit,
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || broken || !canvas.isHardwareAccelerated || width <= 0 || height <= 0) return false
        return try {
            drawWithShader(canvas, width, height, reveal, rp, originX, originY, glow, content)
            true
        } catch (e: Throwable) {
            // A driver or compiler problem must never cost the wallpaper its picture.
            Log.w(TAG, "liquid reveal unavailable, using the fallback", e)
            broken = true
            false
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun drawWithShader(
        canvas: Canvas,
        width: Int,
        height: Int,
        reveal: RevealSpec,
        rp: Float,
        originX: Float,
        originY: Float,
        glow: Int,
        content: (Canvas) -> Unit,
    ) {
        val shader = shaderFor(reveal.type)
        shader.setFloatUniform("size", width.toFloat(), height.toFloat())
        shader.setFloatUniform("progress", rp.coerceIn(0f, 1f))
        shader.setFloatUniform("shade", reveal.shade)
        shader.setFloatUniform("edge", reveal.edgeGlow)
        shader.setColorUniform("glow", glow)
        when (reveal.type) {
            RevealSpec.RIPPLE -> {
                shader.setFloatUniform("origin", originX, originY)
                shader.setFloatUniform("strength", reveal.from[2])
            }
            RevealSpec.FLOW -> {
                shader.setFloatUniform("angle", Math.toRadians(reveal.from[0].toDouble()).toFloat())
                shader.setFloatUniform("strength", reveal.from[1])
            }
            else -> shader.setFloatUniform("strength", reveal.from[0])
        }
        val n = node ?: RenderNode("liquid-reveal").also { node = it }
        n.setPosition(0, 0, width, height)
        val recording = n.beginRecording(width, height)
        try {
            content(recording)
        } finally {
            n.endRecording()
        }
        n.setRenderEffect(RenderEffect.createRuntimeShaderEffect(shader, CONTENT))
        canvas.drawRenderNode(n)
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun shaderFor(type: String): RuntimeShader =
        shaders.getOrPut(type) {
            RuntimeShader(
                when (type) {
                    RevealSpec.RIPPLE -> RIPPLE_AGSL
                    RevealSpec.FLOW -> FLOW_AGSL
                    else -> GLASS_AGSL
                },
            )
        } as RuntimeShader

    companion object {
        private const val TAG = "LiquidReveal"
        private const val CONTENT = "content"

        /**
         * A drop at [origin]: the uncovered disc grows with the first ring; a train of rings rides
         * behind it, pushing pixels outwards on their crests (refraction) and catching the light.
         * Everything calms down as the reveal lands, so the last frame is the plain picture.
         */
        const val RIPPLE_AGSL = """
uniform shader content;
uniform float2 size;
uniform float2 origin;
uniform float progress;
uniform float strength;
uniform float shade;
uniform float edge;
layout(color) uniform half4 glow;

half4 main(float2 xy) {
    float2 d = xy - origin;
    float r = length(d);
    float far = max(max(length(origin), length(origin - float2(size.x, 0.0))),
                    max(length(origin - float2(0.0, size.y)), length(origin - size)));
    float front = progress * far * 1.12;
    float k = (r - front) / (far * 0.08);
    float calm = 1.0 - progress;
    float behind = clamp(-k, 0.0, 40.0);
    float rings = sin((r - front) / (far * 0.034)) * exp(-behind * 0.14) * (1.0 - smoothstep(0.0, 1.6, k));
    float wave = rings * calm * strength;
    float2 dir = r > 0.001 ? d / r : float2(0.0, 0.0);
    float2 at = clamp(xy - dir * wave * far * 0.034, float2(0.5, 0.5), size - float2(0.5, 0.5));
    float4 c = float4(content.eval(at));
    float vis = 1.0 - smoothstep(-0.4, 0.9, k);
    float rim = exp(-k * k * 3.0) * calm;
    float crest = max(wave, 0.0) * 0.3;
    float3 g = float3(glow.rgb);
    float3 rgb = c.rgb * (1.0 - shade * (1.0 - vis)) + g * ((rim * 0.55 + crest) * edge);
    return half4(half3(rgb), half(c.a));
}
"""

        /**
         * The scene pours in along [angle] as a thick liquid: a wavy front (three swells of
         * different size), a lens of liquid just behind it that refracts the picture, a glossy
         * meniscus on the edge and slow ripples trailing behind, all settling as it lands.
         */
        const val FLOW_AGSL = """
uniform shader content;
uniform float2 size;
uniform float angle;
uniform float progress;
uniform float strength;
uniform float shade;
uniform float edge;
layout(color) uniform half4 glow;

half4 main(float2 xy) {
    float2 dir = float2(cos(angle), sin(angle));
    float2 side = float2(-dir.y, dir.x);
    float2 mid = size * 0.5;
    float extent = abs(dir.x) * size.x + abs(dir.y) * size.y;
    float along = dot(xy - mid, dir) + extent * 0.5;
    float across = dot(xy - mid, side) / extent;
    float calm = 1.0 - progress;
    float wobble = sin(across * 6.3 + progress * 4.0) * 0.6
                 + sin(across * 15.7 - progress * 7.0) * 0.28
                 + sin(across * 33.0 + progress * 10.0) * 0.12;
    float front = progress * extent * 1.3 - extent * 0.12 + wobble * extent * 0.07 * (0.35 + calm);
    float k = (along - front) / (extent * 0.045);
    float vis = 1.0 - smoothstep(-0.6, 0.6, k);
    float lens = exp(-(k + 1.0) * (k + 1.0) * 0.35) * calm * strength;
    float behind = clamp(-k, 0.0, 60.0);
    float trail = sin(along / (extent * 0.04) - progress * 18.0) * exp(-behind * 0.08)
                * (1.0 - smoothstep(-1.0, 0.0, k)) * calm * strength;
    float2 at = xy - dir * (lens * extent * 0.04) + side * (trail * extent * 0.006);
    at = clamp(at, float2(0.5, 0.5), size - float2(0.5, 0.5));
    float4 c = float4(content.eval(at));
    float rim = exp(-(k + 0.35) * (k + 0.35) * 2.5) * (0.3 + 0.7 * calm);
    float3 g = float3(glow.rgb);
    float3 rgb = c.rgb * (1.0 - shade * (1.0 - vis)) + g * ((rim * 0.6 + lens * 0.1 + max(trail, 0.0) * 0.06) * edge);
    return half4(half3(rgb), half(c.a));
}
"""

        /**
         * The scene behind thick glass that melts: smooth bumps refract it (red, green and blue
         * bent by different amounts, the glass's chromatic edge), a two-tap blur frosts it, it is
         * paler while the glass is thick, and a sheen runs across as it clears.
         */
        const val GLASS_AGSL = """
uniform shader content;
uniform float2 size;
uniform float progress;
uniform float strength;
uniform float shade;
uniform float edge;
layout(color) uniform half4 glow;

half4 main(float2 xy) {
    float melt = 1.0 - progress;
    float m2 = melt * melt;
    float2 q = xy / size.y;
    float2 n = float2(sin(q.y * 8.0 + q.x * 2.5 + progress * 3.0) + 0.5 * sin(q.x * 19.0 - q.y * 5.0 - progress * 4.0),
                      cos(q.x * 7.0 - q.y * 3.0 - progress * 2.5) + 0.5 * cos(q.y * 17.0 + q.x * 4.0 + progress * 5.0));
    float2 off = n * (m2 * size.y * 0.028 * strength);
    float blur = m2 * size.y * 0.012 * strength;
    float2 lo = float2(0.5, 0.5);
    float2 hi = size - float2(0.5, 0.5);
    float red = (float4(content.eval(clamp(xy + off * 1.3 + float2(blur, 0.0), lo, hi))).r
               + float4(content.eval(clamp(xy + off * 1.3 - float2(blur, 0.0), lo, hi))).r) * 0.5;
    float4 mid = (float4(content.eval(clamp(xy + off + float2(0.0, blur), lo, hi)))
                + float4(content.eval(clamp(xy + off - float2(0.0, blur), lo, hi)))) * 0.5;
    float blue = (float4(content.eval(clamp(xy + off * 0.7 + float2(blur, blur), lo, hi))).b
                + float4(content.eval(clamp(xy + off * 0.7 - float2(blur, blur), lo, hi))).b) * 0.5;
    float3 rgb = float3(red, mid.g, blue);
    float luma = dot(rgb, float3(0.299, 0.587, 0.114));
    float3 g = float3(glow.rgb);
    rgb = mix(rgb, float3(luma, luma, luma) * 0.75 + g * 0.15, m2 * 0.5);
    float lit = smoothstep(0.0, 0.42, progress);
    float s = dot(xy / size, float2(0.64, 0.77));
    float z = (s - (progress * 1.7 - 0.35)) / 0.07;
    float sheen = exp(-z * z) * melt;
    rgb = rgb * (1.0 - shade * (1.0 - lit)) + g * (sheen * 0.45 * edge);
    return half4(half3(rgb), half(mid.a));
}
"""
    }
}
