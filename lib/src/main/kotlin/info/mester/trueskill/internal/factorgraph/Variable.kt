package info.mester.trueskill.internal.factorgraph

internal class Variable(
    val name: String,
) {
    var value: Gaussian = Gaussian.UNIFORM
        private set

    private val messages = mutableMapOf<Factor, Gaussian>()

    fun cavity(factor: Factor): Gaussian = value / (messages[factor] ?: Gaussian.UNIFORM)

    fun updateMessage(
        factor: Factor,
        message: Gaussian,
    ): Double {
        val before = value
        value = cavity(factor) * message
        messages[factor] = message
        return Gaussian.delta(before, value)
    }
}

internal interface Factor {
    fun update(): Double
}
