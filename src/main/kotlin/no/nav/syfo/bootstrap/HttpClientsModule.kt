package no.nav.syfo.bootstrap

import no.nav.syfo.integration.aareg.AaregClient
import no.nav.syfo.integration.aareg.HttpAaregClient
import no.nav.syfo.integration.dinesykmeldte.DinesykmeldteClient
import no.nav.syfo.integration.dinesykmeldte.HttpDinesykmeldteClient
import no.nav.syfo.integration.ereg.CachedEregClient
import no.nav.syfo.integration.ereg.EregClient
import no.nav.syfo.integration.ereg.HttpEregClient
import no.nav.syfo.integration.pdl.HttpPdlClient
import no.nav.syfo.integration.pdl.PdlClient
import no.nav.syfo.narmestelederbehov.infrastructure.dialogporten.client.DialogportenClient
import no.nav.syfo.narmestelederbehov.infrastructure.dialogporten.client.HttpDialogportenClient
import no.nav.syfo.organisasjonstilgang.infrastructure.altinnauthorization.AltinnAuthorizationClient
import no.nav.syfo.organisasjonstilgang.infrastructure.altinnauthorization.HttpAltinnAuthorizationClient
import no.nav.syfo.organisasjonstilgang.infrastructure.altinntilganger.AltinnTilgangerClient
import no.nav.syfo.organisasjonstilgang.infrastructure.altinntilganger.HttpAltinnTilgangerClient
import no.nav.syfo.platform.auth.texas.TexasEnvironment
import no.nav.syfo.platform.auth.texas.altinn.AltinnTokenSource
import no.nav.syfo.platform.auth.texas.altinn.CachingAltinnTokenSource
import no.nav.syfo.platform.auth.texas.altinn.TexasAltinnTokenFetcher
import no.nav.syfo.platform.auth.texas.client.TexasHttpClient
import no.nav.syfo.platform.http.httpClientDefault
import org.koin.dsl.module

internal fun httpClientsModule(
    settings: HttpClientsSettings,
    texas: TexasEnvironment,
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
}

/** Registers the clients for external systems. Local runs and tests replace this module with fakes. */
internal fun externalClientsModule(settings: HttpClientsSettings) = module {
    single<AaregClient> {
        HttpAaregClient(
            aaregBaseUrl = settings.aaregSettings.baseUrl,
            texasHttpClient = get(),
            scope = settings.aaregSettings.scope,
        )
    }
    single<DinesykmeldteClient> {
        HttpDinesykmeldteClient(
            texasHttpClient = get(),
            scope = settings.dinesykmeldteSettings.scope,
            httpClient = get(),
            dinesykmeldteBaseUrl = settings.dinesykmeldteSettings.baseUrl,
        )
    }
    single<PdlClient> {
        HttpPdlClient(
            httpClient = get(),
            pdlBaseUrl = settings.pdlSettings.baseUrl,
            texasHttpClient = get(),
            scope = settings.pdlSettings.scope,
        )
    }
    single<AltinnTilgangerClient> {
        HttpAltinnTilgangerClient(
            texasClient = get(),
            httpClient = get(),
            baseUrl = settings.altinnAccessSettings.baseUrl,
        )
    }
    single<DialogportenClient> {
        HttpDialogportenClient(
            httpClient = get(),
            baseUrl = settings.dialogportenSettings.baseUrl,
            altinnTokenProvider = get(),
        )
    }
    single<EregClient> {
        CachedEregClient(
            delegate = HttpEregClient(eregBaseUrl = settings.eregSettings.baseUrl),
            cache = get(),
        )
    }
    single<AltinnAuthorizationClient> {
        HttpAltinnAuthorizationClient(
            httpClient = get(),
            baseUrl = settings.altinnApiSettings.baseUrl,
            subscriptionKey = settings.altinnAuthorizationSettings.subscriptionKey,
            altinnTokenProvider = get(),
        )
    }
}
