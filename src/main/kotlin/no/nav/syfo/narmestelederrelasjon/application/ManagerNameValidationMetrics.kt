package no.nav.syfo.narmestelederrelasjon.application

import no.nav.syfo.narmestelederrelasjon.domain.LastNameMatch

fun interface ManagerNameValidationMetrics {
    fun record(match: LastNameMatch)
}
