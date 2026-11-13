package com.foldmotion.wallpapers.render.video

/**
 * GLSL ES 1.00 shaders that turn decoded video into per-screen frames. They only crop, scale and
 * cross-fade; the fold styles, light and particles are drawn afterwards by SceneDrawer, the same
 * code that draws every other pack. Checked with glslangValidator in the build notes.
 */
object VideoShaders {

    const val VERTEX = """#version 100
attribute vec2 aPos;
varying vec2 vUv;
void main() {
    // vUv: 0,0 is the top left of the output frame.
    vUv = vec2(aPos.x * 0.5 + 0.5, 0.5 - aPos.y * 0.5);
    gl_Position = vec4(aPos, 0.0, 1.0);
}
"""

    const val FRAGMENT = """#version 100
#extension GL_OES_EGL_image_external : require
precision mediump float;
varying vec2 vUv;
uniform samplerExternalOES uTexA;
uniform samplerExternalOES uTexB;
uniform mat4 uMatA;
uniform mat4 uMatB;
// Window of the video this output shows, [left, top, right, bottom] in video coordinates
// (top-left origin). It may reach past the edges for overscan; sampling clamps there.
uniform vec4 uCrop;
// Share of the standby player while the loop seam cross-fades, 0..1.
uniform float uMix;

vec4 sampleVideo(samplerExternalOES tex, mat4 m, vec2 img) {
    // SurfaceTexture coordinates start at the bottom left; its matrix adds rotation and padding.
    vec4 st = m * vec4(img.x, 1.0 - img.y, 0.0, 1.0);
    return texture2D(tex, st.xy);
}

void main() {
    vec2 img = clamp(uCrop.xy + vUv * (uCrop.zw - uCrop.xy), 0.001, 0.999);
    vec4 color = sampleVideo(uTexA, uMatA, img);
    if (uMix > 0.001) {
        color = mix(color, sampleVideo(uTexB, uMatB, img), uMix);
    }
    gl_FragColor = vec4(color.rgb, 1.0);
}
"""
}
