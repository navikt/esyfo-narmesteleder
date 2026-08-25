package no.nav.syfo.narmesteleder.kafka.model

import no.nav.syfo.application.auth.Principal
import no.nav.syfo.application.auth.SystemPrincipal
import no.nav.syfo.application.auth.UserPrincipal
import no.nav.syfo.narmesteleder.domain.Linemanager
import no.nav.syfo.narmesteleder.domain.LinemanagerRevoke
import no.nav.syfo.narmesteleder.domain.RevokedBy

enum class NlResponseSource(val source: String) {
    LPS("esyo-narmesteleder.lps"),
    LPS_REVOKE("esyo-narmesteleder.lps.deaktivert"),
    PERSONALLEDER("esyo-narmesteleder.personalleder"),
    PERSONALLEDER_REVOKE("esyo-narmesteleder.personalleder.deaktivert"),
    ARBEIDSTAGER_REVOKE("esyo-narmesteleder.arbeidstager.deaktivert"),
    NARMESTELEDER_REVOKE("esyo-narmesteleder.leder.deaktivert"),
    ARBEIDSTAGER_SYKMELDING_REVOKE("esyo-narmesteleder.arbeidstager.sykmelding.deaktivert");

    companion object {
        fun getSourceFrom(principal: Principal, linemanager: Linemanager): NlResponseSource = when (principal) {
            is SystemPrincipal -> LPS
            is UserPrincipal -> PERSONALLEDER
        }

        fun getSourceFrom(principal: Principal, linemanagerRevoke: LinemanagerRevoke): NlResponseSource = when (principal) {
            is SystemPrincipal -> LPS_REVOKE
            is UserPrincipal -> {
                // This flow only knows the employee identifier. When the revoking party is resolved from an
                // existing relation, use getRevokeSourceFrom(RevokedBy) instead.
                when (principal.ident) {
                    linemanagerRevoke.employeeIdentificationNumber.value -> ARBEIDSTAGER_REVOKE
                    else -> PERSONALLEDER_REVOKE
                }
            }
        }

        fun getRevokeSourceFrom(revokedBy: RevokedBy): NlResponseSource = when (revokedBy) {
            RevokedBy.EMPLOYEE -> ARBEIDSTAGER_REVOKE
            RevokedBy.LINEMANAGER -> NARMESTELEDER_REVOKE
            RevokedBy.LPS -> LPS_REVOKE
        }
    }
}
