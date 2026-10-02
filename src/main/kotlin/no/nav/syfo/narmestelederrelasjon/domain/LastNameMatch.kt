package no.nav.syfo.narmestelederrelasjon.domain

sealed interface LastNameMatch {
    val hasParallelNames: Boolean

    data class Exact(
        override val hasParallelNames: Boolean,
    ) : LastNameMatch

    data class OrthographicVariant(
        override val hasParallelNames: Boolean,
    ) : LastNameMatch

    data class Fuzzy(
        val score: Double,
        override val hasParallelNames: Boolean,
    ) : LastNameMatch

    data class NoMatch(
        val bestFuzzyScore: Double?,
        override val hasParallelNames: Boolean,
    ) : LastNameMatch
}

fun LastNameMatch.isAccepted(): Boolean = this !is LastNameMatch.NoMatch
