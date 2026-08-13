package info.mester.trueskill

import info.mester.trueskill.internal.GaussianDistribution
import kotlin.math.sqrt

/** A linear-Gaussian observation model for an individual match statistic. */
data class StatisticModel(
    val playerPerformanceWeight: Double,
    val opponentPerformanceWeight: Double,
    val variancePerTimeUnit: Double,
) {
    init {
        require(playerPerformanceWeight.isFinite() && opponentPerformanceWeight.isFinite())
        require(variancePerTimeUnit > 0.0 && variancePerTimeUnit.isFinite())
    }
}

/** Parameters for TrueSkill 2's unnormalised online quit observation. */
data class QuitModel(
    val underperformanceMean: Double = 0.0,
    val variance: Double,
) {
    init {
        require(underperformanceMean <= 0.0 && underperformanceMean.isFinite())
        require(variance > 0.0 && variance.isFinite())
    }
}

/** One-dimensional base-skill model used to correlate ratings between game modes. */
data class ModeCorrelationConfig(
    val baseWeight: Double = 1.0,
    val initialBaseStdDev: Double,
    val baseSkillChangePerMatch: Double = 0.0,
    val baseTimeDriftPerUnit: Double = 0.0,
) {
    init {
        require(baseWeight >= 0.0 && baseWeight.isFinite())
        require(initialBaseStdDev > 0.0 && initialBaseStdDev.isFinite())
        require(baseSkillChangePerMatch >= 0.0 && baseSkillChangePerMatch.isFinite())
        require(baseTimeDriftPerUnit >= 0.0 && baseTimeDriftPerUnit.isFinite())
    }
}

/**
 * Parameters for online TrueSkill 2 inference.
 *
 * TrueSkill 2's data-dependent arrays are deliberately supplied by the caller: Microsoft
 * learned squad and experience offsets, statistic weights, and quit parameters from each
 * game's historical matches rather than claiming universal constants for them.
 */
data class TrueSkillConfig(
    val initialMean: Double,
    val initialStdDev: Double,
    val beta: Double,
    val tau: Double,
    val drawProbability: Double = 0.0,
    val drawMargin: Double? = null,
    val skillChangePerMatch: Double = 0.0,
    val squadOffsets: Map<Int, Double> = emptyMap(),
    val experienceOffsets: List<Double> = emptyList(),
    val statisticModels: Map<String, StatisticModel> = emptyMap(),
    val quitModel: QuitModel? = null,
    val modeCorrelation: ModeCorrelationConfig? = null,
    val timeUnitMillis: Long = 86_400_000L,
) {
    constructor(
        initialMean: Double = 25.0,
        initialStdDev: Double = 25.0 / 3.0,
        drawProbability: Double = 0.0,
        drawMargin: Double? = null,
    ) : this(
        initialMean = initialMean,
        initialStdDev = initialStdDev,
        beta = initialStdDev / 2.0,
        tau = initialStdDev / 100.0,
        drawProbability = drawProbability,
        drawMargin = drawMargin,
    )

    init {
        require(initialMean.isFinite()) { "Initial mean must be finite" }
        require(initialStdDev > 0.0 && initialStdDev.isFinite())
        require(beta > 0.0 && beta.isFinite())
        require(tau >= 0.0 && tau.isFinite())
        require(drawProbability in 0.0..<1.0)
        drawMargin?.let { require(it >= 0.0 && it.isFinite()) }
        require(skillChangePerMatch >= 0.0 && skillChangePerMatch.isFinite())
        require(squadOffsets.keys.all { it >= 1 } && squadOffsets.values.all { it.isFinite() })
        require(experienceOffsets.size <= 200 && experienceOffsets.all { it.isFinite() })
        require(timeUnitMillis > 0L)
        modeCorrelation?.let {
            require(it.baseWeight * it.initialBaseStdDev < initialStdDev) {
                "Correlated base variance must leave positive variance for mode offsets"
            }
        }
    }

    /** Compatibility value for a full-participation head-to-head match. */
    val effectiveDrawMargin: Double
        get() = drawMarginForPerformanceNoise(beta * sqrt(2.0))

    fun drawMarginForPerformanceNoise(performanceNoiseStdDev: Double): Double =
        drawMargin ?: (
            performanceNoiseStdDev *
                GaussianDistribution.standardNormalInverseCdf((1.0 + drawProbability) / 2.0)
        )

    fun experienceOffset(matchesPlayedInMode: Int): Double =
        if (experienceOffsets.isEmpty()) {
            0.0
        } else {
            experienceOffsets[matchesPlayedInMode.coerceIn(0, minOf(199, experienceOffsets.lastIndex))]
        }

    fun defaultRating(): Rating = Rating(initialMean, initialStdDev)

    companion object {
        fun default(): TrueSkillConfig = TrueSkillConfig()

        fun withDraws(drawProbability: Double = 0.1): TrueSkillConfig = TrueSkillConfig(drawProbability = drawProbability)

        /** Classic 25-point scale; TrueSkill 2 feature parameters still need to be learned. */
        fun gearsOfWar4(): TrueSkillConfig = TrueSkillConfig()
    }
}
