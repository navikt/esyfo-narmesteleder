package no.nav.syfo.bootstrap

import no.nav.syfo.integration.aareg.AaregClient
import no.nav.syfo.integration.aareg.FakeAaregClient
import no.nav.syfo.integration.aareg.HttpAaregClient
import no.nav.syfo.integration.dinesykmeldte.DinesykmeldteClient
import no.nav.syfo.integration.dinesykmeldte.FakeDinesykmeldteClient
import no.nav.syfo.integration.dinesykmeldte.HttpDinesykmeldteClient
import no.nav.syfo.integration.ereg.CachedEregClient
import no.nav.syfo.integration.ereg.EregClient
import no.nav.syfo.integration.ereg.FakeEregClient
import no.nav.syfo.integration.ereg.HttpEregClient
import no.nav.syfo.integration.pdl.FakePdlClient
import no.nav.syfo.integration.pdl.HttpPdlClient
import no.nav.syfo.integration.pdl.PdlClient
import no.nav.syfo.narmestelederbehov.infrastructure.dialogporten.client.DialogportenClient
import no.nav.syfo.narmestelederbehov.infrastructure.dialogporten.client.FakeDialogportenClient
import no.nav.syfo.narmestelederbehov.infrastructure.dialogporten.client.HttpDialogportenClient
import no.nav.syfo.organisasjonstilgang.infrastructure.altinnauthorization.AltinnAuthorizationClient
import no.nav.syfo.organisasjonstilgang.infrastructure.altinnauthorization.FakeAltinnAuthorizationClient
import no.nav.syfo.organisasjonstilgang.infrastructure.altinnauthorization.HttpAltinnAuthorizationClient
import no.nav.syfo.organisasjonstilgang.infrastructure.altinntilganger.AltinnTilgangerClient
import no.nav.syfo.organisasjonstilgang.infrastructure.altinntilganger.FakeAltinnTilgangerClient
import no.nav.syfo.organisasjonstilgang.infrastructure.altinntilganger.HttpAltinnTilgangerClient
import no.nav.syfo.platform.auth.texas.AltinnTokenProvider
import no.nav.syfo.platform.auth.texas.TexasEnvironment
import no.nav.syfo.platform.auth.texas.client.TexasHttpClient
import no.nav.syfo.platform.http.httpClientDefault
import org.koin.core.module.Module
import org.koin.core.scope.Scope
import org.koin.dsl.module

internal fun httpClientsModule(clients: ClientProperties, texas: TexasEnvironment, isLocalEnv: Boolean) = module {
    single { httpClientDefault() }
    single { TexasHttpClient(client = get(), environment = texas) }
    single {
        AltinnTokenProvider(
            texasHttpClient = get(),
            altinnBaseUrl = clients.altinn3BaseUrl,
            httpClient = get(),
        )
    }

    localOrRemote<AaregClient>(
        isLocalEnv,
        local = { FakeAaregClient() },
        remote = {
            HttpAaregClient(
                aaregBaseUrl = clients.aaregBaseUrl,
                texasHttpClient = get(),
                scope = clients.aaregScope,
            )
        },
    )
    localOrRemote<DinesykmeldteClient>(
        isLocalEnv,
        local = { FakeDinesykmeldteClient() },
        remote = {
            HttpDinesykmeldteClient(
                texasHttpClient = get(),
                scope = clients.dinesykmeldteScope,
                httpClient = get(),
                dinesykmeldteBaseUrl = clients.dinesykmeldteBaseUrl,
            )
        },
    )
    localOrRemote<PdlClient>(
        isLocalEnv,
        local = { FakePdlClient() },
        remote = {
            HttpPdlClient(
                httpClient = get(),
                pdlBaseUrl = clients.pdlBaseUrl,
                texasHttpClient = get(),
                scope = clients.pdlScope,
            )
        },
    )
    localOrRemote<AltinnTilgangerClient>(
        isLocalEnv,
        local = { FakeAltinnTilgangerClient() },
        remote = {
            HttpAltinnTilgangerClient(
                texasClient = get(),
                httpClient = get(),
                baseUrl = clients.altinnTilgangerBaseUrl,
            )
        },
    )
    localOrRemote<DialogportenClient>(
        isLocalEnv,
        local = { FakeDialogportenClient() },
        remote = {
            HttpDialogportenClient(
                httpClient = get(),
                baseUrl = clients.altinn3BaseUrl,
                altinnTokenProvider = get(),
            )
        },
    )
    single<EregClient> {
        CachedEregClient(
            delegate = if (isLocalEnv) FakeEregClient() else HttpEregClient(eregBaseUrl = clients.eregBaseUrl),
            cache = get(),
        )
    }
    localOrRemote<AltinnAuthorizationClient>(
        isLocalEnv,
        local = { FakeAltinnAuthorizationClient() },
        remote = {
            HttpAltinnAuthorizationClient(
                httpClient = get(),
                baseUrl = clients.altinn3BaseUrl,
                subscriptionKey = clients.pdpSubscriptionKey,
                altinnTokenProvider = get(),
            )
        },
    )
}

private inline fun <reified T : Any> Module.localOrRemote(
    isLocalEnv: Boolean,
    noinline local: Scope.() -> T,
    noinline remote: Scope.() -> T,
) {
    val definition = if (isLocalEnv) local else remote
    single<T> { definition() }
}
