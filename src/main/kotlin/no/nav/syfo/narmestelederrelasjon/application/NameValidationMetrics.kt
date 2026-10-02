package no.nav.syfo.narmestelederrelasjon.application

import no.nav.syfo.narmestelederrelasjon.domain.LastNameMatch

fun interface NameValidationMetrics {
    fun record(match: LastNameMatch)
}
