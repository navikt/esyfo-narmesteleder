package no.nav.syfo.narmestelederbehov.infrastructure

import no.nav.syfo.narmesteleder.service.validators.NameMatchType
import no.nav.syfo.narmesteleder.service.validators.NameValidator
import no.nav.syfo.narmestelederbehov.application.ManagerNameValidationMetrics
import no.nav.syfo.narmestelederbehov.domain.ManagerLastNameMatch

class LegacyManagerNameValidationMetrics : ManagerNameValidationMetrics {
    override fun record(match: ManagerLastNameMatch) {
        val type = when (match) {
            is ManagerLastNameMatch.Exact -> NameMatchType.EXACT
            is ManagerLastNameMatch.OrthographicVariant -> NameMatchType.ORTHOGRAPHIC_VARIANT
            is ManagerLastNameMatch.Fuzzy -> NameMatchType.FUZZY
            is ManagerLastNameMatch.NoMatch -> NameMatchType.NONE
        }
        val score = when (match) {
            is ManagerLastNameMatch.Fuzzy -> match.score
            is ManagerLastNameMatch.NoMatch -> match.bestFuzzyScore
            else -> null
        }
        NameValidator.recordManagerLastNameMatch(type, match.hasParallelNames, score)
    }
}
