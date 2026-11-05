package no.nav.syfo.narmestelederrelasjon

import io.ktor.server.application.Application
import io.ktor.server.routing.routing
import no.nav.syfo.narmestelederrelasjon.api.registerEmployeeLinemanagerApi
import no.nav.syfo.narmestelederrelasjon.api.registerLineManagerLookupApi
import no.nav.syfo.narmestelederrelasjon.api.registerLinemanagerSearchApi
import no.nav.syfo.narmestelederrelasjon.api.registerNarmestelederrelasjonApi
import no.nav.syfo.narmestelederrelasjon.api.registerRevokeActiveNarmestelederrelasjonApi
import no.nav.syfo.narmestelederrelasjon.api.registerSubmitNarmestelederrelasjonApi
import no.nav.syfo.narmestelederrelasjon.application.employee.ListActiveNarmesteledereForEmployeeUseCase
import no.nav.syfo.narmestelederrelasjon.application.get.GetNarmestelederrelasjonUseCase
import no.nav.syfo.narmestelederrelasjon.application.lookup.LookupActiveNarmestelederUseCase
import no.nav.syfo.narmestelederrelasjon.application.revoke.RevokeActiveNarmestelederrelasjonUseCase
import no.nav.syfo.narmestelederrelasjon.application.revoke.RevokeNarmestelederrelasjonUseCase
import no.nav.syfo.narmestelederrelasjon.application.search.SearchActiveNarmestelederrelasjonerUseCase
import no.nav.syfo.narmestelederrelasjon.application.submit.SubmitNarmestelederrelasjonUseCase
import no.nav.syfo.platform.api.apiV1
import no.nav.syfo.platform.api.internalApiV1
import no.nav.syfo.platform.auth.texas.authenticateAzureAd
import no.nav.syfo.platform.auth.texas.authenticateMaskinportenOrTokenX
import no.nav.syfo.platform.auth.texas.authenticateTokenX
import org.koin.ktor.ext.get
import org.koin.ktor.plugin.koinModules

fun Application.narmestelederrelasjonModule(settings: NarmestelederrelasjonEmploymentSettings) {
    koinModules(narmestelederrelasjonDependencies(settings))
    val submit = get<SubmitNarmestelederrelasjonUseCase>()
    val revoke = get<RevokeActiveNarmestelederrelasjonUseCase>()
    val lookupActiveNarmesteleder = get<LookupActiveNarmestelederUseCase>()
    val searchActiveNarmestelederrelasjoner = get<SearchActiveNarmestelederrelasjonerUseCase>()
    val listActiveNarmesteledereForEmployee = get<ListActiveNarmesteledereForEmployeeUseCase>()
    val getNarmestelederrelasjon = get<GetNarmestelederrelasjonUseCase>()
    val revokeNarmestelederrelasjon = get<RevokeNarmestelederrelasjonUseCase>()
    routing {
        apiV1 {
            authenticateMaskinportenOrTokenX {
                registerSubmitNarmestelederrelasjonApi(submit = submit)
                registerRevokeActiveNarmestelederrelasjonApi(revoke = revoke)
            }
        }
        internalApiV1 {
            authenticateAzureAd {
                registerLineManagerLookupApi(
                    lookupActiveNarmesteleder = lookupActiveNarmesteleder,
                )
            }
            authenticateTokenX {
                registerEmployeeLinemanagerApi(listActiveNarmesteledereForEmployee = listActiveNarmesteledereForEmployee)
            }
            authenticateMaskinportenOrTokenX {
                registerLinemanagerSearchApi(searchActiveNarmestelederrelasjoner = searchActiveNarmestelederrelasjoner)
                registerNarmestelederrelasjonApi(
                    getNarmestelederrelasjon = getNarmestelederrelasjon,
                    revokeNarmestelederrelasjon = revokeNarmestelederrelasjon,
                )
            }
        }
    }
}
