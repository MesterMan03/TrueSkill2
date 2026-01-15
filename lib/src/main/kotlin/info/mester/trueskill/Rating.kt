package info.mester.trueskill

import info.mester.trueskill.internal.GaussianDistribution
import kotlin.math.sqrt

/**
 * Represents a player's skill rating as a Gaussian distribution.
 *
 * The rating consists of:
 * - [mean] (μ): The estimated skill level
 * - [standardDeviation] (σ): The uncertainty in the skill estimate
 *
 * A higher mean indicates higher skill, while a higher standard deviation
 * indicates more uncertainty. As a player plays more games, the standard
 * deviation decreases (confidence increases).
 *
 * @property mean The mean skill value (μ)
 * @property standardDeviation The standard deviation representing uncertainty (σ)
 */
data class Rating(
    val mean: Double,
    val standardDeviation: Double,
) {
    init {
        require(standardDeviation > 0) { "Standard deviation must be positive" }
    }

    /**
     * The precision (inverse variance) of this rating.
     * Precision = 1 / σ²
     */
    val precision: Double
        get() = 1.0 / (standardDeviation * standardDeviation)

    /**
     * The precision-adjusted mean (τ).
     * τ = μ / σ²
     */
    val precisionMean: Double
        get() = mean * precision

    /**
     * Conservative skill estimate for ranking purposes.
     * Calculated as μ - 3σ, which represents the skill value where
     * we're 99.7% confident the true skill is above this value.
     */
    val conservativeRating: Double
        get() = mean - 3.0 * standardDeviation

    /**
     * Apply skill decay over time (for TrueSkill Through Time).
     * Increases uncertainty based on time elapsed.
     *
     * @param timeDelta Time units elapsed
     * @param dynamicsFactor The dynamics factor (β) controlling skill variance growth
     * @return A new rating with increased uncertainty
     */
    fun applyDynamics(
        timeDelta: Double,
        dynamicsFactor: Double,
    ): Rating {
        val newVariance =
            standardDeviation * standardDeviation +
                timeDelta * dynamicsFactor * dynamicsFactor
        return Rating(mean, sqrt(newVariance))
    }

    override fun toString(): String = "Rating(μ=${"%.3f".format(mean)}, σ=${"%.3f".format(standardDeviation)})"

    companion object {
        /**
         * Creates a default initial rating with standard parameters.
         */
        fun defaultRating(
            initialMean: Double = 25.0,
            initialStdDev: Double = 25.0 / 3.0,
        ): Rating = Rating(initialMean, initialStdDev)
    }
}
