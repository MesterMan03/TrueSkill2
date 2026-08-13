package info.mester.trueskill

import java.lang.reflect.Modifier
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.Path
import kotlin.io.path.extension
import kotlin.io.path.readText
import kotlin.io.path.walk
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArchitectureTest {
    @Test
    fun `public API never exposes internal implementation types`() {
        val publicTypes =
            listOf(
                Match::class.java,
                Player::class.java,
                Rating::class.java,
                Team::class.java,
                TrueSkill2::class.java,
                TrueSkillConfig::class.java,
                TrueSkill2State::class.java,
                RatingUpdate::class.java,
            )

        publicTypes.forEach { type ->
            type.declaredMethods.filter { Modifier.isPublic(it.modifiers) }.forEach { method ->
                val exposed = listOf(method.returnType) + method.parameterTypes
                assertFalse(
                    exposed.any { it.name.contains(".internal.") },
                    "${type.simpleName}.${method.name} exposes an internal type",
                )
            }
        }
    }

    @OptIn(ExperimentalPathApi::class)
    @Test
    fun `factor graph remains independent of game domain`() {
        val root = Path("src/main/kotlin/info/mester/trueskill/internal/factorgraph")
        val forbidden =
            listOf(
                "info.mester.trueskill.Match",
                "info.mester.trueskill.Player",
                "info.mester.trueskill.Team",
                "info.mester.trueskill.TrueSkillConfig",
            )

        root.walk().filter { it.extension == "kt" }.forEach { source ->
            val text = source.readText()
            forbidden.forEach { dependency ->
                assertFalse(text.contains(dependency), "$source must not depend on $dependency")
            }
        }
    }

    @Test
    fun `public domain model is immutable`() {
        listOf(Match::class.java, Player::class.java, Rating::class.java, Team::class.java).forEach { type ->
            assertTrue(
                type.declaredFields.filterNot { it.isSynthetic }.all { Modifier.isFinal(it.modifiers) },
                "${type.simpleName} must only contain final fields",
            )
        }
    }
}
