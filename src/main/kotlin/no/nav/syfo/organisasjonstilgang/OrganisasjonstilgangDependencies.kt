package no.nav.syfo.organisasjonstilgang

import no.nav.syfo.organisasjonstilgang.application.AccessibleOrganizationsLookup
import no.nav.syfo.organisasjonstilgang.application.ListAccessibleOrganizationsUseCase
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccess
import no.nav.syfo.organisasjonstilgang.infrastructure.AltinnOrganizationAccess
import no.nav.syfo.organisasjonstilgang.infrastructure.McpDevOrganizationAccess
import no.nav.syfo.organisasjonstilgang.infrastructure.altinntilganger.AltinnTilgangerService
import org.koin.dsl.binds
import org.koin.dsl.module

fun organisasjonstilgangDependencies(mcpEnabled: Boolean) = module {
    single { AltinnTilgangerService(altinnTilgangerClient = get()) } binds arrayOf(AccessibleOrganizationsLookup::class)
    single { ListAccessibleOrganizationsUseCase(get()) }
    single<OrganizationAccess> {
        val altinn = AltinnOrganizationAccess(get(), get(), get())
        if (mcpEnabled) McpDevOrganizationAccess(altinn) else altinn
    }
}
