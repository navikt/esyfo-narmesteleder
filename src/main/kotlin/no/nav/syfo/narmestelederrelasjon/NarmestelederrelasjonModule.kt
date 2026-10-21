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
import no.nav.syfo.platform.auth.texas.client.TexasHttpClient
import org.koin.ktor.ext.get
import org.koin.ktor.plugin.koinModules

fun Application.narmestelederrelasjonModule(settings: NarmestelederrelasjonSettings) {
    koinModules(narmestelederrelasjonDependencies(settings))
    val submit = get<SubmitNarmestelederrelasjonUseCase>()
    val revoke = get<RevokeActiveNarmestelederrelasjonUseCase>()
    val lookupActiveNarmesteleder = get<LookupActiveNarmestelederUseCase>()
    val searchActiveNarmestelederrelasjoner = get<SearchActiveNarmestelederrelasjonerUseCase>()
    val listActiveNarmesteledereForEmployee = get<ListActiveNarmesteledereForEmployeeUseCase>()
    val getNarmestelederrelasjon = get<GetNarmestelederrelasjonUseCase>()
    val revokeNarmestelederrelasjon = get<RevokeNarmestelederrelasjonUseCase>()
    val texasHttpClient = get<TexasHttpClient>()
    routing {
        apiV1 {
            registerSubmitNarmestelederrelasjonApi(submit = submit, texasHttpClient = texasHttpClient)
            registerRevokeActiveNarmestelederrelasjonApi(revoke = revoke, texasHttpClient = texasHttpClient)
        }
        internalApiV1 {
            registerLineManagerLookupApi(
                lookupActiveNarmesteleder = lookupActiveNarmesteleder,
                texasHttpClient = texasHttpClient,
                preAuthorizedApps = settings.lineManagerLookupPreAuthorizedApps,
            )
            registerLinemanagerSearchApi(searchActiveNarmestelederrelasjoner = searchActiveNarmestelederrelasjoner, texasHttpClient = texasHttpClient)
            registerEmployeeLinemanagerApi(listActiveNarmesteledereForEmployee = listActiveNarmesteledereForEmployee, texasHttpClient = texasHttpClient)
            registerNarmestelederrelasjonApi(
                getNarmestelederrelasjon = getNarmestelederrelasjon,
                revokeNarmestelederrelasjon = revokeNarmestelederrelasjon,
                texasHttpClient = texasHttpClient,
            )
        }
    }
}
