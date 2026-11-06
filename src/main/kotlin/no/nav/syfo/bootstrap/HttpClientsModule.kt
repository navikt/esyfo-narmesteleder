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
import no.nav.syfo.platform.auth.texas.TexasSettings
import no.nav.syfo.platform.auth.texas.altinn.AltinnApiSettings
import no.nav.syfo.platform.auth.texas.altinn.AltinnTokenSource
import no.nav.syfo.platform.auth.texas.altinn.CachingAltinnTokenSource
import no.nav.syfo.platform.auth.texas.altinn.TexasAltinnTokenFetcher
import no.nav.syfo.platform.auth.texas.client.TexasHttpClient
import no.nav.syfo.platform.http.httpClientDefault
import org.koin.dsl.module

internal fun httpClientsModule(
    texas: TexasSettings,
    altinnApi: AltinnApiSettings,
) = module {
    single { httpClientDefault() }
    single { TexasHttpClient(client = get(), environment = texas) }
    single<AltinnTokenSource> {
        CachingAltinnTokenSource(
            delegate = TexasAltinnTokenFetcher(
                texasHttpClient = get(),
                altinnBaseUrl = altinnApi.baseUrl,
                httpClient = get(),
            ),
        )
    }
}

/** Registers the clients for external systems. */
internal fun externalClientsModule(settings: ApplicationSettings) = module {
    single<AaregClient> {
        HttpAaregClient(
            aaregBaseUrl = settings.integrations.aareg.baseUrl,
            texasHttpClient = get(),
            scope = settings.integrations.aareg.scope,
        )
    }
    single<DinesykmeldteClient> {
        HttpDinesykmeldteClient(
            texasHttpClient = get(),
            scope = settings.integrations.dinesykmeldte.scope,
            httpClient = get(),
            dinesykmeldteBaseUrl = settings.integrations.dinesykmeldte.baseUrl,
        )
    }
    single<PdlClient> {
        HttpPdlClient(
            httpClient = get(),
            pdlBaseUrl = settings.integrations.pdl.baseUrl,
            texasHttpClient = get(),
            scope = settings.integrations.pdl.scope,
        )
    }
    single<AltinnTilgangerClient> {
        HttpAltinnTilgangerClient(
            texasClient = get(),
            httpClient = get(),
            baseUrl = settings.organisasjonstilgang.altinnTilganger.baseUrl,
        )
    }
    single<DialogportenClient> {
        HttpDialogportenClient(
            httpClient = get(),
            baseUrl = settings.narmestelederbehov.dialogporten.baseUrl,
            altinnTokenProvider = get(),
        )
    }
    single<EregClient> {
        CachedEregClient(
            delegate = HttpEregClient(eregBaseUrl = settings.integrations.ereg.baseUrl),
            cache = get(),
        )
    }
    single<AltinnAuthorizationClient> {
        HttpAltinnAuthorizationClient(
            httpClient = get(),
            baseUrl = settings.altinnApi.baseUrl,
            subscriptionKey = settings.organisasjonstilgang.altinnAuthorization.subscriptionKey,
            altinnTokenProvider = get(),
        )
    }
}
