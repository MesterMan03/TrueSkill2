package info.mester.trueskill.internal.inference

import info.mester.trueskill.Match
import info.mester.trueskill.Player
import info.mester.trueskill.PlayerSkillState
import info.mester.trueskill.Rating
import info.mester.trueskill.Team
import info.mester.trueskill.TrueSkill2State
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

internal data class InferenceResult(
    val ratings: Map<String, Rating>,
    val state: TrueSkill2State?,
)

/** Builds one composable EP graph from match outcome and optional TrueSkill 2 evidence. */
internal class OnlineInference(
    private val config: TrueSkillConfig,
) {
    fun rate(
        match: Match,
        state: TrueSkill2State? = null,
    ): InferenceResult {
        val factors = mutableListOf<Factor>()
        val skillVariables = mutableMapOf<String, Variable>()
        val baseVariables = mutableMapOf<String, Variable>()
        val offsetVariables = mutableMapOf<String, Variable>()
        val initialStates = mutableMapOf<String, PlayerSkillState>()
        val performanceVariables = mutableMapOf<String, Variable>()

        match.teams.flatMap { it.players }.forEach { player ->
            val performance = Variable("performance:${player.id}")
            performanceVariables[player.id] = performance
            val squadOffset = squadOffset(player, match)

            if (state != null && config.modeCorrelation != null) {
                val playerState = stateFor(player, match, state)
                initialStates[player.id] = playerState
                val base = Variable("base:${player.id}")
                val offset = Variable("offset:${player.id}:${match.mode}")
                baseVariables[player.id] = base
                offsetVariables[player.id] = offset
                val baseElapsed = elapsedUnits(playerState.lastPlayedAt, match.timestamp)
                val offsetElapsed = elapsedUnits(playerState.lastPlayedByMode[match.mode], match.timestamp)
                val correlation = config.modeCorrelation
                factors +=
                    PriorFactor(
                        base,
                        playerState.base.mean,
                        playerState.base.standardDeviationSquared +
                            baseElapsed * correlation.baseTimeDriftPerUnit * correlation.baseTimeDriftPerUnit,
                    )
                val modeOffset = playerState.modeOffsets.getValue(match.mode)
                factors +=
                    PriorFactor(
                        offset,
                        modeOffset.mean,
                        modeOffset.standardDeviationSquared + offsetElapsed * config.tau * config.tau,
                    )
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
                        squadOffset,
                    )
            } else {
                val skill = Variable("skill:${player.id}")
                skillVariables[player.id] = skill
                factors += PriorFactor(skill, player.rating.mean, player.rating.standardDeviationSquared)
                factors +=
                    NoisyWeightedSumFactor(
                        performance,
                        listOf(skill),
                        listOf(1.0),
                        config.beta * config.beta,
                        squadOffset,
                    )
            }
        }

        val teamVariables = addTeamOutcomeFactors(match, performanceVariables, factors)
        addStatisticFactors(match, performanceVariables, factors)
        addQuitFactors(match, performanceVariables, factors)

        FactorGraph(factors).run()

        val ratings =
            if (state != null && config.modeCorrelation != null) {
                correlatedRatings(match, baseVariables, offsetVariables)
            } else {
                directRatings(match, skillVariables)
            }

        val updatedState =
            if (state != null && config.modeCorrelation != null) {
                updatedState(match, state, initialStates, baseVariables, offsetVariables)
            } else {
                null
            }

        // Retain a strong reference until inference completes; variables are connected via factors.
        check(teamVariables.size == match.teams.size)
        return InferenceResult(ratings, updatedState)
    }

    private fun addTeamOutcomeFactors(
        match: Match,
        performances: Map<String, Variable>,
        factors: MutableList<Factor>,
    ): Map<Team, Variable> {
        val teams = match.sortedTeams
        val teamVariables =
            teams.associateWith { team ->
                val variable = Variable("team:${team.rank}:${team.players.joinToString { it.id }}")
                val participating = team.players.filter { it.partialPlayPercentage > 0.0 }
                factors +=
                    NoisyWeightedSumFactor(
                        variable,
                        participating.map { performances.getValue(it.id) },
                        participating.map { it.partialPlayPercentage },
                    )
                variable
            }

        teams.zipWithNext().forEachIndexed { index, (higher, lower) ->
            val difference = Variable("team-difference:$index")
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
        return teamVariables
    }

    private fun addStatisticFactors(
        match: Match,
        performances: Map<String, Variable>,
        factors: MutableList<Factor>,
    ) {
        if (config.statisticModels.isEmpty()) return
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
                val nonZeroTerms = terms.filterValues { it != 0.0 }
                if (nonZeroTerms.isEmpty()) return@statistic
                val variables = nonZeroTerms.keys.map { performances.getValue(it) }
                val coefficients = nonZeroTerms.values.toList()
                val noiseVariance = model.variancePerTimeUnit * played

                if (observed > 0.0) {
                    factors += GaussianObservationFactor(observed, variables, coefficients, noiseVariance)
                } else {
                    val latent = Variable("statistic:$name:${player.id}")
                    factors += NoisyWeightedSumFactor(latent, variables, coefficients, noiseVariance)
                    factors += OutcomeFactor.LessThan(latent, 0.0)
                }
            }
        }
    }

    private fun addQuitFactors(
        match: Match,
        performances: Map<String, Variable>,
        factors: MutableList<Factor>,
    ) {
        val quitModel = config.quitModel ?: return
        val playersWithTeams = playersWithTeams(match)
        playersWithTeams.filter { it.first.quit }.forEach { (player, team) ->
            val terms = mutableMapOf(player.id to -1.0)
            opponentWeights(team, playersWithTeams).forEach { (opponent, weight) ->
                terms[opponent.id] = (terms[opponent.id] ?: 0.0) + weight
            }
            val nonZeroTerms = terms.filterValues { it != 0.0 }
            val underperformance = Variable("quit:${player.id}")
            factors +=
                NoisyWeightedSumFactor(
                    underperformance,
                    nonZeroTerms.keys.map { performances.getValue(it) },
                    nonZeroTerms.values.toList(),
                    quitModel.variance,
                    quitModel.underperformanceMean,
                )
            factors += OutcomeFactor.GreaterThan(underperformance, 0.0)
        }
    }

    private fun directRatings(
        match: Match,
        skills: Map<String, Variable>,
    ): Map<String, Rating> =
        match.teams
            .flatMap { it.players }
            .associate { player ->
                val posterior = skills.getValue(player.id).value
                player.id to
                    Rating(
                        posterior.mean + config.experienceOffset(player.experience),
                        sqrt(posterior.variance + config.skillChangePerMatch * config.skillChangePerMatch),
                    )
            }

    private fun correlatedRatings(
        match: Match,
        bases: Map<String, Variable>,
        offsets: Map<String, Variable>,
    ): Map<String, Rating> {
        val weight = config.modeCorrelation!!.baseWeight
        return match.teams
            .flatMap { it.players }
            .associate { player ->
                val base = bases.getValue(player.id).value
                val offset = offsets.getValue(player.id).value
                player.id to
                    Rating(
                        weight * base.mean + offset.mean,
                        sqrt(weight * weight * base.variance + offset.variance),
                    )
            }
    }

    private fun updatedState(
        match: Match,
        previous: TrueSkill2State,
        initial: Map<String, PlayerSkillState>,
        bases: Map<String, Variable>,
        offsets: Map<String, Variable>,
    ): TrueSkill2State {
        val correlation = config.modeCorrelation!!
        val players = previous.players.toMutableMap()
        match.teams.flatMap { it.players }.forEach { player ->
            val old = initial.getValue(player.id)
            val experience = old.experienceByMode[match.mode] ?: 0
            val basePosterior = bases.getValue(player.id).value
            val offsetPosterior = offsets.getValue(player.id).value
            val updatedOffset =
                Rating(
                    offsetPosterior.mean + config.experienceOffset(experience),
                    sqrt(offsetPosterior.variance + config.skillChangePerMatch * config.skillChangePerMatch),
                )
            players[player.id] =
                old.copy(
                    base =
                        Rating(
                            basePosterior.mean,
                            sqrt(
                                basePosterior.variance +
                                    correlation.baseSkillChangePerMatch * correlation.baseSkillChangePerMatch,
                            ),
                        ),
                    modeOffsets = old.modeOffsets + (match.mode to updatedOffset),
                    experienceByMode = old.experienceByMode + (match.mode to experience + 1),
                    lastPlayedAt = match.timestamp ?: old.lastPlayedAt,
                    lastPlayedByMode =
                        if (match.timestamp == null) {
                            old.lastPlayedByMode
                        } else {
                            old.lastPlayedByMode + (match.mode to match.timestamp)
                        },
                )
        }
        return TrueSkill2State(players)
    }

    private fun stateFor(
        player: Player,
        match: Match,
        state: TrueSkill2State,
    ): PlayerSkillState {
        val correlation = config.modeCorrelation!!
        val existing = state.players[player.id]
        val base =
            existing?.base ?: Rating(
                0.0,
                minOf(correlation.initialBaseStdDev, player.rating.standardDeviation / max(correlation.baseWeight, 1e-9)),
            )
        val firstOffsetVariance =
            max(
                player.rating.standardDeviationSquared -
                    correlation.baseWeight * correlation.baseWeight * base.standardDeviationSquared,
                1e-9,
            )
        val initialOffsetVariance =
            config.initialStdDev * config.initialStdDev -
                correlation.baseWeight * correlation.baseWeight *
                correlation.initialBaseStdDev * correlation.initialBaseStdDev
        val offset =
            existing?.modeOffsets?.get(match.mode)
                ?: Rating(
                    player.rating.mean,
                    sqrt(if (existing == null) firstOffsetVariance else initialOffsetVariance),
                )
        return existing?.copy(modeOffsets = existing.modeOffsets + (match.mode to offset))
            ?: PlayerSkillState(base, mapOf(match.mode to offset))
    }

    private fun elapsedUnits(
        previous: Long?,
        current: Long?,
    ): Double {
        if (previous == null || current == null) return 0.0
        require(current >= previous) { "Match timestamp cannot precede a player's last match" }
        return (current - previous).toDouble() / config.timeUnitMillis.toDouble()
    }

    private fun squadOffset(
        player: Player,
        match: Match,
    ): Double {
        val squadId = player.squadId ?: return config.squadOffsets[1] ?: 0.0
        val squadSize = match.teams.flatMap { it.players }.count { it.squadId == squadId }
        return config.squadOffsets[squadSize] ?: 0.0
    }

    private fun playersWithTeams(match: Match): List<Pair<Player, Team>> = match.teams.flatMap { team -> team.players.map { it to team } }

    /** Equation (11) in the TrueSkill 2 paper. */
    private fun opponentWeights(
        team: Team,
        playersWithTeams: List<Pair<Player, Team>>,
    ): Map<Player, Double> {
        val opponents = playersWithTeams.filter { it.second !== team }
        return opponents.associate { (opponent, opponentTeam) ->
            val opposingForOpponent =
                playersWithTeams
                    .filter { it.second !== opponentTeam }
                    .sumOf { it.first.partialPlayPercentage }
            opponent to
                if (opposingForOpponent == 0.0) {
                    0.0
                } else {
                    opponent.partialPlayPercentage / opposingForOpponent
                }
        }
    }

    private val Rating.standardDeviationSquared: Double
        get() = standardDeviation * standardDeviation
}
