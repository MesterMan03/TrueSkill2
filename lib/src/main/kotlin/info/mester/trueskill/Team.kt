package info.mester.trueskill

/**
 * Represents a team of players in a match.
 * 
 * @property players List of players on this team
 * @property rank The rank/placement of this team in the match (1 = winner, 2 = second place, etc.).
 *                Lower rank values indicate better performance. Teams with the same rank are considered tied.
 */
data class Team(
    val players: List<Player>,
    val rank: Int = 1
) {
    init {
        require(players.isNotEmpty()) { "Team must have at least one player" }
        require(rank > 0) { "Rank must be positive" }
    }
    
    /**
     * Creates a team from a single player.
     */
    constructor(player: Player, rank: Int = 1) : this(listOf(player), rank)
    
    /**
     * Creates a team from multiple players.
     */
    constructor(vararg players: Player, rank: Int = 1) : this(players.toList(), rank)
    
    /**
     * The mean rating of all players on this team.
     */
    val meanRating: Double
        get() = players.map { it.rating.mean }.average()
    
    /**
     * Creates a copy of this team with updated players.
     */
    fun withPlayers(newPlayers: List<Player>): Team {
        return copy(players = newPlayers)
    }
    
    /**
     * Creates a copy of this team with an updated rank.
     */
    fun withRank(newRank: Int): Team {
        return copy(rank = newRank)
    }
    
    override fun toString(): String {
        return "Team(rank=$rank, players=[${players.joinToString(", ") { it.id }}])"
    }
}
