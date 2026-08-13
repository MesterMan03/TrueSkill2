package info.mester.trueskill

/**
 * Represents a player with a unique identifier and skill rating.
 *
 * @property id Unique identifier for the player
 * @property rating The player's current skill rating
 * @property partialPlayPercentage Fraction of the match played, used as the player's team weight.
 * @property squadId Identifier shared by players who queued as a squad. Null means solo.
 * @property statistics Observed individual statistics, keyed by configured statistic name.
 * @property quit Whether the player quit rather than merely joining late or disconnecting harmlessly.
 * @property experience Number of earlier matches played in this mode (capped at 200 by the model).
 */
data class Player(
    val id: String,
    val rating: Rating = Rating.defaultRating(),
    val partialPlayPercentage: Double = 1.0,
    val squadId: String? = null,
    val statistics: Map<String, Double> = emptyMap(),
    val quit: Boolean = false,
    val experience: Int = 0,
) {
    init {
        require(id.isNotBlank()) { "Player ID must not be blank" }
        require(partialPlayPercentage in 0.0..1.0) {
            "Partial play percentage must be between 0.0 and 1.0"
        }
        require(statistics.values.all { it >= 0.0 && it.isFinite() }) {
            "Player statistics must be finite and non-negative"
        }
        require(experience >= 0) { "Experience must be non-negative" }
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
