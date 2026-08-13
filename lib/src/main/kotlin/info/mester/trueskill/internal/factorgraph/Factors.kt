package info.mester.trueskill.internal.factorgraph

import info.mester.trueskill.internal.GaussianDistribution
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

internal class PriorFactor(
    private val variable: Variable,
    mean: Double,
    variance: Double,
) : Factor {
    private val prior = Gaussian.fromMeanVariance(mean, variance)

    override fun update(): Double = variable.updateMessage(this, prior)
}

/** `y = offset + sum(coefficients[i] * inputs[i]) + N(0, noiseVariance)`. */
internal class NoisyWeightedSumFactor(
    private val output: Variable,
    private val inputs: List<Variable>,
    private val coefficients: List<Double>,
    private val noiseVariance: Double = 0.0,
    private val offset: Double = 0.0,
) : Factor {
    init {
        require(inputs.size == coefficients.size)
        require(coefficients.none { it == 0.0 })
        require(noiseVariance >= 0.0)
    }

    override fun update(): Double {
        var delta = updateOutput()
        inputs.indices.forEach { delta = max(delta, updateInput(it)) }
        return delta
    }

    private fun updateOutput(): Double {
        var mean = offset
        var variance = noiseVariance
        inputs.indices.forEach { index ->
            val cavity = inputs[index].cavity(this)
            if (!cavity.variance.isFinite()) return 0.0
            mean += coefficients[index] * cavity.mean
            variance += coefficients[index] * coefficients[index] * cavity.variance
        }
        return output.updateMessage(this, Gaussian.fromMeanVariance(mean, max(variance, MIN_VARIANCE)))
    }

    private fun updateInput(target: Int): Double {
        val outputCavity = output.cavity(this)
        if (!outputCavity.variance.isFinite()) return 0.0

        var otherMean = offset
        var variance = outputCavity.variance + noiseVariance
        inputs.indices.filter { it != target }.forEach { index ->
            val cavity = inputs[index].cavity(this)
            if (!cavity.variance.isFinite()) return 0.0
            otherMean += coefficients[index] * cavity.mean
            variance += coefficients[index] * coefficients[index] * cavity.variance
        }
        val coefficient = coefficients[target]
        val mean = (outputCavity.mean - otherMean) / coefficient
        return inputs[target].updateMessage(
            this,
            Gaussian.fromMeanVariance(mean, max(variance / (coefficient * coefficient), MIN_VARIANCE)),
        )
    }

    private companion object {
        const val MIN_VARIANCE = 1e-12
    }
}

/** `observed = offset + sum(coefficients[i] * inputs[i]) + N(0, noiseVariance)`. */
internal class GaussianObservationFactor(
    private val observed: Double,
    private val inputs: List<Variable>,
    private val coefficients: List<Double>,
    private val noiseVariance: Double,
    private val offset: Double = 0.0,
) : Factor {
    init {
        require(inputs.size == coefficients.size)
        require(coefficients.none { it == 0.0 })
        require(noiseVariance > 0.0)
    }

    override fun update(): Double {
        var delta = 0.0
        inputs.indices.forEach { target ->
            var otherMean = offset
            var variance = noiseVariance
            inputs.indices.filter { it != target }.forEach { index ->
                val cavity = inputs[index].cavity(this)
                if (!cavity.variance.isFinite()) return@forEach
                otherMean += coefficients[index] * cavity.mean
                variance += coefficients[index] * coefficients[index] * cavity.variance
            }
            val coefficient = coefficients[target]
            val mean = (observed - otherMean) / coefficient
            delta =
                max(
                    delta,
                    inputs[target].updateMessage(
                        this,
                        Gaussian.fromMeanVariance(mean, variance / (coefficient * coefficient)),
                    ),
                )
        }
        return delta
    }
}

internal sealed class OutcomeFactor(
    protected val variable: Variable,
) : Factor {
    class GreaterThan(
        variable: Variable,
        private val margin: Double,
    ) : OutcomeFactor(variable) {
        override fun update(): Double {
            val cavity = variable.cavity(this)
            if (!cavity.variance.isFinite()) return 0.0
            val stdDev = sqrt(cavity.variance)
            val t = (cavity.mean - margin) / stdDev
            val v = GaussianDistribution.inverseMillsRatio(t)
            val w = min(v * (v + t), 1.0 - 1e-12)
            val posterior =
                Gaussian.fromMeanVariance(
                    cavity.mean + stdDev * v,
                    max(cavity.variance * (1.0 - w), 1e-12),
                )
            return variable.updateMessage(this, posterior / cavity)
        }
    }

    class LessThan(
        variable: Variable,
        private val margin: Double,
    ) : OutcomeFactor(variable) {
        override fun update(): Double {
            val cavity = variable.cavity(this)
            if (!cavity.variance.isFinite()) return 0.0
            val stdDev = sqrt(cavity.variance)
            val t = (margin - cavity.mean) / stdDev
            val v = GaussianDistribution.inverseMillsRatio(t)
            val w = min(v * (v + t), 1.0 - 1e-12)
            val posterior =
                Gaussian.fromMeanVariance(
                    cavity.mean - stdDev * v,
                    max(cavity.variance * (1.0 - w), 1e-12),
                )
            return variable.updateMessage(this, posterior / cavity)
        }
    }

    class Draw(
        variable: Variable,
        private val margin: Double,
    ) : OutcomeFactor(variable) {
        override fun update(): Double {
            val cavity = variable.cavity(this)
            if (!cavity.variance.isFinite()) return 0.0
            val stdDev = sqrt(cavity.variance)
            val lower = (-margin - cavity.mean) / stdDev
            val upper = (margin - cavity.mean) / stdDev
            val denominator =
                max(
                    GaussianDistribution.standardNormalCdf(upper) -
                        GaussianDistribution.standardNormalCdf(lower),
                    1e-300,
                )
            val lowerPdf = GaussianDistribution.standardNormalPdf(lower)
            val upperPdf = GaussianDistribution.standardNormalPdf(upper)
            val v = (lowerPdf - upperPdf) / denominator
            val w =
                min(
                    v * v + (upper * upperPdf - lower * lowerPdf) / denominator,
                    1.0 - 1e-12,
                )
            val posterior =
                Gaussian.fromMeanVariance(
                    cavity.mean + stdDev * v,
                    max(cavity.variance * (1.0 - w), 1e-12),
                )
            return variable.updateMessage(this, posterior / cavity)
        }
    }
}

internal class FactorGraph(
    private val factors: List<Factor>,
    private val maxIterations: Int = 100,
    private val convergenceThreshold: Double = 1e-7,
) {
    fun run() {
        repeat(maxIterations) { iteration ->
            var delta = 0.0
            factors.forEach { delta = max(delta, it.update()) }
            if (iteration > 1 && (delta < convergenceThreshold || !delta.isFinite())) return
        }
    }
}
