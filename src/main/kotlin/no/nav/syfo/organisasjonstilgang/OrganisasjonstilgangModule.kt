package no.nav.syfo.organisasjonstilgang

import no.nav.syfo.organisasjonstilgang.application.OrganizationAccess
import no.nav.syfo.organisasjonstilgang.infrastructure.LegacyOrganizationAccess
import org.koin.dsl.module

fun organisasjonstilgangModule() = module {
    single<OrganizationAccess> { LegacyOrganizationAccess(get()) }
}
