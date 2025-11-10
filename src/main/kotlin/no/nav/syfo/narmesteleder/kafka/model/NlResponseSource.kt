package no.nav.syfo.narmesteleder.kafka.model

import no.nav.syfo.application.auth.SystemPrincipal
import no.nav.syfo.application.auth.Principal
import no.nav.syfo.application.auth.UserPrincipal
import no.nav.syfo.narmesteleder.domain.Linemanager
import no.nav.syfo.narmesteleder.domain.LinemanagerRevoke

enum class NlResponseSource(val source: String) {
    LPS("esyo-narmesteleder.lps"),
    PERSONALLEDER("esyo-narmesteleder.personalleder"),
    ARBEIDSTAGER("esyo-narmesteleder.arbeidstager"),
    NARMESTELEDER("esyo-narmesteleder.leder");

    companion object {
        fun getSourceFrom(principal: Principal, linemanager: Linemanager): NlResponseSource {
            return when(principal) {
                is SystemPrincipal -> LPS
                is UserPrincipal -> {
                    when (principal.ident) {
                        linemanager.employeeIdentificationNumber -> ARBEIDSTAGER
                        linemanager.manager.nationalIdentificationNumber -> NARMESTELEDER
                        else -> PERSONALLEDER
                    }
                }
            }
        }
        fun getSourceFrom(principal: Principal, linemanagerRevoke: LinemanagerRevoke): NlResponseSource {
            return when(principal) {
                is SystemPrincipal -> LPS
                is UserPrincipal -> {
                    when (principal.ident) { // Can add option for NARMESTELEDER if we accept requests from them and can identity the caller as such
                        linemanagerRevoke.employeeIdentificationNumber -> ARBEIDSTAGER
                        else -> PERSONALLEDER
                    }
                }
            }
        }
    }
}
