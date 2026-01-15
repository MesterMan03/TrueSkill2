# TrueSkill2
A Kotlin implementation of Microsoft's TrueSkill2 skill rating system.

## Overview

TrueSkill2 is a Bayesian skill rating system designed for multiplayer games. This implementation provides:

- **TrueSkill2**: The core algorithm for rating players based on match outcomes
- **TrueSkill Through Time (TTT)**: Extension that accounts for temporal dynamics and skill changes over time
- **Pure Kotlin**: Uses native Kotlin standard library without external dependencies
- **Clear API**: Opinionated public API with well-defined internal components

## Features

- ✅ 1v1 matches
- ✅ Team-based matches
- ✅ Free-for-all matches (multiple teams)
- ✅ Draw/tie handling
- ✅ Partial play percentage (quit penalty)
- ✅ Skill uncertainty tracking
- ✅ Temporal dynamics (TrueSkill Through Time)
- ✅ Match quality calculation
- ✅ Win probability prediction

## Installation

Add this library to your project (adjust based on your build system):

### Gradle (Kotlin DSL)
```kotlin
dependencies {
    implementation("info.mester:trueskill2:1.0.0")
}
```

### Gradle (Groovy)
```groovy
dependencies {
    implementation 'info.mester:trueskill2:1.0.0'
}
```

## Usage

### Basic 1v1 Match

```kotlin
import info.mester.trueskill.*

// Create players with default ratings
val alice = Player("Alice")
val bob = Player("Bob")

// Create calculator
val trueskill = TrueSkill2()

// Create match (Alice wins, Bob loses)
val team1 = Team(alice, rank = 1)
val team2 = Team(bob, rank = 2)
val match = Match(team1, team2)

// Update ratings
val result = trueskill.updateRatings(match)

// Get updated players
val updatedAlice = result.teams[0].players[0]
val updatedBob = result.teams[1].players[0]

println("Alice: ${updatedAlice.rating}") // Rating increased
println("Bob: ${updatedBob.rating}")     // Rating decreased
```

### Team-Based Match

```kotlin
val team1 = Team(
    Player("Alice"),
    Player("Bob"),
    rank = 1 // Winners
)

val team2 = Team(
    Player("Charlie"),
    Player("Dave"),
    rank = 2 // Losers
)

val match = Match(team1, team2)
val result = trueskill.updateRatings(match)
```

### Free-for-All (Multiple Teams)

```kotlin
val teams = listOf(
    Team(Player("Alice"), rank = 1),  // 1st place
    Team(Player("Bob"), rank = 2),    // 2nd place
    Team(Player("Charlie"), rank = 3), // 3rd place
    Team(Player("Dave"), rank = 4)    // 4th place
)

val match = Match(teams)
val result = trueskill.updateRatings(match)
```

### Draw/Tie Handling

```kotlin
val team1 = Team(Player("Alice"), rank = 1)
val team2 = Team(Player("Bob"), rank = 1) // Same rank = draw

val match = Match(team1, team2)
val result = trueskill.updateRatings(match)
```

### Win Probability

```kotlin
val alice = Player("Alice", Rating(30.0, 5.0))
val bob = Player("Bob", Rating(20.0, 5.0))

val winProbability = trueskill.calculateWinProbability(
    Team(alice),
    Team(bob)
)

println("Alice win probability: ${(winProbability * 100).toInt()}%")
```

### Match Quality

```kotlin
val quality = trueskill.calculateMatchQuality(team1, team2)
println("Match quality: ${(quality * 100).toInt()}%") // 0-100%, higher is more balanced
```

### Quit Penalty (Partial Play)

```kotlin
val fullPlayer = Player("FullGame", partialPlayPercentage = 1.0)
val quitter = Player("Quitter", partialPlayPercentage = 0.3) // Only played 30%

val team1 = Team(fullPlayer, rank = 1)
val team2 = Team(quitter, rank = 2)

// Quitter's rating will change less due to partial participation
val result = trueskill.updateRatings(Match(team1, team2))
```

### TrueSkill Through Time

TrueSkill Through Time accounts for time between matches, increasing uncertainty as time passes.

```kotlin
import info.mester.trueskill.*

val ttt = TrueSkillThroughTime(timeUnit = TrueSkillThroughTime.TimeUnit.DAYS)

// Create match history with timestamps
val matches = listOf(
    Match(
        Team(Player("Alice"), rank = 1),
        Team(Player("Bob"), rank = 2),
        timestamp = 1000L // Day 1
    ),
    Match(
        Team(Player("Alice"), rank = 1),
        Team(Player("Bob"), rank = 2),
        timestamp = 2000L // Day 2
    ),
    Match(
        Team(Player("Alice"), rank = 2),
        Team(Player("Bob"), rank = 1),
        timestamp = 3000L // Day 3
    )
)

// Process entire history (automatically applies dynamics)
val results = ttt.processMatchHistory(matches)

// Get player's rating history
val aliceHistory = ttt.getPlayerHistory("Alice", results)
aliceHistory.forEach { timestamped ->
    println("Time ${timestamped.timestamp}: ${timestamped.rating}")
}

// Predict future rating (uncertainty grows over time)
val player = Player("Alice", Rating(30.0, 5.0))
val futureRating = ttt.predictFutureRating(player, currentTime = 1000L, futureTime = 31000L)
println("Future rating: $futureRating") // Higher uncertainty
```

### Custom Configuration

```kotlin
val config = TrueSkillConfig(
    initialMean = 25.0,           // Starting skill mean
    initialStdDev = 25.0 / 3.0,   // Starting uncertainty
    beta = 25.0 / 6.0,            // Performance variation
    tau = 25.0 / 300.0,           // Dynamics factor
    drawProbability = 0.1         // 10% draw probability
)

val trueskill = TrueSkill2(config)
```

### Presets

```kotlin
// Default configuration
val defaultConfig = TrueSkillConfig.default()

// Configuration with draws
val withDraws = TrueSkillConfig.withDraws(drawProbability = 0.15)

// Gears of War 4 configuration (from Microsoft's paper)
val gow4Config = TrueSkillConfig.gearsOfWar4()
```

## API Design

### Public API (`info.mester.trueskill` package)

- `Rating`: Represents a player's skill as a Gaussian distribution
- `Player`: Represents a player with ID and rating
- `Team`: Represents a team of players with rank
- `Match`: Represents a match with multiple teams
- `TrueSkill2`: Main calculator for rating updates
- `TrueSkillConfig`: Configuration parameters
- `TrueSkillThroughTime`: Extension for temporal dynamics

### Internal API (`info.mester.trueskill.internal` package)

- `GaussianDistribution`: Statistical utilities (not part of public API)

## Algorithm Details

This implementation is based on Microsoft's TrueSkill2 paper:
- **Paper**: "TrueSkill2: An Improved Bayesian Skill Rating System"
- **Authors**: Tom Minka, et al.
- **Source**: https://www.microsoft.com/en-us/research/publication/trueskill2/

### Key Concepts

- **Skill Rating**: Represented as μ (mean) and σ (standard deviation)
- **Conservative Rating**: μ - 3σ (99.7% confidence interval)
- **Dynamics**: Uncertainty grows over time to account for skill changes
- **Partial Play**: Players who quit early receive reduced rating updates

## Testing

Run tests with:

```bash
./gradlew test
```

## Building

Build the project with:

```bash
./gradlew build
```

## License

[Specify your license here]

## Contributing

[Specify contribution guidelines if applicable]

