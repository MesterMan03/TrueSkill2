package info.mester.trueskill

import info.mester.trueskill.internal.GaussianDistribution
import info.mester.trueskill.internal.inference.OnlineInference
import kotlin.math.exp
import kotlin.math.sqrt

/** Online TrueSkill 2 rating and prediction API. */
class TrueSkill2(
    val config: TrueSkillConfig = TrueSkillConfig.default(),
) {
    private val inference = OnlineInference(config)

    fun updateRatings(match: Match): Match {
        val ratings = inference.rate(match).ratings
        return match.withRatings(ratings)
    }

    /**
     * Updates persistent base/mode-offset distributions, allowing evidence from one mode to
     * improve a player's initial estimate in other modes.
     */
    fun updateRatings(
        match: Match,
        state: TrueSkill2State,
    ): RatingUpdate {
        require(config.modeCorrelation != null) {
            "modeCorrelation must be configured when using persistent TrueSkill2State"
        }
        val result = inference.rate(match, state)
        return RatingUpdate(match.withRatings(result.ratings), requireNotNull(result.state))
    }

    /** Returns the marginal rating for a mode from persistent correlated-mode state. */
    fun rating(
        playerId: String,
        mode: String,
        state: TrueSkill2State,
    ): Rating {
        val correlation =
            requireNotNull(config.modeCorrelation) {
                "modeCorrelation must be configured when reading persistent TrueSkill2State"
            }
        val player = state.players[playerId] ?: return config.defaultRating()
        val offset = player.modeOffsets[mode] ?: defaultModeOffset()
        return Rating(
            correlation.baseWeight * player.base.mean + offset.mean,
            sqrt(
                correlation.baseWeight * correlation.baseWeight *
                    player.base.standardDeviation * player.base.standardDeviation +
                    offset.standardDeviation * offset.standardDeviation,
            ),
        )
    }

    fun calculateWinProbability(
        team1: Team,
        team2: Team,
    ): Double {
        val first = teamPerformance(team1)
        val second = teamPerformance(team2)
        val differenceStdDev = sqrt(first.variance + second.variance)
        val performanceNoise =
            config.beta *
                sqrt(
                    team1.players.sumOf { it.partialPlayPercentage * it.partialPlayPercentage } +
                        team2.players.sumOf { it.partialPlayPercentage * it.partialPlayPercentage },
                )
        val margin = config.drawMarginForPerformanceNoise(performanceNoise)
        return GaussianDistribution.standardNormalCdf((first.mean - second.mean - margin) / differenceStdDev)
    }

    fun calculateDrawProbability(
        team1: Team,
        team2: Team,
    ): Double {
        val first = teamPerformance(team1)
        val second = teamPerformance(team2)
        val stdDev = sqrt(first.variance + second.variance)
        val performanceNoise =
            config.beta *
                sqrt(
                    team1.players.sumOf { it.partialPlayPercentage * it.partialPlayPercentage } +
                        team2.players.sumOf { it.partialPlayPercentage * it.partialPlayPercentage },
                )
        val margin = config.drawMarginForPerformanceNoise(performanceNoise)
        val delta = first.mean - second.mean
        return GaussianDistribution.standardNormalCdf((margin - delta) / stdDev) -
            GaussianDistribution.standardNormalCdf((-margin - delta) / stdDev)
    }

    fun calculateMatchQuality(vararg teams: Team): Double {
        require(teams.size >= 2) { "Need at least 2 teams" }
        if (teams.size > 2) {
            return teams.indices
                .flatMap { left -> ((left + 1)..<teams.size).map { right -> left to right } }
                .map { (left, right) -> calculateMatchQuality(teams[left], teams[right]) }
                .average()
        }

        val first = teamPerformance(teams[0], includePerformanceNoise = false)
        val second = teamPerformance(teams[1], includePerformanceNoise = false)
        val skillVariance = first.variance + second.variance
        val performanceVariance =
            config.beta * config.beta *
                (
                    teams[0].players.sumOf { it.partialPlayPercentage * it.partialPlayPercentage } +
                        teams[1].players.sumOf { it.partialPlayPercentage * it.partialPlayPercentage }
                )
        val totalVariance = skillVariance + performanceVariance
        val delta = first.mean - second.mean
        return sqrt(performanceVariance / totalVariance) * exp(-(delta * delta) / (2.0 * totalVariance))
    }

    private fun teamPerformance(
        team: Team,
        includePerformanceNoise: Boolean = true,
    ): TeamPerformance {
        val squadSizes =
            team.players
                .mapNotNull { it.squadId }
                .groupingBy { it }
                .eachCount()
        var mean = 0.0
        var variance = 0.0
        team.players.forEach { player ->
            val weight = player.partialPlayPercentage
            val squadSize = player.squadId?.let { squadSizes[it] } ?: 1
            mean += weight * (player.rating.mean + (config.squadOffsets[squadSize] ?: 0.0))
            variance += weight * weight * player.rating.standardDeviation * player.rating.standardDeviation
            if (includePerformanceNoise) variance += weight * weight * config.beta * config.beta
        }
        return TeamPerformance(mean, variance)
    }

    private fun defaultModeOffset(): Rating {
        val correlation = requireNotNull(config.modeCorrelation)
        val variance =
            config.initialStdDev * config.initialStdDev -
                correlation.baseWeight * correlation.baseWeight *
                correlation.initialBaseStdDev * correlation.initialBaseStdDev
        return Rating(config.initialMean, sqrt(variance))
    }

    private fun Match.withRatings(ratings: Map<String, Rating>): Match =
        copy(
            teams =
                teams.map { team ->
                    team.withPlayers(team.players.map { it.withRating(ratings.getValue(it.id)) })
                },
        )

    private data class TeamPerformance(
        val mean: Double,
        val variance: Double,
    )
}
