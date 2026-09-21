package no.nav.syfo.narmestelederbehov.domain

sealed interface ManagerLastNameMatch {
    val hasParallelNames: Boolean

    data class Exact(
        override val hasParallelNames: Boolean,
    ) : ManagerLastNameMatch

    data class OrthographicVariant(
        override val hasParallelNames: Boolean,
    ) : ManagerLastNameMatch

    data class Fuzzy(
        val score: Double,
        override val hasParallelNames: Boolean,
    ) : ManagerLastNameMatch

    data class NoMatch(
        val bestFuzzyScore: Double?,
        override val hasParallelNames: Boolean,
    ) : ManagerLastNameMatch
}

fun ManagerLastNameMatch.isAccepted(): Boolean = this !is ManagerLastNameMatch.NoMatch
