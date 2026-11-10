package no.nav.syfo.organisasjonstilgang

import io.ktor.server.application.Application
import io.ktor.server.routing.routing
import no.nav.syfo.organisasjonstilgang.api.registerAccessibleOrganizationsApi
import no.nav.syfo.organisasjonstilgang.application.ListAccessibleOrganizationsUseCase
import no.nav.syfo.platform.api.apiV1
import no.nav.syfo.platform.auth.texas.authenticateMaskinportenOrTokenX
import org.koin.ktor.ext.get
import org.koin.ktor.plugin.koinModules

fun Application.organisasjonstilgangModule() {
    koinModules(organisasjonstilgangDependencies())
    val listAccessibleOrganizations = get<ListAccessibleOrganizationsUseCase>()
    routing {
        apiV1 {
            authenticateMaskinportenOrTokenX {
                registerAccessibleOrganizationsApi(listAccessibleOrganizations = listAccessibleOrganizations)
            }
        }
    }
}
