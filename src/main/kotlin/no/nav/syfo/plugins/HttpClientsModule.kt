package no.nav.syfo.plugins

import no.nav.syfo.aareg.client.AaregClient
import no.nav.syfo.aareg.client.FakeAaregClient
import no.nav.syfo.aareg.client.HttpAaregClient
import no.nav.syfo.altinn.dialogporten.client.DialogportenClient
import no.nav.syfo.altinn.dialogporten.client.FakeDialogportenClient
import no.nav.syfo.altinn.dialogporten.client.HttpDialogportenClient
import no.nav.syfo.altinn.pdp.client.FakePdpClient
import no.nav.syfo.altinn.pdp.client.HttpPdpClient
import no.nav.syfo.altinn.pdp.client.PdpClient
import no.nav.syfo.altinntilganger.client.AltinnTilgangerClient
import no.nav.syfo.altinntilganger.client.FakeAltinnTilgangerClient
import no.nav.syfo.altinntilganger.client.HttpAltinnTilgangerClient
import no.nav.syfo.dinesykmeldte.client.DinesykmeldteClient
import no.nav.syfo.dinesykmeldte.client.FakeDinesykmeldteClient
import no.nav.syfo.dinesykmeldte.client.HttpDinesykmeldteClient
import no.nav.syfo.ereg.client.EregClient
import no.nav.syfo.ereg.client.FakeEregClient
import no.nav.syfo.ereg.client.HttpEregClient
import no.nav.syfo.pdl.client.FakePdlClient
import no.nav.syfo.pdl.client.HttpPdlClient
import no.nav.syfo.pdl.client.PdlClient
import no.nav.syfo.texas.AltinnTokenProvider
import no.nav.syfo.texas.client.TexasHttpClient
import no.nav.syfo.util.httpClientDefault
import org.koin.core.module.Module
import org.koin.core.scope.Scope
import org.koin.dsl.module

internal fun httpClientsModule(isLocalEnv: Boolean) = module {
    single { httpClientDefault() }
    single { TexasHttpClient(client = get(), environment = env().texas) }
    single {
        AltinnTokenProvider(
            texasHttpClient = get(),
            altinnBaseUrl = env().clientProperties.altinn3BaseUrl,
            httpClient = get(),
        )
    }

    localOrRemote<AaregClient>(
        isLocalEnv,
        local = { FakeAaregClient() },
        remote = {
            HttpAaregClient(
                aaregBaseUrl = env().clientProperties.aaregBaseUrl,
                texasHttpClient = get(),
                scope = env().clientProperties.aaregScope,
            )
        },
    )
    localOrRemote<DinesykmeldteClient>(
        isLocalEnv,
        local = { FakeDinesykmeldteClient() },
        remote = {
            HttpDinesykmeldteClient(
                texasHttpClient = get(),
                scope = env().clientProperties.dinesykmeldteScope,
                httpClient = get(),
                dinesykmeldteBaseUrl = env().clientProperties.dinesykmeldteBaseUrl,
            )
        },
    )
    localOrRemote<PdlClient>(
        isLocalEnv,
        local = { FakePdlClient() },
        remote = {
            HttpPdlClient(
                httpClient = get(),
                pdlBaseUrl = env().clientProperties.pdlBaseUrl,
                texasHttpClient = get(),
                scope = env().clientProperties.pdlScope,
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
                baseUrl = env().clientProperties.altinnTilgangerBaseUrl,
            )
        },
    )
    localOrRemote<DialogportenClient>(
        isLocalEnv,
        local = { FakeDialogportenClient() },
        remote = {
            HttpDialogportenClient(
                httpClient = get(),
                baseUrl = env().clientProperties.altinn3BaseUrl,
                altinnTokenProvider = get(),
            )
        },
    )
    localOrRemote<EregClient>(
        isLocalEnv,
        local = { FakeEregClient() },
        remote = { HttpEregClient(eregBaseUrl = env().clientProperties.eregBaseUrl) },
    )
    localOrRemote<PdpClient>(
        isLocalEnv,
        local = { FakePdpClient() },
        remote = {
            HttpPdpClient(
                httpClient = get(),
                baseUrl = env().clientProperties.altinn3BaseUrl,
                subscriptionKey = env().clientProperties.pdpSubscriptionKey,
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
