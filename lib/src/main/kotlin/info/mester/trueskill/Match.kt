package info.mester.trueskill

/**
 * Represents a match result with teams and their rankings.
 *
 * @property teams List of teams that participated in the match
 * @property timestamp Optional timestamp for TrueSkill Through Time calculations
 * @property mode Stable identifier for the game mode.
 * @property duration Length of the match in the unit used by statistic model parameters.
 */
data class Match(
    val teams: List<Team>,
    val timestamp: Long? = null,
    val mode: String = DEFAULT_MODE,
    val duration: Double = 1.0,
) {
    init {
        require(teams.size >= 2) { "Match must have at least 2 teams" }
        require(mode.isNotBlank()) { "Match mode must not be blank" }
        require(duration > 0.0 && duration.isFinite()) { "Match duration must be positive and finite" }
        val playerIds = teams.flatMap { it.players }.map { it.id }
        require(playerIds.size == playerIds.toSet().size) { "A player may only appear once in a match" }
        teams
            .flatMap { team -> team.players.mapNotNull { player -> player.squadId?.let { it to team } } }
            .groupBy({ it.first }, { it.second })
            .forEach { (squadId, squadTeams) ->
                require(squadTeams.distinct().size == 1) { "Squad '$squadId' cannot span multiple teams" }
            }
    }

    /**
     * Creates a match from two teams (most common case).
     */
    constructor(team1: Team, team2: Team, timestamp: Long? = null) :
        this(listOf(team1, team2), timestamp)

    /**
     * Creates a match with multiple teams.
     */
    constructor(vararg teams: Team, timestamp: Long? = null) :
        this(teams.toList(), timestamp)

    /**
     * Returns teams sorted by rank (winners first).
     */
    val sortedTeams: List<Team>
        get() = teams.sortedBy { it.rank }

    /**
     * Returns true if this is a two-team match.
     */
    val isTwoTeamMatch: Boolean
        get() = teams.size == 2

    /**
     * Returns true if the match ended in a draw/tie.
     */
    val isDraw: Boolean
        get() = teams.map { it.rank }.toSet().size == 1

    override fun toString(): String = "Match(teams=${teams.size}, timestamp=$timestamp)"

    companion object {
        const val DEFAULT_MODE = "default"
    }
}
