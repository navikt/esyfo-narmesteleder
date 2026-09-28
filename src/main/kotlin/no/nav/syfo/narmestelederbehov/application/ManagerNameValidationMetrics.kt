package no.nav.syfo.narmestelederbehov.application

import no.nav.syfo.narmestelederrelasjon.domain.ManagerLastNameMatch

fun interface ManagerNameValidationMetrics {
    fun record(match: ManagerLastNameMatch)
}
