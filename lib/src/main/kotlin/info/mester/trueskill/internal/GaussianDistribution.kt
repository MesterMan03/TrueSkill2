package info.mester.trueskill.internal

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Internal utilities for Gaussian distribution calculations.
 * These are not part of the public API.
 */
internal object GaussianDistribution {
    /**
     * Numerical stability threshold for CDF calculations.
     * Values below this threshold in the CDF are treated specially to avoid
     * numerical instability when computing the V function (PDF/CDF ratio).
     */
    private const val CDF_STABILITY_THRESHOLD = 2.222758749e-162

    /**
     * Probability density function (PDF) for a standard normal distribution.
     */
    fun standardNormalPdf(x: Double): Double = exp(-0.5 * x * x) / sqrt(2.0 * PI)

    /**
     * Cumulative distribution function (CDF) for a standard normal distribution.
     * Uses error function approximation.
     */
    fun standardNormalCdf(x: Double): Double = 0.5 * (1.0 + erf(x / sqrt(2.0)))

    /**
     * Error function approximation using Abramowitz and Stegun formula.
     */
    private fun erf(x: Double): Double {
        val sign = if (x >= 0) 1.0 else -1.0
        val absX = abs(x)

        // Constants for the approximation
        val a1 = 0.254829592
        val a2 = -0.284496736
        val a3 = 1.421413741
        val a4 = -1.453152027
        val a5 = 1.061405429
        val p = 0.3275911

        val t = 1.0 / (1.0 + p * absX)
        val y = 1.0 - ((((a5 * t + a4) * t + a3) * t + a2) * t + a1) * t * exp(-absX * absX)

        return sign * y
    }

    /**
     * Inverse CDF (quantile function) for a standard normal distribution.
     * Uses Beasley-Springer-Moro algorithm.
     */
    fun standardNormalInverseCdf(p: Double): Double {
        require(p in 0.0..1.0) { "Probability must be between 0 and 1" }

        if (p == 0.0) return Double.NEGATIVE_INFINITY
        if (p == 1.0) return Double.POSITIVE_INFINITY
        if (p == 0.5) return 0.0

        val q = p - 0.5

        // Central region
        if (abs(q) <= 0.425) {
            val r = 0.180625 - q * q
            return q * (
                (
                    (
                        (
                            (
                                (
                                    (2.5090809287301226727e3 * r + 3.3430575583588128105e4) * r +
                                        4.3162944234493772205e4
                                ) * r + 5.7107332567861765700e4
                            ) * r +
                                6.7265770927008700853e4
                        ) * r + 4.5921953931549871457e4
                    ) * r +
                        1.3731693765509461125e4
                ) * r + 1.9715909503065514427e3
            ) /
                (
                    (
                        (
                            (
                                (
                                    (
                                        (1.0 * r + 7.7425252513113380888e3) * r +
                                            2.0529762245432132916e4
                                    ) * r + 3.9307895800092710610e4
                                ) * r +
                                    5.2872526033990542176e4
                            ) * r + 4.8612393403931612035e4
                        ) * r +
                            2.9246445603710159190e4
                    ) * r + 6.7183008615061117220e3
                )
        }

        // Tail region
        val r = if (q < 0) p else 1 - p
        val sign = if (q < 0) -1.0 else 1.0
        val s = sqrt(-ln(r))

        val result =
            if (s <= 5.0) {
                val t = s - 1.6
                (
                    (
                        (
                            (
                                (
                                    (7.74545014278341407640e-4 * t + 2.27238449892691845833e-2) * t +
                                        2.41780725177450611770e-1
                                ) * t + 1.27045825245236838258e0
                            ) * t +
                                3.64784832476320460504e0
                        ) * t + 5.76949722146069140550e0
                    ) * t +
                        4.63033784615654529590e0
                ) /
                    (
                        (
                            (
                                (
                                    (1.0 * t + 7.71154519324537209953e-2) * t +
                                        7.04916420258654092892e-1
                                ) * t + 3.32624458947632290653e0
                            ) * t +
                                8.08924570494082762506e0
                        ) * t + 8.45791721868324965501e0
                    )
            } else {
                val t = s - 5.0
                (
                    (
                        (
                            (
                                (
                                    (2.01033439929228813265e-7 * t + 2.71155556874348757815e-5) * t +
                                        1.24266094738807843860e-3
                                ) * t + 2.65321895265761230930e-2
                            ) * t +
                                2.96560571828504891230e-1
                        ) * t + 1.78482653991729133580e0
                    ) * t +
                        5.46378491116411436990e0
                ) /
                    (
                        (
                            (
                                (
                                    (1.0 * t + 2.04426310338993978564e-15) * t +
                                        1.42151175831644588870e-7
                                ) * t + 1.84631831751005468180e-5
                            ) * t +
                                7.86869131145613259100e-4
                        ) * t + 1.48753612908506148525e-2
                    )
            }

        return sign * result
    }

    /**
     * Compute the V function for truncated Gaussian.
     * V(t, ε) = φ(t-ε) / Φ(t-ε)
     */
    fun vFunction(
        t: Double,
        epsilon: Double,
    ): Double {
        val x = t - epsilon
        val pdf = standardNormalPdf(x)
        val cdf = standardNormalCdf(x)

        return if (cdf < CDF_STABILITY_THRESHOLD) {
            -x
        } else {
            pdf / cdf
        }
    }

    /**
     * Compute the W function for truncated Gaussian.
     * W(t, ε) = V(t, ε) * (V(t, ε) + t - ε)
     */
    fun wFunction(
        t: Double,
        epsilon: Double,
    ): Double {
        val x = t - epsilon
        val v = vFunction(t, epsilon)
        return v * (v + x)
    }

    /**
     * Multiply two Gaussian distributions.
     * Returns a new Gaussian with precision (1/variance) calculated from both.
     */
    fun multiply(
        mean1: Double,
        stdDev1: Double,
        mean2: Double,
        stdDev2: Double,
    ): Pair<Double, Double> {
        val precision1 = 1.0 / (stdDev1 * stdDev1)
        val precision2 = 1.0 / (stdDev2 * stdDev2)
        val precisionSum = precision1 + precision2

        val newMean = (mean1 * precision1 + mean2 * precision2) / precisionSum
        val newStdDev = sqrt(1.0 / precisionSum)

        return Pair(newMean, newStdDev)
    }

    /**
     * Divide one Gaussian by another.
     * Returns the quotient Gaussian.
     */
    fun divide(
        mean1: Double,
        stdDev1: Double,
        mean2: Double,
        stdDev2: Double,
    ): Pair<Double, Double> {
        val precision1 = 1.0 / (stdDev1 * stdDev1)
        val precision2 = 1.0 / (stdDev2 * stdDev2)
        val precisionDiff = precision1 - precision2

        require(precisionDiff > 0) { "Cannot divide: resulting precision would be negative" }

        val newMean = (mean1 * precision1 - mean2 * precision2) / precisionDiff
        val newStdDev = sqrt(1.0 / precisionDiff)

        return Pair(newMean, newStdDev)
    }
}
