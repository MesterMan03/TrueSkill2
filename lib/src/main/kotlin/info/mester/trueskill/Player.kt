package info.mester.trueskill

/**
 * Represents a player with a unique identifier and skill rating.
 *
 * @property id Unique identifier for the player
 * @property rating The player's current skill rating
 * @property partialPlayPercentage Percentage of game participation (0.0 to 1.0).
 *                                 Used for quit penalty - players who quit early
 *                                 receive reduced rating updates. Default is 1.0 (full participation).
 */
data class Player(
    val id: String,
    val rating: Rating = Rating.defaultRating(),
    val partialPlayPercentage: Double = 1.0,
) {
    init {
        require(partialPlayPercentage in 0.0..1.0) {
            "Partial play percentage must be between 0.0 and 1.0"
        }
    }

    /**
     * Creates a copy of this player with an updated rating.
     */
    fun withRating(newRating: Rating): Player = copy(rating = newRating)

    /**
     * Creates a copy of this player with updated partial play percentage.
     */
    fun withPartialPlay(percentage: Double): Player = copy(partialPlayPercentage = percentage)

    override fun toString(): String = "Player(id='$id', rating=$rating, partialPlay=${"%.2f".format(partialPlayPercentage)})"
}
