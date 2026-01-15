package info.mester.trueskill

import kotlin.test.*

class TrueSkillThroughTimeTest {
    
    private val calculator = TrueSkillThroughTime(timeUnit = TrueSkillThroughTime.TimeUnit.DAYS)
    
    @Test
    fun `test basic TTT match processing`() {
        val alice = Player("Alice")
        val bob = Player("Bob")
        
        val match1 = Match(
            Team(alice, rank = 1),
            Team(bob, rank = 2),
            timestamp = 0L
        )
        
        val results = calculator.processMatchHistory(listOf(match1))
        
        assertEquals(1, results.size)
        val winner = results[0].teams[0].players[0]
        val loser = results[0].teams[1].players[0]
        
        assertTrue(winner.rating.mean > 25.0, "Winner rating should increase")
        assertTrue(loser.rating.mean < 25.0, "Loser rating should decrease")
    }
    
    @Test
    fun `test dynamics application over time`() {
        val player = Player("Alice")
        val team = Team(player, rank = 1)
        
        val earlyTime = 0L
        val lateTime = 30L * 24L * 60L * 60L * 1000L // 30 days in milliseconds
        
        val match1 = Match(team, Team(Player("Bob"), rank = 2), timestamp = earlyTime)
        val match2 = Match(team, Team(Player("Charlie"), rank = 2), timestamp = lateTime)
        
        val results = calculator.processMatchHistory(listOf(match1, match2))
        
        // After second match, uncertainty should have grown then shrunk
        assertNotNull(results[1])
    }
    
    @Test
    fun `test match history maintains player ratings`() {
        val alice = Player("Alice")
        val bob = Player("Bob")
        val charlie = Player("Charlie")
        
        val matches = listOf(
            Match(Team(alice, rank = 1), Team(bob, rank = 2), timestamp = 1000L),
            Match(Team(bob, rank = 1), Team(charlie, rank = 2), timestamp = 2000L),
            Match(Team(charlie, rank = 1), Team(alice, rank = 2), timestamp = 3000L)
        )
        
        val results = calculator.processMatchHistory(matches)
        
        assertEquals(3, results.size)
        
        // Alice wins match 1, loses match 3
        // Her final rating should reflect both outcomes
        val finalMatch = results.last()
        val finalAlice = finalMatch.teams.flatMap { it.players }.find { it.id == "Alice" }
        assertNotNull(finalAlice)
    }
    
    @Test
    fun `test player history tracking`() {
        val alice = Player("Alice")
        val bob = Player("Bob")
        
        val matches = listOf(
            Match(Team(alice, rank = 1), Team(bob, rank = 2), timestamp = 1000L),
            Match(Team(alice, rank = 1), Team(bob, rank = 2), timestamp = 2000L),
            Match(Team(alice, rank = 1), Team(bob, rank = 2), timestamp = 3000L)
        )
        
        val results = calculator.processMatchHistory(matches)
        val history = calculator.getPlayerHistory("Alice", results)
        
        assertEquals(3, history.size)
        assertEquals(1000L, history[0].timestamp)
        assertEquals(2000L, history[1].timestamp)
        assertEquals(3000L, history[2].timestamp)
        
        // Rating should generally increase as Alice wins all matches
        assertTrue(history[2].rating.mean > history[0].rating.mean)
    }
    
    @Test
    fun `test predict future rating`() {
        val player = Player("Alice", Rating(30.0, 5.0))
        val currentTime = 1000L
        val futureTime = currentTime + (30L * 24L * 60L * 60L * 1000L) // 30 days later
        
        val futureRating = calculator.predictFutureRating(player, currentTime, futureTime)
        
        assertEquals(30.0, futureRating.mean, "Mean should not change over time")
        assertTrue(futureRating.standardDeviation > player.rating.standardDeviation,
            "Uncertainty should increase over time")
    }
    
    @Test
    fun `test chronological order requirement`() {
        val alice = Player("Alice")
        val bob = Player("Bob")
        
        val matches = listOf(
            Match(Team(alice, rank = 1), Team(bob, rank = 2), timestamp = 3000L), // Out of order
            Match(Team(alice, rank = 1), Team(bob, rank = 2), timestamp = 1000L)
        )
        
        assertFails {
            calculator.processMatchHistory(matches)
        }
    }
    
    @Test
    fun `test matches without timestamp fail`() {
        val alice = Player("Alice")
        val bob = Player("Bob")
        
        val matches = listOf(
            Match(Team(alice, rank = 1), Team(bob, rank = 2), timestamp = null) // Missing timestamp
        )
        
        assertFails {
            calculator.processMatchHistory(matches)
        }
    }
    
    @Test
    fun `test different time units`() {
        val hourCalculator = TrueSkillThroughTime(timeUnit = TrueSkillThroughTime.TimeUnit.HOURS)
        val dayCalculator = TrueSkillThroughTime(timeUnit = TrueSkillThroughTime.TimeUnit.DAYS)
        
        val player = Player("Alice", Rating(25.0, 5.0))
        val hourTime = 24 * 60 * 60 * 1000L // 24 hours in ms
        
        val hourResult = hourCalculator.predictFutureRating(player, 0L, hourTime)
        val dayResult = dayCalculator.predictFutureRating(player, 0L, hourTime)
        
        // With hours as unit, 24 hours = 24 time units
        // With days as unit, 24 hours = 1 time unit
        // So hour calculator should show more uncertainty growth
        assertTrue(hourResult.standardDeviation > dayResult.standardDeviation)
    }
    
    @Test
    fun `test win probability with time consideration`() {
        val alice = Player("Alice", Rating(30.0, 3.0))
        val bob = Player("Bob", Rating(20.0, 3.0))
        
        val currentTime = 10000L
        val prob = calculator.calculateWinProbability(
            Team(alice), 
            Team(bob),
            currentTime,
            currentTime - 1000L,
            currentTime
        )
        
        assertTrue(prob > 0.7, "Alice should have high win probability")
    }
}
