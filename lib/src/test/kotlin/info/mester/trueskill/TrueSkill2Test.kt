package info.mester.trueskill

import kotlin.test.*

class TrueSkill2Test {
    
    private val calculator = TrueSkill2()
    
    @Test
    fun `test basic 1v1 match winner rating increases`() {
        val player1 = Player("Alice")
        val player2 = Player("Bob")
        
        val initialRating1 = player1.rating.mean
        val initialRating2 = player2.rating.mean
        
        val team1 = Team(player1, rank = 1) // Winner
        val team2 = Team(player2, rank = 2) // Loser
        val match = Match(team1, team2)
        
        val result = calculator.updateRatings(match)
        
        val newPlayer1 = result.teams[0].players[0]
        val newPlayer2 = result.teams[1].players[0]
        
        // Winner's rating should increase
        assertTrue(newPlayer1.rating.mean > initialRating1, "Winner rating should increase")
        // Loser's rating should decrease
        assertTrue(newPlayer2.rating.mean < initialRating2, "Loser rating should decrease")
        // Uncertainty should decrease for both
        assertTrue(newPlayer1.rating.standardDeviation < player1.rating.standardDeviation)
        assertTrue(newPlayer2.rating.standardDeviation < player2.rating.standardDeviation)
    }
    
    @Test
    fun `test draw scenario`() {
        val player1 = Player("Alice")
        val player2 = Player("Bob")
        
        val initialMean1 = player1.rating.mean
        val initialMean2 = player2.rating.mean
        
        val team1 = Team(player1, rank = 1)
        val team2 = Team(player2, rank = 1) // Same rank = draw
        val match = Match(team1, team2)
        
        val result = calculator.updateRatings(match)
        
        // Ratings should change minimally in a draw
        val delta1 = kotlin.math.abs(result.teams[0].players[0].rating.mean - initialMean1)
        val delta2 = kotlin.math.abs(result.teams[1].players[0].rating.mean - initialMean2)
        
        assertTrue(delta1 < 5.0, "Rating change should be small for draw")
        assertTrue(delta2 < 5.0, "Rating change should be small for draw")
    }
    
    @Test
    fun `test team match`() {
        val alice = Player("Alice")
        val bob = Player("Bob")
        val charlie = Player("Charlie")
        val dave = Player("Dave")
        
        val team1 = Team(alice, bob, rank = 1) // Winners
        val team2 = Team(charlie, dave, rank = 2) // Losers
        val match = Match(team1, team2)
        
        val result = calculator.updateRatings(match)
        
        // All winners should have increased ratings
        val winner1 = result.teams[0].players[0]
        val winner2 = result.teams[0].players[1]
        assertTrue(winner1.rating.mean > alice.rating.mean)
        assertTrue(winner2.rating.mean > bob.rating.mean)
        
        // All losers should have decreased ratings
        val loser1 = result.teams[1].players[0]
        val loser2 = result.teams[1].players[1]
        assertTrue(loser1.rating.mean < charlie.rating.mean)
        assertTrue(loser2.rating.mean < dave.rating.mean)
    }
    
    @Test
    fun `test win probability calculation`() {
        val strongPlayer = Player("Strong", Rating(35.0, 5.0))
        val weakPlayer = Player("Weak", Rating(15.0, 5.0))
        
        val team1 = Team(strongPlayer)
        val team2 = Team(weakPlayer)
        
        val winProb = calculator.calculateWinProbability(team1, team2)
        
        // Strong player should have high probability of winning
        assertTrue(winProb > 0.8, "Strong player should have >80% win probability")
        assertTrue(winProb < 1.0, "Win probability should be < 100%")
    }
    
    @Test
    fun `test match quality for balanced match`() {
        val player1 = Player("Alice", Rating(25.0, 5.0))
        val player2 = Player("Bob", Rating(25.0, 5.0))
        
        val team1 = Team(player1)
        val team2 = Team(player2)
        
        val quality = calculator.calculateMatchQuality(team1, team2)
        
        // Evenly matched players should have high quality
        assertTrue(quality > 0.8, "Evenly matched should have high quality")
    }
    
    @Test
    fun `test match quality for unbalanced match`() {
        val player1 = Player("Strong", Rating(40.0, 5.0))
        val player2 = Player("Weak", Rating(10.0, 5.0))
        
        val team1 = Team(player1)
        val team2 = Team(player2)
        
        val quality = calculator.calculateMatchQuality(team1, team2)
        
        // Unbalanced match should have low quality
        assertTrue(quality < 0.5, "Unbalanced match should have low quality")
    }
    
    @Test
    fun `test partial play percentage reduces rating change`() {
        val fullPlayer = Player("Full", partialPlayPercentage = 1.0)
        val partialPlayer = Player("Partial", partialPlayPercentage = 0.5)
        
        val initialFull = fullPlayer.rating.mean
        val initialPartial = partialPlayer.rating.mean
        
        val team1 = Team(fullPlayer, rank = 1)
        val team2 = Team(partialPlayer, rank = 2)
        val match = Match(team1, team2)
        
        val result = calculator.updateRatings(match)
        
        val fullDelta = kotlin.math.abs(result.teams[0].players[0].rating.mean - initialFull)
        val partialDelta = kotlin.math.abs(result.teams[1].players[0].rating.mean - initialPartial)
        
        // Full participation should have larger rating change
        assertTrue(fullDelta > partialDelta, "Full participation should have larger rating change")
    }
    
    @Test
    fun `test conservative rating calculation`() {
        val rating = Rating(25.0, 8.333)
        val conservative = rating.conservativeRating
        
        assertEquals(25.0 - 3.0 * 8.333, conservative, 0.001)
    }
    
    @Test
    fun `test rating with dynamics`() {
        val rating = Rating(25.0, 5.0)
        val updated = rating.applyDynamics(10.0, 0.5)
        
        assertEquals(25.0, updated.mean, "Mean should not change with dynamics")
        assertTrue(updated.standardDeviation > rating.standardDeviation, 
            "Standard deviation should increase with dynamics")
    }
    
    @Test
    fun `test multi-team free-for-all`() {
        val player1 = Player("1st")
        val player2 = Player("2nd")
        val player3 = Player("3rd")
        val player4 = Player("4th")
        
        val teams = listOf(
            Team(player1, rank = 1),
            Team(player2, rank = 2),
            Team(player3, rank = 3),
            Team(player4, rank = 4)
        )
        val match = Match(teams)
        
        val result = calculator.updateRatings(match)
        
        // First place should have highest rating gain
        val first = result.teams.find { it.rank == 1 }!!.players[0]
        val last = result.teams.find { it.rank == 4 }!!.players[0]
        
        assertTrue(first.rating.mean > player1.rating.mean)
        assertTrue(last.rating.mean < player4.rating.mean)
    }
}
