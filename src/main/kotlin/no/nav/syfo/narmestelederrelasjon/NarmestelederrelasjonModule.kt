package no.nav.syfo.narmestelederrelasjon

import io.ktor.server.application.Application
import io.ktor.server.routing.routing
import no.nav.syfo.narmestelederrelasjon.api.registerEmployeeLinemanagerApi
import no.nav.syfo.narmestelederrelasjon.api.registerLineManagerLookupApi
import no.nav.syfo.narmestelederrelasjon.api.registerLinemanagerSearchApi
import no.nav.syfo.narmestelederrelasjon.api.registerNarmestelederrelasjonApi
import no.nav.syfo.narmestelederrelasjon.api.registerRevokeActiveNarmestelederrelasjonApi
import no.nav.syfo.narmestelederrelasjon.api.registerSubmitNarmestelederrelasjonApi
import no.nav.syfo.narmestelederrelasjon.application.GetNarmestelederrelasjonUseCase
import no.nav.syfo.narmestelederrelasjon.application.ListActiveNarmesteledereForEmployeeUseCase
import no.nav.syfo.narmestelederrelasjon.application.LookupActiveNarmestelederUseCase
import no.nav.syfo.narmestelederrelasjon.application.RevokeActiveNarmestelederrelasjonUseCase
import no.nav.syfo.narmestelederrelasjon.application.RevokeNarmestelederrelasjonUseCase
import no.nav.syfo.narmestelederrelasjon.application.SearchActiveNarmestelederrelasjonerUseCase
import no.nav.syfo.narmestelederrelasjon.application.SubmitNarmestelederrelasjonUseCase
import no.nav.syfo.platform.api.apiV1
import no.nav.syfo.platform.api.internalApiV1
import no.nav.syfo.platform.auth.texas.client.TexasHttpClient
import org.koin.ktor.ext.get
import org.koin.ktor.plugin.koinModules

fun Application.narmestelederrelasjonModule() {
    koinModules(narmestelederrelasjonDependencies())
    val submit = get<SubmitNarmestelederrelasjonUseCase>()
    val revoke = get<RevokeActiveNarmestelederrelasjonUseCase>()
    val lookupActiveNarmesteleder = get<LookupActiveNarmestelederUseCase>()
    val searchActiveNarmestelederrelasjoner = get<SearchActiveNarmestelederrelasjonerUseCase>()
    val listActiveNarmesteledereForEmployee = get<ListActiveNarmesteledereForEmployeeUseCase>()
    val getNarmestelederrelasjon = get<GetNarmestelederrelasjonUseCase>()
    val revokeNarmestelederrelasjon = get<RevokeNarmestelederrelasjonUseCase>()
    val texasHttpClient = get<TexasHttpClient>()
    val preAuthorizedApps = get<NarmestelederrelasjonSettings>().lineManagerLookupPreAuthorizedApps
    routing {
        apiV1 {
            registerSubmitNarmestelederrelasjonApi(submit = submit, texasHttpClient = texasHttpClient)
            registerRevokeActiveNarmestelederrelasjonApi(revoke = revoke, texasHttpClient = texasHttpClient)
        }
        internalApiV1 {
            registerLineManagerLookupApi(
                lookupActiveNarmesteleder = lookupActiveNarmesteleder,
                texasHttpClient = texasHttpClient,
                preAuthorizedApps = preAuthorizedApps,
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
