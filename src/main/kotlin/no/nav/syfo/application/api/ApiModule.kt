package no.nav.syfo.application.api

import io.ktor.server.application.Application
import io.ktor.server.routing.Route
import io.ktor.server.routing.routing
import no.nav.syfo.altinn.dialogporten.registerDialogportenTokenApi
import no.nav.syfo.application.environment.Environment
import no.nav.syfo.application.environment.isProdEnv
import no.nav.syfo.narmestelederbehov.api.registerFulfillNarmestelederbehovApi
import no.nav.syfo.narmestelederbehov.api.registerGetNarmestelederbehovApi
import no.nav.syfo.narmestelederbehov.api.registerListNarmestelederbehovApi
import no.nav.syfo.narmestelederrelasjon.api.registerEmployeeLinemanagerApi
import no.nav.syfo.narmestelederrelasjon.api.registerLineManagerLookupApi
import no.nav.syfo.narmestelederrelasjon.api.registerLinemanagerSearchApi
import no.nav.syfo.narmestelederrelasjon.api.registerNarmestelederrelasjonApi
import no.nav.syfo.narmestelederrelasjon.api.registerRevokeActiveNarmestelederrelasjonApi
import no.nav.syfo.narmestelederrelasjon.api.registerSubmitNarmestelederrelasjonApi
import no.nav.syfo.organisasjonstilgang.api.registerAccessibleOrganizationsApi
import no.nav.syfo.platform.api.apiV1
import no.nav.syfo.platform.api.internalApiV1
import org.koin.ktor.ext.get

fun Application.configureRouting() {
    routing {
        apiV1 {
            registerApiV1Routes()
        }
        internalApiV1 {
            registerInternalApiV1Routes()
        }
        if (!isProdEnv()) {
            // TODO: Remove this endpoint later
            registerDialogportenTokenApi(texasHttpClient = get(), altinnTokenProvider = get())
        }
    }
}

private fun Route.registerApiV1Routes() {
    registerGetNarmestelederbehovApi(getNarmestelederbehov = get(), texasHttpClient = get())
    registerFulfillNarmestelederbehovApi(fulfillNarmestelederbehov = get(), texasHttpClient = get())
    registerListNarmestelederbehovApi(listNarmestelederbehov = get(), texasHttpClient = get())
    registerSubmitNarmestelederrelasjonApi(submit = get(), texasHttpClient = get())
    registerRevokeActiveNarmestelederrelasjonApi(revoke = get(), texasHttpClient = get())
    registerAccessibleOrganizationsApi(listAccessibleOrganizations = get(), texasHttpClient = get())
}

private fun Route.registerInternalApiV1Routes() {
    registerLineManagerLookupApi(
        lookupActiveNarmesteleder = get(),
        texasHttpClient = get(),
        preAuthorizedApps = get<Environment>().texas.azurePreAuthorizedApps,
    )
    registerLinemanagerSearchApi(searchActiveNarmestelederrelasjoner = get(), texasHttpClient = get())
    registerEmployeeLinemanagerApi(listActiveNarmesteledereForEmployee = get(), texasHttpClient = get())
    registerNarmestelederrelasjonApi(
        getNarmestelederrelasjon = get(),
        revokeNarmestelederrelasjon = get(),
        texasHttpClient = get(),
    )
}
