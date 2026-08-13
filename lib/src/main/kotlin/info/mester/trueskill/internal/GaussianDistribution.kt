package info.mester.trueskill.internal

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/** Numerically stable standard-normal utilities used by the inference factors. */
internal object GaussianDistribution {
    private const val SQRT_TWO = 1.4142135623730951
    private const val SQRT_TWO_PI = 2.5066282746310002

    fun standardNormalPdf(x: Double): Double = exp(-0.5 * x * x) / SQRT_TWO_PI

    fun standardNormalCdf(x: Double): Double {
        if (x < -8.0) return exp(logStandardNormalCdf(x))
        if (x > 8.0) return 1.0 - exp(logStandardNormalCdf(-x))
        return 0.5 * (1.0 + erf(x / SQRT_TWO))
    }

    fun logStandardNormalCdf(x: Double): Double {
        if (x > -8.0) return ln(standardNormalCdf(x))

        // Asymptotic expansion of Phi(x), evaluated in log space.
        val z = -x
        val inverseSquare = 1.0 / (z * z)
        val correction =
            1.0 - inverseSquare +
                3.0 * inverseSquare * inverseSquare -
                15.0 * inverseSquare * inverseSquare * inverseSquare
        return -0.5 * z * z - ln(z) - ln(SQRT_TWO_PI) + ln(correction)
    }

    /** phi(x) / Phi(x), with a stable lower-tail implementation. */
    fun inverseMillsRatio(x: Double): Double =
        if (x < -8.0) {
            exp(-0.5 * x * x - ln(SQRT_TWO_PI) - logStandardNormalCdf(x))
        } else {
            standardNormalPdf(x) / standardNormalCdf(x)
        }

    fun standardNormalInverseCdf(p: Double): Double {
        require(p in 0.0..1.0) { "Probability must be between 0 and 1" }
        if (p == 0.0) return Double.NEGATIVE_INFINITY
        if (p == 1.0) return Double.POSITIVE_INFINITY

        // Peter J. Acklam's rational approximation.
        val a =
            doubleArrayOf(
                -3.969683028665376e1,
                2.209460984245205e2,
                -2.759285104469687e2,
                1.383577518672690e2,
                -3.066479806614716e1,
                2.506628277459239,
            )
        val b =
            doubleArrayOf(
                -5.447609879822406e1,
                1.615858368580409e2,
                -1.556989798598866e2,
                6.680131188771972e1,
                -1.328068155288572e1,
            )
        val c =
            doubleArrayOf(
                -7.784894002430293e-3,
                -3.223964580411365e-1,
                -2.400758277161838,
                -2.549732539343734,
                4.374664141464968,
                2.938163982698783,
            )
        val d =
            doubleArrayOf(
                7.784695709041462e-3,
                3.224671290700398e-1,
                2.445134137142996,
                3.754408661907416,
            )
        val low = 0.02425
        val high = 1.0 - low

        return when {
            p < low -> {
                val q = sqrt(-2.0 * ln(p))
                (((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5]) /
                    ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1.0)
            }
            p > high -> {
                val q = sqrt(-2.0 * ln(1.0 - p))
                -(((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5]) /
                    ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1.0)
            }
            else -> {
                val q = p - 0.5
                val r = q * q
                (((((a[0] * r + a[1]) * r + a[2]) * r + a[3]) * r + a[4]) * r + a[5]) * q /
                    (((((b[0] * r + b[1]) * r + b[2]) * r + b[3]) * r + b[4]) * r + 1.0)
            }
        }
    }

    private fun erf(x: Double): Double {
        val sign = if (x < 0.0) -1.0 else 1.0
        val value = abs(x)
        val t = 1.0 / (1.0 + 0.3275911 * value)
        val polynomial =
            (
                ((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t +
                    0.254829592
            ) * t
        return sign * (1.0 - polynomial * exp(-value * value))
    }
}
