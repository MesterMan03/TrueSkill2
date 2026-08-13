package info.mester.trueskill.internal.factorgraph

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/** A Gaussian in canonical (precision, precision-mean) form. */
internal data class Gaussian(
    val precision: Double,
    val precisionMean: Double,
) {
    val mean: Double
        get() = if (precision == 0.0) 0.0 else precisionMean / precision

    val variance: Double
        get() = if (precision == 0.0) Double.POSITIVE_INFINITY else 1.0 / precision

    operator fun times(other: Gaussian) = Gaussian(precision + other.precision, precisionMean + other.precisionMean)

    operator fun div(other: Gaussian) = Gaussian(precision - other.precision, precisionMean - other.precisionMean)

    companion object {
        val UNIFORM = Gaussian(0.0, 0.0)

        fun fromMeanVariance(
            mean: Double,
            variance: Double,
        ): Gaussian {
            require(variance > 0.0 && variance.isFinite()) { "Variance must be positive and finite" }
            val precision = 1.0 / variance
            return Gaussian(precision, mean * precision)
        }

        fun delta(
            before: Gaussian,
            after: Gaussian,
        ): Double {
            if (before.precision == 0.0) return Double.POSITIVE_INFINITY
            val meanDelta = abs(before.mean - after.mean)
            val logStdDevDelta =
                abs(0.5 * ln(max(after.variance, 1e-300) / max(before.variance, 1e-300)))
            return max(meanDelta, logStdDevDelta)
        }
    }
}
