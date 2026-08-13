package info.mester.trueskill

import info.mester.trueskill.internal.inference.HistoryInference

/**
 * Batch TrueSkill Through Time inference.
 *
 * Unlike online filtering, [processMatchHistory] builds a joint factor graph for the whole
 * history and repeatedly propagates evidence in both directions. A later result can therefore
 * change an earlier rating estimate.
 */
class TrueSkillThroughTime(
    val config: TrueSkillConfig = TrueSkillConfig.default(),
    val timeUnit: TimeUnit = TimeUnit.DAYS,
) {
    enum class TimeUnit(
        val milliseconds: Long,
    ) {
        SECONDS(1_000L),
        MINUTES(60_000L),
        HOURS(3_600_000L),
        DAYS(86_400_000L),
    }

    data class TimestampedRating(
        val rating: Rating,
        val timestamp: Long,
    )

    fun processMatchHistory(matches: List<Match>): List<Match> {
        require(matches.all { it.timestamp != null }) { "All matches must have timestamps for TTT" }
        require(matches.zipWithNext().all { (left, right) -> left.timestamp!! <= right.timestamp!! }) {
            "Matches must be in chronological order"
        }
        val effectiveConfig = config.copy(timeUnitMillis = timeUnit.milliseconds)
        val ratings = HistoryInference(effectiveConfig).rate(matches)
        return matches.mapIndexed { index, match ->
            match.copy(
                teams =
                    match.teams.map { team ->
                        team.withPlayers(team.players.map { it.withRating(ratings[index].getValue(it.id)) })
                    },
            )
        }
    }

    /** Online convenience update; use [processMatchHistory] when smoothing is required. */
    fun updateRatings(
        match: Match,
        lastMatchTimes: Map<String, Long> = emptyMap(),
    ): Pair<Match, Map<String, Long>> {
        val timestamp = requireNotNull(match.timestamp) { "Match must have a timestamp for TTT" }
        val effectiveConfig = config.copy(timeUnitMillis = timeUnit.milliseconds)
        val playersWithDynamics =
            match.teams.map { team ->
                team.withPlayers(
                    team.players.map { player ->
                        val previous = lastMatchTimes[player.id]
                        val elapsed =
                            if (previous == null) 0.0 else (timestamp - previous).toDouble() / timeUnit.milliseconds
                        player.withRating(player.rating.applyDynamics(elapsed, effectiveConfig.tau))
                    },
                )
            }
        val updated = TrueSkill2(effectiveConfig).updateRatings(match.copy(teams = playersWithDynamics))
        val times = lastMatchTimes + updated.teams.flatMap { it.players }.associate { it.id to timestamp }
        return updated to times
    }

    fun calculateWinProbability(
        team1: Team,
        team2: Team,
        currentTime: Long,
        team1LastTime: Long,
        team2LastTime: Long,
    ): Double {
        val first = applyDynamics(team1, team1LastTime, currentTime)
        val second = applyDynamics(team2, team2LastTime, currentTime)
        return TrueSkill2(config).calculateWinProbability(first, second)
    }

    fun predictFutureRating(
        player: Player,
        currentTime: Long,
        futureTime: Long,
    ): Rating {
        require(futureTime >= currentTime) { "Future time must be >= current time" }
        val elapsed = (futureTime - currentTime).toDouble() / timeUnit.milliseconds
        return player.rating.applyDynamics(elapsed, config.tau)
    }

    fun getPlayerHistory(
        playerId: String,
        processedMatches: List<Match>,
    ): List<TimestampedRating> =
        processedMatches.mapNotNull { match ->
            val rating =
                match.teams
                    .flatMap { it.players }
                    .find { it.id == playerId }
                    ?.rating
            val timestamp = match.timestamp
            if (rating == null || timestamp == null) null else TimestampedRating(rating, timestamp)
        }

    private fun applyDynamics(
        team: Team,
        previous: Long,
        current: Long,
    ): Team {
        require(current >= previous)
        val elapsed = (current - previous).toDouble() / timeUnit.milliseconds
        return team.withPlayers(team.players.map { it.withRating(it.rating.applyDynamics(elapsed, config.tau)) })
    }

    companion object {
        fun default(): TrueSkillThroughTime = TrueSkillThroughTime()

        fun withTimeUnit(
            timeUnit: TimeUnit,
            config: TrueSkillConfig = TrueSkillConfig.default(),
        ): TrueSkillThroughTime = TrueSkillThroughTime(config, timeUnit)
    }
}
