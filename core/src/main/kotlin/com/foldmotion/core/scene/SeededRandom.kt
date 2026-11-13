package com.foldmotion.core.scene

import kotlin.math.floor

/**
 * SplitMix64. Implemented here rather than using a platform class so the same seed draws the
 * same scene on every Android version and on the JVM used by tests and by a future server.
 */
class SeededRandom(seed: Long) {
    private var state = seed

    fun nextLong(): Long {
        state += GOLDEN
        var z = state
        z = (z xor (z ushr 30)) * MIX1
        z = (z xor (z ushr 27)) * MIX2
        return z xor (z ushr 31)
    }

    /** Uniform in [0, 1). */
    fun nextFloat(): Float = (nextLong() ushr 40).toFloat() / (1 shl 24).toFloat()

    fun range(from: Float, to: Float): Float = from + (to - from) * nextFloat()

    /** Uniform in [0, bound). */
    fun nextInt(bound: Int): Int = if (bound <= 1) 0 else ((nextLong() ushr 33) % bound).toInt()

    fun chance(probability: Float): Boolean = nextFloat() < probability

    fun <T> pick(items: List<T>): T = items[nextInt(items.size)]

    /** Independent stream for a named sub-element, so adding one element never shifts another. */
    fun fork(salt: Long): SeededRandom = SeededRandom(nextLong() xor (salt * MIX1))

    private companion object {
        const val GOLDEN = -0x61c8864680b583ebL
        const val MIX1 = -0x40a7b892e31b1a47L
        const val MIX2 = -0x6b2fb644ecceee15L
    }
}

/** Deterministic 1D value noise, continuous in x, defined for every world coordinate. */
object ValueNoise {

    private fun hash(i: Int, seed: Int): Float {
        var h = i * 374761393 + seed * 668265263
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return (h and 0x7FFFFFFF).toFloat() / 0x7FFFFFFF.toFloat()
    }

    /** Smooth noise in [0, 1]. */
    fun at(x: Float, seed: Int): Float {
        val i = floor(x).toInt()
        val f = x - i
        val u = f * f * (3f - 2f * f)
        val a = hash(i, seed)
        val b = hash(i + 1, seed)
        return a + (b - a) * u
    }

    /** Fractal sum of octaves in [0, 1]; roughness 0.5 gives natural ridgelines. */
    fun fbm(x: Float, seed: Int, octaves: Int = 4, roughness: Float = 0.5f): Float {
        var sum = 0f
        var amplitude = 1f
        var norm = 0f
        var frequency = 1f
        for (o in 0 until octaves) {
            sum += at(x * frequency, seed + o * 31) * amplitude
            norm += amplitude
            amplitude *= roughness
            frequency *= 2f
        }
        return sum / norm
    }
}
