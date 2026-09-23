package no.nav.syfo.narmestelederbehov.application

import no.nav.syfo.narmestelederbehov.domain.ManagerLastNameMatch

fun interface ManagerNameValidationMetrics {
    fun record(match: ManagerLastNameMatch)
}
