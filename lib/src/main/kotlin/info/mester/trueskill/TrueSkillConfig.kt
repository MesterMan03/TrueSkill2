package info.mester.trueskill

/**
 * Configuration parameters for the TrueSkill2 algorithm.
 * 
 * These parameters control the behavior of skill rating calculations.
 * Default values are based on Microsoft's Gears of War 4 implementation.
 * 
 * @property initialMean Initial mean skill rating for new players (μ₀)
 * @property initialStdDev Initial standard deviation for new players (σ₀)
 * @property beta Performance variation parameter. Represents the difference in skill
 *               needed for an 80% win probability. Higher values make rating changes slower.
 * @property tau Dynamics factor. Controls how quickly uncertainty grows over time
 *               to account for skill changes. Higher values increase uncertainty faster.
 * @property drawProbability Probability of a draw, used to calculate draw margin
 * @property drawMargin The margin within which a match is considered a draw.
 *                      If not specified, calculated from drawProbability.
 */
data class TrueSkillConfig(
    val initialMean: Double = 25.0,
    val initialStdDev: Double = 25.0 / 3.0,
    val beta: Double = initialStdDev / 2.0,
    val tau: Double = initialStdDev / 100.0,
    val drawProbability: Double = 0.0,
    val drawMargin: Double? = null
) {
    init {
        require(initialMean > 0) { "Initial mean must be positive" }
        require(initialStdDev > 0) { "Initial standard deviation must be positive" }
        require(beta > 0) { "Beta must be positive" }
        require(tau >= 0) { "Tau must be non-negative" }
        require(drawProbability in 0.0..1.0) { "Draw probability must be between 0 and 1" }
        drawMargin?.let { require(it >= 0) { "Draw margin must be non-negative" } }
    }
    
    /**
     * The actual draw margin used in calculations.
     * If not explicitly set, calculated from beta and draw probability.
     */
    val effectiveDrawMargin: Double
        get() = drawMargin ?: (beta * kotlin.math.sqrt(2.0) * 
            info.mester.trueskill.internal.GaussianDistribution.standardNormalInverseCdf((1.0 + drawProbability) / 2.0))
    
    /**
     * Creates a default rating for a new player using this configuration.
     */
    fun defaultRating(): Rating {
        return Rating(initialMean, initialStdDev)
    }
    
    companion object {
        /**
         * Default configuration based on Microsoft's recommendations.
         */
        fun default(): TrueSkillConfig = TrueSkillConfig()
        
        /**
         * Configuration for games with frequent draws.
         */
        fun withDraws(drawProbability: Double = 0.1): TrueSkillConfig {
            return TrueSkillConfig(drawProbability = drawProbability)
        }
        
        /**
         * Configuration optimized for Gears of War 4 (from the paper).
         */
        fun gearsOfWar4(): TrueSkillConfig {
            return TrueSkillConfig(
                initialMean = 25.0,
                initialStdDev = 25.0 / 3.0,
                beta = 25.0 / 6.0,
                tau = 25.0 / 300.0,
                drawProbability = 0.0
            )
        }
    }
}
