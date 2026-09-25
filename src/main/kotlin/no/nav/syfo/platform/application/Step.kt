package no.nav.syfo.platform.application

/**
 * Outcome of one private use-case step: continue with a value, or stop with the use case's result.
 *
 * Keep it inside use cases. Public use-case contracts and ports return their own sealed types.
 */
sealed interface Step<out T, out R> {
    data class Continue<out T>(val value: T) : Step<T, Nothing>

    data class Stop<out R>(val result: R) : Step<Nothing, R>

    companion object {
        val Proceed: Step<Unit, Nothing> = Continue(Unit)
    }
}

inline fun <T, R> Step<T, R>.orStop(stop: (R) -> Nothing): T = when (this) {
    is Step.Continue -> value
    is Step.Stop -> stop(result)
}
