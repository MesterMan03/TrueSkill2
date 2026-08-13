package info.mester.trueskill

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TrueSkill2Test {
    @Test
    fun `head-to-head win matches the published TrueSkill reference values`() {
        val calculator = TrueSkill2(TrueSkillConfig.withDraws(0.1))

        val result =
            calculator.updateRatings(
                Match(Team(Player("winner"), rank = 1), Team(Player("loser"), rank = 2)),
            )

        assertRating(result.player("winner").rating, 29.395576, 7.171141)
        assertRating(result.player("loser").rating, 20.604424, 7.171141)
    }

    @Test
    fun `symmetric draw leaves equal means equal`() {
        val calculator = TrueSkill2(TrueSkillConfig.withDraws(0.1))

        val result =
            calculator.updateRatings(
                Match(Team(Player("a"), rank = 1), Team(Player("b"), rank = 1)),
            )

        assertRating(result.player("a").rating, 25.0, 6.457236)
        assertRating(result.player("b").rating, 25.0, 6.457236)
    }

    @Test
    fun `input team order does not change ratings or output order`() {
        val calculator = TrueSkill2(TrueSkillConfig.withDraws(0.1))
        val loser = Team(Player("loser"), rank = 2)
        val winner = Team(Player("winner"), rank = 1)

        val result = calculator.updateRatings(Match(loser, winner))

        assertEquals(
            "loser",
            result.teams[0]
                .players
                .single()
                .id,
        )
        assertTrue(result.player("winner").rating.mean > 25.0)
        assertTrue(result.player("loser").rating.mean < 25.0)
    }

    @Test
    fun `multi-team ratings are invariant to input permutation`() {
        val calculator = TrueSkill2(TrueSkillConfig.withDraws(0.1))
        val teams =
            listOf(
                Team(Player("first"), rank = 1),
                Team(Player("second"), rank = 2),
                Team(Player("third"), rank = 3),
                Team(Player("fourth"), rank = 4),
            )

        val forward = calculator.updateRatings(Match(teams))
        val reversed = calculator.updateRatings(Match(teams.reversed()))

        teams.flatMap { it.players }.forEach { player ->
            assertRating(
                forward.player(player.id).rating,
                reversed.player(player.id).rating.mean,
                reversed.player(player.id).rating.standardDeviation,
            )
        }
        assertTrue(forward.player("first").rating.mean > forward.player("second").rating.mean)
        assertTrue(forward.player("second").rating.mean > forward.player("third").rating.mean)
    }

    @Test
    fun `partial play is a team-performance weight`() {
        val calculator = TrueSkill2(TrueSkillConfig.withDraws(0.1))
        val result =
            calculator.updateRatings(
                Match(
                    Team(Player("full"), Player("partial", partialPlayPercentage = 0.25), rank = 1),
                    Team(Player("opponent-a"), Player("opponent-b"), rank = 2),
                ),
            )

        val fullChange = result.player("full").rating.mean - 25.0
        val partialChange = result.player("partial").rating.mean - 25.0
        assertTrue(fullChange > partialChange * 3.5)
    }

    @Test
    fun `squad offset changes both prediction and credit assignment`() {
        val config = TrueSkillConfig().copy(squadOffsets = mapOf(2 to 4.0))
        val calculator = TrueSkill2(config)
        val squad = Team(Player("a", squadId = "friends"), Player("b", squadId = "friends"), rank = 1)
        val solos = Team(Player("c"), Player("d"), rank = 2)

        assertTrue(calculator.calculateWinProbability(squad, solos) > 0.5)
        val withOffset = calculator.updateRatings(Match(squad, solos))
        val withoutOffset = TrueSkill2().updateRatings(Match(squad, solos))
        assertTrue(withOffset.player("a").rating.mean < withoutOffset.player("a").rating.mean)
    }

    @Test
    fun `experience drift applies after outcome inference`() {
        val config = TrueSkillConfig().copy(experienceOffsets = listOf(0.5, 0.25))
        val calculator = TrueSkill2(config)
        val result =
            calculator.updateRatings(
                Match(
                    Team(Player("winner", experience = 1), rank = 1),
                    Team(Player("loser", experience = 1), rank = 2),
                ),
            )
        val baseline = TrueSkill2().updateRatings(Match(Team(Player("winner"), rank = 1), Team(Player("loser"), rank = 2)))

        assertEquals(baseline.player("winner").rating.mean + 0.25, result.player("winner").rating.mean, 1e-5)
    }

    @Test
    fun `individual statistics split credit between teammates`() {
        val config =
            TrueSkillConfig().copy(
                statisticModels = mapOf("kills" to StatisticModel(1.0, -0.25, 4.0)),
            )
        val calculator = TrueSkill2(config)
        val result =
            calculator.updateRatings(
                Match(
                    Team(
                        Player("carry", statistics = mapOf("kills" to 12.0)),
                        Player("support", statistics = mapOf("kills" to 1.0)),
                        rank = 1,
                    ),
                    Team(
                        Player("enemy-a", statistics = mapOf("kills" to 3.0)),
                        Player("enemy-b", statistics = mapOf("kills" to 3.0)),
                        rank = 2,
                    ),
                ),
            )

        assertTrue(result.player("carry").rating.mean > result.player("support").rating.mean)
    }

    @Test
    fun `quit observation penalizes quitter independently of team outcome`() {
        val config = TrueSkillConfig().copy(quitModel = QuitModel(underperformanceMean = -1.0, variance = 4.0))
        val calculator = TrueSkill2(config)
        val result =
            calculator.updateRatings(
                Match(
                    Team(Player("quitter", quit = true), Player("teammate"), rank = 1),
                    Team(Player("enemy-a"), Player("enemy-b"), rank = 2),
                ),
            )

        assertTrue(result.player("quitter").rating.mean < result.player("teammate").rating.mean)
    }

    @Test
    fun `correlated modes borrow only uncertain base skill`() {
        val config =
            TrueSkillConfig().copy(
                modeCorrelation = ModeCorrelationConfig(baseWeight = 1.0, initialBaseStdDev = 4.0),
            )
        val calculator = TrueSkill2(config)
        val update =
            calculator.updateRatings(
                Match(
                    teams = listOf(Team(Player("winner"), rank = 1), Team(Player("loser"), rank = 2)),
                    mode = "duels",
                ),
                TrueSkill2State(),
            )

        assertTrue(calculator.rating("winner", "teams", update.state).mean > 25.0)
        assertTrue(calculator.rating("loser", "teams", update.state).mean < 25.0)
    }

    @Test
    fun `balanced match quality includes rating uncertainty`() {
        val quality =
            TrueSkill2().calculateMatchQuality(
                Team(Player("a", Rating(25.0, 5.0))),
                Team(Player("b", Rating(25.0, 5.0))),
            )

        assertTrue(quality in 0.6..0.7)
    }

    private fun Match.player(id: String): Player = teams.flatMap { it.players }.single { it.id == id }

    private fun assertRating(
        actual: Rating,
        expectedMean: Double,
        expectedStdDev: Double,
    ) {
        assertTrue(abs(actual.mean - expectedMean) < 1e-4, "mean: expected $expectedMean, got ${actual.mean}")
        assertTrue(
            abs(actual.standardDeviation - expectedStdDev) < 1e-4,
            "standard deviation: expected $expectedStdDev, got ${actual.standardDeviation}",
        )
    }
}
