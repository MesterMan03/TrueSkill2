package info.mester.trueskill.internal.inference

import info.mester.trueskill.Match
import info.mester.trueskill.Player
import info.mester.trueskill.Rating
import info.mester.trueskill.Team
import info.mester.trueskill.TrueSkillConfig
import info.mester.trueskill.internal.factorgraph.Factor
import info.mester.trueskill.internal.factorgraph.FactorGraph
import info.mester.trueskill.internal.factorgraph.GaussianObservationFactor
import info.mester.trueskill.internal.factorgraph.NoisyWeightedSumFactor
import info.mester.trueskill.internal.factorgraph.OutcomeFactor
import info.mester.trueskill.internal.factorgraph.PriorFactor
import info.mester.trueskill.internal.factorgraph.Variable
import kotlin.math.max
import kotlin.math.sqrt

/** Full-history EP: later results send messages back into earlier skill variables. */
internal class HistoryInference(
    private val config: TrueSkillConfig,
) {
    private data class PreviousSkill(
        val variable: Variable,
        val timestamp: Long?,
        val experience: Int,
    )

    private data class LatentSkill(
        val direct: Variable? = null,
        val base: Variable? = null,
        val offset: Variable? = null,
    )

    fun rate(matches: List<Match>): List<Map<String, Rating>> {
        if (matches.isEmpty()) return emptyList()
        val factors = mutableListOf<Factor>()
        val skillsByMatch = mutableListOf<Map<String, LatentSkill>>()
        val previousDirectSkill = mutableMapOf<Pair<String, String>, PreviousSkill>()
        val previousBaseSkill = mutableMapOf<String, PreviousSkill>()
        val previousModeOffset = mutableMapOf<Pair<String, String>, PreviousSkill>()

        matches.forEachIndexed { matchIndex, match ->
            val skills = mutableMapOf<String, LatentSkill>()
            val performances = mutableMapOf<String, Variable>()
            match.teams.flatMap { it.players }.forEach { player ->
                val performance = Variable("history-performance:$matchIndex:${player.id}")
                performances[player.id] = performance
                val correlation = config.modeCorrelation
                if (correlation == null) {
                    val key = player.id to match.mode
                    val skill = Variable("history-skill:$matchIndex:${player.id}:${match.mode}")
                    val prior = previousDirectSkill[key]
                    addEvolution(
                        current = skill,
                        previous = prior,
                        initialMean = player.rating.mean,
                        initialVariance = player.rating.variance,
                        timestamp = match.timestamp,
                        changePerMatch = config.skillChangePerMatch,
                        timeDrift = config.tau,
                        experienceOffset = prior?.let { config.experienceOffset(it.experience) } ?: 0.0,
                        factors = factors,
                    )
                    skills[player.id] = LatentSkill(direct = skill)
                    previousDirectSkill[key] = PreviousSkill(skill, match.timestamp, player.experience)
                    factors +=
                        NoisyWeightedSumFactor(
                            performance,
                            listOf(skill),
                            listOf(1.0),
                            config.beta * config.beta,
                            squadOffset(player, match),
                        )
                } else {
                    val base = Variable("history-base:$matchIndex:${player.id}")
                    val offset = Variable("history-offset:$matchIndex:${player.id}:${match.mode}")
                    val basePrior = previousBaseSkill[player.id]
                    val modeKey = player.id to match.mode
                    val offsetPrior = previousModeOffset[modeKey]
                    val initialBaseVariance = initialBaseVariance(player)
                    addEvolution(
                        current = base,
                        previous = basePrior,
                        initialMean = 0.0,
                        initialVariance = initialBaseVariance,
                        timestamp = match.timestamp,
                        changePerMatch = correlation.baseSkillChangePerMatch,
                        timeDrift = correlation.baseTimeDriftPerUnit,
                        experienceOffset = 0.0,
                        factors = factors,
                    )
                    addEvolution(
                        current = offset,
                        previous = offsetPrior,
                        initialMean = player.rating.mean,
                        initialVariance =
                            max(
                                player.rating.variance - correlation.baseWeight * correlation.baseWeight * initialBaseVariance,
                                1e-9,
                            ),
                        timestamp = match.timestamp,
                        changePerMatch = config.skillChangePerMatch,
                        timeDrift = config.tau,
                        experienceOffset = offsetPrior?.let { config.experienceOffset(it.experience) } ?: 0.0,
                        factors = factors,
                    )
                    skills[player.id] = LatentSkill(base = base, offset = offset)
                    previousBaseSkill[player.id] = PreviousSkill(base, match.timestamp, player.experience)
                    previousModeOffset[modeKey] = PreviousSkill(offset, match.timestamp, player.experience)
                    val components =
                        if (correlation.baseWeight == 0.0) {
                            listOf(offset) to listOf(1.0)
                        } else {
                            listOf(base, offset) to listOf(correlation.baseWeight, 1.0)
                        }
                    factors +=
                        NoisyWeightedSumFactor(
                            performance,
                            components.first,
                            components.second,
                            config.beta * config.beta,
                            squadOffset(player, match),
                        )
                }
            }
            addOutcomeFactors(matchIndex, match, performances, factors)
            addStatisticFactors(matchIndex, match, performances, factors)
            addQuitFactors(matchIndex, match, performances, factors)
            skillsByMatch += skills
        }

        FactorGraph(factors, maxIterations = 250).run()
        return skillsByMatch.mapIndexed { index, skills ->
            val players = matches[index].teams.flatMap { it.players }.associateBy { it.id }
            skills.mapValues { (playerId, latent) ->
                val player = players.getValue(playerId)
                val experienceOffset = config.experienceOffset(player.experience)
                latent.direct?.let {
                    Rating(
                        it.value.mean + experienceOffset,
                        sqrt(it.value.variance + config.skillChangePerMatch * config.skillChangePerMatch),
                    )
                }
                    ?: run {
                        val correlation = config.modeCorrelation!!
                        val weight = correlation.baseWeight
                        val base = latent.base!!.value
                        val offset = latent.offset!!.value
                        Rating(
                            weight * base.mean + offset.mean + experienceOffset,
                            sqrt(
                                weight * weight *
                                    (
                                        base.variance +
                                            correlation.baseSkillChangePerMatch * correlation.baseSkillChangePerMatch
                                    ) +
                                    offset.variance + config.skillChangePerMatch * config.skillChangePerMatch,
                            ),
                        )
                    }
            }
        }
    }

    private fun addEvolution(
        current: Variable,
        previous: PreviousSkill?,
        initialMean: Double,
        initialVariance: Double,
        timestamp: Long?,
        changePerMatch: Double,
        timeDrift: Double,
        experienceOffset: Double,
        factors: MutableList<Factor>,
    ) {
        if (previous == null) {
            factors += PriorFactor(current, initialMean, initialVariance)
            return
        }
        val elapsed = elapsedUnits(previous.timestamp, timestamp)
        factors +=
            NoisyWeightedSumFactor(
                current,
                listOf(previous.variable),
                listOf(1.0),
                changePerMatch * changePerMatch + elapsed * timeDrift * timeDrift,
                experienceOffset,
            )
    }

    private fun initialBaseVariance(player: Player): Double {
        val correlation = config.modeCorrelation!!
        if (correlation.baseWeight == 0.0) return correlation.initialBaseStdDev * correlation.initialBaseStdDev
        return minOf(
            correlation.initialBaseStdDev * correlation.initialBaseStdDev,
            max((player.rating.variance - 1e-9) / (correlation.baseWeight * correlation.baseWeight), 1e-9),
        )
    }

    private fun addOutcomeFactors(
        matchIndex: Int,
        match: Match,
        performances: Map<String, Variable>,
        factors: MutableList<Factor>,
    ) {
        val teams = match.sortedTeams
        val teamVariables =
            teams.associateWith { team ->
                val variable = Variable("history-team:$matchIndex:${team.rank}")
                val players = team.players.filter { it.partialPlayPercentage > 0.0 }
                factors +=
                    NoisyWeightedSumFactor(
                        variable,
                        players.map { performances.getValue(it.id) },
                        players.map { it.partialPlayPercentage },
                    )
                variable
            }
        teams.zipWithNext().forEachIndexed { comparison, (higher, lower) ->
            val difference = Variable("history-difference:$matchIndex:$comparison")
            factors +=
                NoisyWeightedSumFactor(
                    difference,
                    listOf(teamVariables.getValue(higher), teamVariables.getValue(lower)),
                    listOf(1.0, -1.0),
                )
            val noiseStdDev =
                config.beta *
                    sqrt(
                        higher.players.sumOf { it.partialPlayPercentage * it.partialPlayPercentage } +
                            lower.players.sumOf { it.partialPlayPercentage * it.partialPlayPercentage },
                    )
            val margin = config.drawMarginForPerformanceNoise(noiseStdDev)
            factors +=
                if (higher.rank == lower.rank) {
                    OutcomeFactor.Draw(difference, margin)
                } else {
                    OutcomeFactor.GreaterThan(difference, margin)
                }
        }
    }

    private fun addStatisticFactors(
        matchIndex: Int,
        match: Match,
        performances: Map<String, Variable>,
        factors: MutableList<Factor>,
    ) {
        val playersWithTeams = playersWithTeams(match)
        playersWithTeams.forEach { (player, team) ->
            val played = match.duration * player.partialPlayPercentage
            if (played <= 0.0) return@forEach
            player.statistics.forEach statistic@{ (name, observed) ->
                val model = config.statisticModels[name] ?: return@statistic
                val terms = mutableMapOf(player.id to model.playerPerformanceWeight * played)
                opponentWeights(team, playersWithTeams).forEach { (opponent, weight) ->
                    terms[opponent.id] = (terms[opponent.id] ?: 0.0) + model.opponentPerformanceWeight * played * weight
                }
                val nonZero = terms.filterValues { it != 0.0 }
                if (nonZero.isEmpty()) return@statistic
                val variables = nonZero.keys.map { performances.getValue(it) }
                val coefficients = nonZero.values.toList()
                val noiseVariance = model.variancePerTimeUnit * played
                if (observed > 0.0) {
                    factors += GaussianObservationFactor(observed, variables, coefficients, noiseVariance)
                } else {
                    val latent = Variable("history-statistic:$matchIndex:$name:${player.id}")
                    factors += NoisyWeightedSumFactor(latent, variables, coefficients, noiseVariance)
                    factors += OutcomeFactor.LessThan(latent, 0.0)
                }
            }
        }
    }

    private fun addQuitFactors(
        matchIndex: Int,
        match: Match,
        performances: Map<String, Variable>,
        factors: MutableList<Factor>,
    ) {
        val model = config.quitModel ?: return
        val playersWithTeams = playersWithTeams(match)
        playersWithTeams.filter { it.first.quit }.forEach { (player, team) ->
            val terms = mutableMapOf(player.id to -1.0)
            opponentWeights(team, playersWithTeams).forEach { (opponent, weight) ->
                terms[opponent.id] = (terms[opponent.id] ?: 0.0) + weight
            }
            val nonZero = terms.filterValues { it != 0.0 }
            val latent = Variable("history-quit:$matchIndex:${player.id}")
            factors +=
                NoisyWeightedSumFactor(
                    latent,
                    nonZero.keys.map { performances.getValue(it) },
                    nonZero.values.toList(),
                    model.variance,
                    model.underperformanceMean,
                )
            factors += OutcomeFactor.GreaterThan(latent, 0.0)
        }
    }

    private fun opponentWeights(
        team: Team,
        playersWithTeams: List<Pair<Player, Team>>,
    ): Map<Player, Double> =
        playersWithTeams.filter { it.second !== team }.associate { (opponent, opponentTeam) ->
            val opposing =
                playersWithTeams.filter { it.second !== opponentTeam }.sumOf { it.first.partialPlayPercentage }
            opponent to if (opposing == 0.0) 0.0 else opponent.partialPlayPercentage / opposing
        }

    private fun playersWithTeams(match: Match): List<Pair<Player, Team>> = match.teams.flatMap { team -> team.players.map { it to team } }

    private fun squadOffset(
        player: Player,
        match: Match,
    ): Double {
        val squadId = player.squadId ?: return config.squadOffsets[1] ?: 0.0
        val size = match.teams.flatMap { it.players }.count { it.squadId == squadId }
        return config.squadOffsets[size] ?: 0.0
    }

    private fun elapsedUnits(
        previous: Long?,
        current: Long?,
    ): Double {
        if (previous == null || current == null) return 0.0
        require(current >= previous) { "Matches must be chronological for history inference" }
        return (current - previous).toDouble() / config.timeUnitMillis.toDouble()
    }

    private val Rating.variance: Double
        get() = standardDeviation * standardDeviation
}
