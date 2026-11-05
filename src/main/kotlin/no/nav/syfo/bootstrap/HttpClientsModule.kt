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
import no.nav.syfo.platform.auth.texas.TexasEnvironment
import no.nav.syfo.platform.auth.texas.altinn.AltinnTokenSource
import no.nav.syfo.platform.auth.texas.altinn.CachingAltinnTokenSource
import no.nav.syfo.platform.auth.texas.altinn.TexasAltinnTokenFetcher
import no.nav.syfo.platform.auth.texas.client.TexasHttpClient
import no.nav.syfo.platform.http.httpClientDefault
import org.koin.core.module.Module
import org.koin.core.scope.Scope
import org.koin.dsl.module

internal fun httpClientsModule(
    settings: HttpClientsSettings,
    texas: TexasEnvironment,
    isLocalEnv: Boolean,
) = module {
    single { httpClientDefault() }
    single { TexasHttpClient(client = get(), environment = texas) }
    single<AltinnTokenSource> {
        CachingAltinnTokenSource(
            delegate = TexasAltinnTokenFetcher(
                texasHttpClient = get(),
                altinnBaseUrl = settings.altinnApiSettings.baseUrl,
                httpClient = get(),
            ),
        )
    }

    localOrRemote<AaregClient>(
        isLocalEnv,
        local = { FakeAaregClient() },
        remote = {
            HttpAaregClient(
                aaregBaseUrl = settings.aaregSettings.baseUrl,
                texasHttpClient = get(),
                scope = settings.aaregSettings.scope,
            )
        },
    )
    localOrRemote<DinesykmeldteClient>(
        isLocalEnv,
        local = { FakeDinesykmeldteClient() },
        remote = {
            HttpDinesykmeldteClient(
                texasHttpClient = get(),
                scope = settings.dinesykmeldteSettings.scope,
                httpClient = get(),
                dinesykmeldteBaseUrl = settings.dinesykmeldteSettings.baseUrl,
            )
        },
    )
    localOrRemote<PdlClient>(
        isLocalEnv,
        local = { FakePdlClient() },
        remote = {
            HttpPdlClient(
                httpClient = get(),
                pdlBaseUrl = settings.pdlSettings.baseUrl,
                texasHttpClient = get(),
                scope = settings.pdlSettings.scope,
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
                baseUrl = settings.altinnAccessSettings.baseUrl,
            )
        },
    )
    localOrRemote<DialogportenClient>(
        isLocalEnv,
        local = { FakeDialogportenClient() },
        remote = {
            HttpDialogportenClient(
                httpClient = get(),
                baseUrl = settings.dialogportenSettings.baseUrl,
                altinnTokenProvider = get(),
            )
        },
    )
    single<EregClient> {
        CachedEregClient(
            delegate = if (isLocalEnv) FakeEregClient() else HttpEregClient(eregBaseUrl = settings.eregSettings.baseUrl),
            cache = get(),
        )
    }
    localOrRemote<AltinnAuthorizationClient>(
        isLocalEnv,
        local = { FakeAltinnAuthorizationClient() },
        remote = {
            HttpAltinnAuthorizationClient(
                httpClient = get(),
                baseUrl = settings.altinnApiSettings.baseUrl,
                subscriptionKey = settings.altinnAuthorizationSettings.subscriptionKey,
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
