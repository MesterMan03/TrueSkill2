package info.mester.trueskill

/**
 * Represents a match result with teams and their rankings.
 *
 * @property teams List of teams that participated in the match
 * @property timestamp Optional timestamp for TrueSkill Through Time calculations
 */
data class Match(
    val teams: List<Team>,
    val timestamp: Long? = null,
) {
    init {
        require(teams.size >= 2) { "Match must have at least 2 teams" }
        require(teams.map { it.rank }.toSet().size > 1 || teams.size == 2) {
            "Match must have different rankings or be a 2-team match"
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
}
