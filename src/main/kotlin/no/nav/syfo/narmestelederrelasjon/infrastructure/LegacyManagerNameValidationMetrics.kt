package no.nav.syfo.narmestelederrelasjon.infrastructure

import no.nav.syfo.narmesteleder.service.validators.NameMatchType
import no.nav.syfo.narmesteleder.service.validators.NameValidator
import no.nav.syfo.narmestelederrelasjon.application.ManagerNameValidationMetrics
import no.nav.syfo.narmestelederrelasjon.domain.LastNameMatch

class LegacyManagerNameValidationMetrics : ManagerNameValidationMetrics {
    override fun record(match: LastNameMatch) {
        val type = when (match) {
            is LastNameMatch.Exact -> NameMatchType.EXACT
            is LastNameMatch.OrthographicVariant -> NameMatchType.ORTHOGRAPHIC_VARIANT
            is LastNameMatch.Fuzzy -> NameMatchType.FUZZY
            is LastNameMatch.NoMatch -> NameMatchType.NONE
        }
        val score = when (match) {
            is LastNameMatch.Fuzzy -> match.score
            is LastNameMatch.NoMatch -> match.bestFuzzyScore
            else -> null
        }
        NameValidator.recordManagerLastNameMatch(type, match.hasParallelNames, score)
    }
}
