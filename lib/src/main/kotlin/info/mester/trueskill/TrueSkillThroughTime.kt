package info.mester.trueskill

/**
 * TrueSkill Through Time (TTT) calculator.
 *
 * This extension of TrueSkill2 handles temporal dynamics by:
 * - Tracking skill changes over time
 * - Applying dynamics (uncertainty growth) between matches
 * - Supporting retroactive rating calculations
 * - Batch processing of match histories
 *
 * The key difference from standard TrueSkill2 is that TTT considers the time
 * between matches when calculating ratings. Longer gaps increase uncertainty
 * to account for potential skill changes.
 *
 * @property config Configuration parameters for the algorithm
 * @property timeUnit The unit of time used for timestamp calculations (e.g., seconds, days)
 */
class TrueSkillThroughTime(
    val config: TrueSkillConfig = TrueSkillConfig.default(),
    val timeUnit: TimeUnit = TimeUnit.DAYS,
) {
    private val baseCalculator = TrueSkill2(config)

    /**
     * Time unit enumeration for timestamp handling.
     */
    enum class TimeUnit(
        val milliseconds: Long,
    ) {
        SECONDS(1000L),
        MINUTES(60_000L),
        HOURS(3_600_000L),
        DAYS(86_400_000L),
    }

    /**
     * Represents a player's rating at a specific point in time.
     */
    data class TimestampedRating(
        val rating: Rating,
        val timestamp: Long,
    )

    /**
     * Calculate win probability between two teams at a specific time,
     * accounting for dynamics since their last match.
     */
    fun calculateWinProbability(
        team1: Team,
        team2: Team,
        currentTime: Long,
        team1LastTime: Long,
        team2LastTime: Long,
    ): Double {
        val updatedTeam1 = applyDynamicsToTeam(team1, team1LastTime, currentTime)
        val updatedTeam2 = applyDynamicsToTeam(team2, team2LastTime, currentTime)

        return baseCalculator.calculateWinProbability(updatedTeam1, updatedTeam2)
    }

    /**
     * Update player ratings after a match, accounting for time dynamics.
     *
     * @param match The match result with timestamp
     * @param lastMatchTimes Map of player IDs to their last match timestamp
     * @return Updated match and map of updated timestamps
     */
    fun updateRatings(
        match: Match,
        lastMatchTimes: Map<String, Long> = emptyMap(),
    ): Pair<Match, Map<String, Long>> {
        require(match.timestamp != null) { "Match must have a timestamp for TTT" }

        // Apply dynamics to all players based on time since last match
        val teamsWithDynamics =
            match.teams.map { team ->
                val updatedPlayers =
                    team.players.map { player ->
                        val lastTime = lastMatchTimes[player.id] ?: match.timestamp
                        val timeDelta = calculateTimeDelta(lastTime, match.timestamp)
                        val updatedRating = player.rating.applyDynamics(timeDelta, config.tau)
                        player.withRating(updatedRating)
                    }
                team.withPlayers(updatedPlayers)
            }

        // Update ratings using standard algorithm
        val matchWithDynamics = match.copy(teams = teamsWithDynamics)
        val updatedMatch = baseCalculator.updateRatings(matchWithDynamics)

        // Update timestamp map
        val updatedTimes = lastMatchTimes.toMutableMap()
        for (team in updatedMatch.teams) {
            for (player in team.players) {
                updatedTimes[player.id] = match.timestamp
            }
        }

        return Pair(updatedMatch, updatedTimes)
    }

    /**
     * Process a sequence of matches in chronological order.
     *
     * This is the primary method for TTT - it processes an entire match history
     * to calculate ratings that account for temporal dynamics.
     *
     * @param matches List of matches in chronological order (must have timestamps)
     * @return List of matches with updated player ratings after each match
     */
    fun processMatchHistory(matches: List<Match>): List<Match> {
        require(matches.all { it.timestamp != null }) {
            "All matches must have timestamps for TTT"
        }

        // Extract timestamps for validation (they're guaranteed to be non-null at this point)
        val timestamps = matches.map { it.timestamp ?: error("Internal error: timestamp should not be null") }
        require(timestamps.zipWithNext().all { (a, b) -> a <= b }) {
            "Matches must be in chronological order"
        }

        val result = mutableListOf<Match>()
        val playerRatings = mutableMapOf<String, Rating>()
        val lastMatchTimes = mutableMapOf<String, Long>()

        for (match in matches) {
            // Build teams with current ratings
            val teamsWithCurrentRatings =
                match.teams.map { team ->
                    val playersWithRatings =
                        team.players.map { player ->
                            val currentRating = playerRatings[player.id] ?: config.defaultRating()
                            player.copy(rating = currentRating)
                        }
                    team.withPlayers(playersWithRatings)
                }

            val matchWithCurrentRatings = match.copy(teams = teamsWithCurrentRatings)

            // Update ratings
            val (updatedMatch, updatedTimes) = updateRatings(matchWithCurrentRatings, lastMatchTimes)

            // Store updated ratings
            for (team in updatedMatch.teams) {
                for (player in team.players) {
                    playerRatings[player.id] = player.rating
                }
            }
            lastMatchTimes.putAll(updatedTimes)

            result.add(updatedMatch)
        }

        return result
    }

    /**
     * Get the historical rating trajectory for a player across multiple matches.
     *
     * @param playerId The player ID to track
     * @param processedMatches List of matches that have been processed with TTT
     * @return List of timestamped ratings showing the player's rating evolution
     */
    fun getPlayerHistory(
        playerId: String,
        processedMatches: List<Match>,
    ): List<TimestampedRating> {
        val history = mutableListOf<TimestampedRating>()

        for (match in processedMatches) {
            for (team in match.teams) {
                val player = team.players.find { it.id == playerId }
                if (player != null && match.timestamp != null) {
                    history.add(TimestampedRating(player.rating, match.timestamp))
                    break
                }
            }
        }

        return history
    }

    /**
     * Predict the rating a player would have at a future time,
     * accounting for skill uncertainty growth.
     *
     * @param player The player
     * @param currentTime Current timestamp
     * @param futureTime Future timestamp
     * @return Predicted rating with increased uncertainty
     */
    fun predictFutureRating(
        player: Player,
        currentTime: Long,
        futureTime: Long,
    ): Rating {
        require(futureTime >= currentTime) { "Future time must be >= current time" }

        val timeDelta = calculateTimeDelta(currentTime, futureTime)
        return player.rating.applyDynamics(timeDelta, config.tau)
    }

    /**
     * Apply dynamics to all players in a team.
     */
    private fun applyDynamicsToTeam(
        team: Team,
        lastTime: Long,
        currentTime: Long,
    ): Team {
        val timeDelta = calculateTimeDelta(lastTime, currentTime)
        val updatedPlayers =
            team.players.map { player ->
                val updatedRating = player.rating.applyDynamics(timeDelta, config.tau)
                player.withRating(updatedRating)
            }
        return team.withPlayers(updatedPlayers)
    }

    /**
     * Calculate time delta in the configured time unit.
     */
    private fun calculateTimeDelta(
        fromTime: Long,
        toTime: Long,
    ): Double {
        val millisDelta = toTime - fromTime
        return millisDelta.toDouble() / timeUnit.milliseconds.toDouble()
    }

    companion object {
        /**
         * Create a TTT calculator with default configuration.
         */
        fun default(): TrueSkillThroughTime = TrueSkillThroughTime()

        /**
         * Create a TTT calculator optimized for games with specific time units.
         */
        fun withTimeUnit(
            timeUnit: TimeUnit,
            config: TrueSkillConfig = TrueSkillConfig.default(),
        ): TrueSkillThroughTime = TrueSkillThroughTime(config, timeUnit)
    }
}
