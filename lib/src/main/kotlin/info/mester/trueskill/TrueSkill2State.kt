package info.mester.trueskill

/** Persistent latent distributions for TrueSkill 2's correlated-mode online updater. */
data class PlayerSkillState(
    val base: Rating,
    val modeOffsets: Map<String, Rating> = emptyMap(),
    val experienceByMode: Map<String, Int> = emptyMap(),
    val lastPlayedAt: Long? = null,
    val lastPlayedByMode: Map<String, Long> = emptyMap(),
)

data class TrueSkill2State(
    val players: Map<String, PlayerSkillState> = emptyMap(),
)

data class RatingUpdate(
    val match: Match,
    val state: TrueSkill2State,
)
