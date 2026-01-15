package info.mester.trueskill

import info.mester.trueskill.internal.GaussianDistribution
import kotlin.math.sqrt

/**
 * Main calculator for TrueSkill2 rating system.
 * 
 * This class provides methods to:
 * - Calculate win probabilities between teams
 * - Update player ratings after matches
 * - Handle various match configurations (1v1, team vs team, free-for-all)
 * 
 * @property config Configuration parameters for the algorithm
 */
class TrueSkill2(val config: TrueSkillConfig = TrueSkillConfig.default()) {
    
    /**
     * Calculate the win probability for team1 against team2.
     * 
     * @param team1 First team
     * @param team2 Second team
     * @return Probability that team1 wins (0.0 to 1.0)
     */
    fun calculateWinProbability(team1: Team, team2: Team): Double {
        val teamRating1 = calculateTeamRating(team1)
        val teamRating2 = calculateTeamRating(team2)
        
        val deltaMean = teamRating1.mean - teamRating2.mean
        val sumVariance = teamRating1.standardDeviation * teamRating1.standardDeviation +
                         teamRating2.standardDeviation * teamRating2.standardDeviation
        val performanceStdDev = sqrt(sumVariance + 2.0 * config.beta * config.beta)
        
        return GaussianDistribution.standardNormalCdf(deltaMean / performanceStdDev)
    }
    
    /**
     * Calculate the draw probability between two teams.
     * 
     * @param team1 First team
     * @param team2 Second team
     * @return Probability of a draw (0.0 to 1.0)
     */
    fun calculateDrawProbability(team1: Team, team2: Team): Double {
        val teamRating1 = calculateTeamRating(team1)
        val teamRating2 = calculateTeamRating(team2)
        
        val deltaMean = teamRating1.mean - teamRating2.mean
        val sumVariance = teamRating1.standardDeviation * teamRating1.standardDeviation +
                         teamRating2.standardDeviation * teamRating2.standardDeviation
        val performanceStdDev = sqrt(sumVariance + 2.0 * config.beta * config.beta)
        
        val drawMargin = config.effectiveDrawMargin
        val high = GaussianDistribution.standardNormalCdf((drawMargin - deltaMean) / performanceStdDev)
        val low = GaussianDistribution.standardNormalCdf((-drawMargin - deltaMean) / performanceStdDev)
        
        return high - low
    }
    
    /**
     * Calculate match quality - a measure from 0 to 1 indicating how evenly matched the teams are.
     * Higher values indicate more balanced matches.
     * 
     * @param teams All teams in the match
     * @return Match quality (0.0 to 1.0)
     */
    fun calculateMatchQuality(vararg teams: Team): Double {
        require(teams.size >= 2) { "Need at least 2 teams" }
        
        if (teams.size == 2) {
            val team1Rating = calculateTeamRating(teams[0])
            val team2Rating = calculateTeamRating(teams[1])
            
            val deltaMean = team1Rating.mean - team2Rating.mean
            val sumVariance = team1Rating.standardDeviation * team1Rating.standardDeviation +
                             team2Rating.standardDeviation * team2Rating.standardDeviation +
                             2.0 * config.beta * config.beta
            
            val expValue = deltaMean * deltaMean / (2.0 * sumVariance)
            return kotlin.math.exp(-expValue)
        }
        
        // For multiple teams, use average pairwise quality
        var totalQuality = 0.0
        var pairCount = 0
        
        for (i in teams.indices) {
            for (j in (i + 1) until teams.size) {
                totalQuality += calculateMatchQuality(teams[i], teams[j])
                pairCount++
            }
        }
        
        return if (pairCount > 0) totalQuality / pairCount else 0.0
    }
    
    /**
     * Update player ratings after a match.
     * 
     * @param match The match result with teams and rankings
     * @return Updated match with new player ratings
     */
    fun updateRatings(match: Match): Match {
        // For 2-team matches, use efficient pairwise update
        if (match.isTwoTeamMatch) {
            return updateRatingsTwoTeam(match)
        }
        
        // For multi-team matches, use iterative approach
        return updateRatingsMultiTeam(match)
    }
    
    /**
     * Update ratings for a two-team match (optimized).
     */
    private fun updateRatingsTwoTeam(match: Match): Match {
        val teams = match.sortedTeams
        val team1 = teams[0]
        val team2 = teams[1]
        
        val isDraw = team1.rank == team2.rank
        
        val team1Rating = calculateTeamRating(team1)
        val team2Rating = calculateTeamRating(team2)
        
        // Calculate performance difference
        val deltaMean = team1Rating.mean - team2Rating.mean
        val sumVariance = team1Rating.standardDeviation * team1Rating.standardDeviation +
                         team2Rating.standardDeviation * team2Rating.standardDeviation +
                         2.0 * config.beta * config.beta
        val performanceStdDev = sqrt(sumVariance)
        
        // Calculate v and w functions
        val drawMargin = config.effectiveDrawMargin
        val v: Double
        val w: Double
        
        if (isDraw) {
            val alpha1 = (drawMargin - deltaMean) / performanceStdDev
            val alpha2 = (-drawMargin - deltaMean) / performanceStdDev
            v = (GaussianDistribution.vFunction(alpha1, 0.0) - 
                 GaussianDistribution.vFunction(alpha2, 0.0)) / performanceStdDev
            w = ((GaussianDistribution.wFunction(alpha1, 0.0) + 
                  GaussianDistribution.wFunction(alpha2, 0.0)) / (performanceStdDev * performanceStdDev))
        } else {
            val alpha = deltaMean / performanceStdDev
            v = GaussianDistribution.vFunction(alpha, 0.0) / performanceStdDev
            w = GaussianDistribution.wFunction(alpha, 0.0) / (performanceStdDev * performanceStdDev)
        }
        
        // Update each player
        val updatedTeam1 = updateTeamPlayers(team1, team1Rating, v, w, 1.0)
        val updatedTeam2 = updateTeamPlayers(team2, team2Rating, v, w, -1.0)
        
        return match.copy(teams = listOf(updatedTeam1, updatedTeam2))
    }
    
    /**
     * Update ratings for multi-team matches using ranking approach.
     */
    private fun updateRatingsMultiTeam(match: Match): Match {
        val sortedTeams = match.sortedTeams
        val updatedTeams = mutableListOf<Team>()
        
        // Process each team against all others
        for (i in sortedTeams.indices) {
            val currentTeam = sortedTeams[i]
            val currentRating = calculateTeamRating(currentTeam)
            
            var meanDelta = 0.0
            var varianceDelta = 0.0
            
            // Compare with each other team
            for (j in sortedTeams.indices) {
                if (i == j) continue
                
                val otherTeam = sortedTeams[j]
                val otherRating = calculateTeamRating(otherTeam)
                
                val comparison = when {
                    currentTeam.rank < otherTeam.rank -> 1  // Current team won
                    currentTeam.rank > otherTeam.rank -> -1 // Current team lost
                    else -> 0 // Draw
                }
                
                val deltaMean = currentRating.mean - otherRating.mean
                val sumVariance = currentRating.standardDeviation * currentRating.standardDeviation +
                                 otherRating.standardDeviation * otherRating.standardDeviation +
                                 2.0 * config.beta * config.beta
                val performanceStdDev = sqrt(sumVariance)
                
                val adjustedDelta = comparison * deltaMean
                val v = GaussianDistribution.vFunction(adjustedDelta, performanceStdDev)
                val w = GaussianDistribution.wFunction(adjustedDelta, performanceStdDev)
                
                meanDelta += comparison * v
                varianceDelta += w
            }
            
            // Average the deltas
            val teamCount = sortedTeams.size - 1
            meanDelta /= teamCount
            varianceDelta /= (teamCount * teamCount)
            
            val updatedTeam = updateTeamPlayers(currentTeam, currentRating, meanDelta, varianceDelta, 1.0)
            updatedTeams.add(updatedTeam)
        }
        
        return match.copy(teams = updatedTeams)
    }
    
    /**
     * Update all players in a team based on the team's performance.
     */
    private fun updateTeamPlayers(
        team: Team,
        teamRating: Rating,
        v: Double,
        w: Double,
        sign: Double
    ): Team {
        val updatedPlayers = team.players.map { player ->
            val playerRating = player.rating
            val variance = playerRating.standardDeviation * playerRating.standardDeviation
            
            // Calculate player's contribution to team variance
            val teamVariance = teamRating.standardDeviation * teamRating.standardDeviation
            
            // Apply update with partial play factor
            val meanChange = sign * variance * v * player.partialPlayPercentage
            val varianceChange = variance * variance * w * player.partialPlayPercentage * player.partialPlayPercentage
            
            val newMean = playerRating.mean + meanChange
            val newVariance = variance - varianceChange
            
            // Ensure variance doesn't become negative or too small
            val finalVariance = maxOf(newVariance, variance * 0.01)
            val newStdDev = sqrt(finalVariance)
            
            player.withRating(Rating(newMean, newStdDev))
        }
        
        return team.withPlayers(updatedPlayers)
    }
    
    /**
     * Calculate the aggregate rating for a team.
     * Team mean is sum of player means, team variance is sum of player variances.
     */
    private fun calculateTeamRating(team: Team): Rating {
        var sumMean = 0.0
        var sumVariance = 0.0
        
        for (player in team.players) {
            sumMean += player.rating.mean
            val variance = player.rating.standardDeviation * player.rating.standardDeviation
            sumVariance += variance
        }
        
        return Rating(sumMean, sqrt(sumVariance))
    }
}
