package no.nav.syfo.narmestelederrelasjon.application

import no.nav.syfo.narmestelederrelasjon.application.revoke.RevocationInitiator
import no.nav.syfo.narmestelederrelasjon.domain.RelationSource
import no.nav.syfo.organisasjonstilgang.application.McpActingAs

fun McpActingAs.relationSource(): RelationSource = when (this) {
    McpActingAs.LPS -> RelationSource.LPS
    McpActingAs.PERSONNEL_MANAGER -> RelationSource.PERSONNEL_MANAGER
}

fun McpActingAs.revocationInitiator(): RevocationInitiator = when (this) {
    McpActingAs.LPS -> RevocationInitiator.LPS
    McpActingAs.PERSONNEL_MANAGER -> RevocationInitiator.PERSONNEL_MANAGER
}
