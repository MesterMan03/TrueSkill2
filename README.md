# TrueSkill 2 for Kotlin

A JVM implementation of Microsoft's TrueSkill 2 Bayesian skill-rating model, licensed under Apache-2.0.

The library supports:

- correct TrueSkill factor-graph inference for head-to-head, teams, free-for-all, ties, and partial play;
- TrueSkill 2 squad offsets, experience drift, individual-statistic evidence, and quit evidence;
- persistent base plus mode-offset ratings for correlated game modes;
- online inference and forward/backward TrueSkill Through Time history smoothing;
- match quality, win probability, and draw probability;
- immutable public models and a dependency-free runtime.

## Installation

After the first stable release is published to Maven Central:

```kotlin
dependencies {
    implementation("info.mester:trueskill2:0.1.0")
}
```

Until then, publish a local snapshot:

```shell
./gradlew :lib:publishToMavenLocal
```

```kotlin
repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation("info.mester:trueskill2:0.1.0-SNAPSHOT")
}
```

The generated binary is `trueskill2-<version>.jar`, accompanied by source and Dokka documentation JARs.

## Classic match outcome

```kotlin
import info.mester.trueskill.Match
import info.mester.trueskill.Player
import info.mester.trueskill.Team
import info.mester.trueskill.TrueSkill2
import info.mester.trueskill.TrueSkillConfig

val ratings = TrueSkill2(TrueSkillConfig.withDraws(drawProbability = 0.1))

val result = ratings.updateRatings(
    Match(
        Team(Player("alice"), rank = 1),
        Team(Player("bob"), rank = 2),
    ),
)

val alice = result.teams.flatMap { it.players }.single { it.id == "alice" }
println(alice.rating)
```

Ranks are placements: lower is better, and equal ranks represent a draw. Player order and team input order do not affect inference or output ordering.

## TrueSkill 2 evidence

```kotlin
import info.mester.trueskill.QuitModel
import info.mester.trueskill.StatisticModel

val config = TrueSkillConfig().copy(
    squadOffsets = mapOf(
        1 to 0.0,
        2 to 0.35,
        3 to 0.8,
        4 to 1.4,
    ),
    experienceOffsets = List(200) { games ->
        0.3 / (games + 1.0)
    },
    statisticModels = mapOf(
        "kills" to StatisticModel(
            playerPerformanceWeight = 0.8,
            opponentPerformanceWeight = -0.3,
            variancePerTimeUnit = 4.0,
        ),
        "deaths" to StatisticModel(
            playerPerformanceWeight = -0.6,
            opponentPerformanceWeight = 0.4,
            variancePerTimeUnit = 4.0,
        ),
    ),
    quitModel = QuitModel(
        underperformanceMean = -0.25,
        variance = 2.0,
    ),
)

val calculator = TrueSkill2(config)
val player = Player(
    id = "alice",
    partialPlayPercentage = 0.75,
    squadId = "party-42",
    statistics = mapOf("kills" to 12.0, "deaths" to 4.0),
    quit = false,
    experience = 17,
)
```

Those numeric parameters are illustrative, not recommended Minecraft tuning. Fit them against held-out match data and evaluate predictive calibration before enabling them in production.

## Correlated game modes

```kotlin
import info.mester.trueskill.ModeCorrelationConfig
import info.mester.trueskill.TrueSkill2State

val calculator = TrueSkill2(
    TrueSkillConfig().copy(
        modeCorrelation = ModeCorrelationConfig(
            baseWeight = 1.0,
            initialBaseStdDev = 4.0,
        ),
    ),
)

var state = TrueSkill2State()
val update = calculator.updateRatings(
    Match(
        teams = listOf(
            Team(Player("alice"), rank = 1),
            Team(Player("bob"), rank = 2),
        ),
        mode = "bedwars-solo",
    ),
    state,
)
state = update.state

val aliceTeamsRating = calculator.rating("alice", "bedwars-teams", state)
```

Store `TrueSkill2State` as hidden matchmaking state. A public rank or leaderboard score should be projected from the hidden rating separately and must not feed back into matchmaking inference.

## TrueSkill Through Time

```kotlin
import info.mester.trueskill.TrueSkillThroughTime

val historyModel = TrueSkillThroughTime()
val smoothedMatches = historyModel.processMatchHistory(matchesInTimestampOrder)
```

This is batch smoothing, not a chronological replay: evidence is propagated repeatedly forward and backward, so a later result can revise an earlier estimate.

## Verification and documentation

```shell
./gradlew check
./gradlew :lib:dokkaGeneratePublicationHtml
./gradlew :lib:sourcesJar :lib:dokkaJavadocJar
```

Generated HTML documentation is written to `lib/build/dokka/html` and deployed to GitHub Pages by the documentation workflow.

See [PUBLISHING.md](PUBLISHING.md) for the one-time Maven Central setup and release process.

## References

- [TrueSkill 2: An improved Bayesian skill rating system](https://www.microsoft.com/en-us/research/publication/trueskill-2-improved-bayesian-skill-rating-system/)
- [TrueSkill: A Bayesian Skill Rating System](https://www.microsoft.com/en-us/research/publication/trueskilltm-a-bayesian-skill-rating-system/)
- [TrueSkill Through Time](https://www.microsoft.com/en-us/research/publication/trueskill-through-time-revisiting-the-history-of-chess/)

## License

Copyright 2026 MesterMan03. Distributed under the [Apache License 2.0](LICENSE).
