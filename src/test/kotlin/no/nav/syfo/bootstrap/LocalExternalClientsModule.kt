package no.nav.syfo.bootstrap

import no.nav.syfo.integration.aareg.AaregClient
import no.nav.syfo.integration.aareg.FakeAaregClient
import no.nav.syfo.integration.dinesykmeldte.DinesykmeldteClient
import no.nav.syfo.integration.dinesykmeldte.FakeDinesykmeldteClient
import no.nav.syfo.integration.ereg.CachedEregClient
import no.nav.syfo.integration.ereg.EregClient
import no.nav.syfo.integration.ereg.FakeEregClient
import no.nav.syfo.integration.pdl.FakePdlClient
import no.nav.syfo.integration.pdl.PdlClient
import no.nav.syfo.narmestelederbehov.infrastructure.dialogporten.client.DialogportenClient
import no.nav.syfo.narmestelederbehov.infrastructure.dialogporten.client.FakeDialogportenClient
import no.nav.syfo.organisasjonstilgang.infrastructure.altinnauthorization.AltinnAuthorizationClient
import no.nav.syfo.organisasjonstilgang.infrastructure.altinnauthorization.FakeAltinnAuthorizationClient
import no.nav.syfo.organisasjonstilgang.infrastructure.altinntilganger.AltinnTilgangerClient
import no.nav.syfo.organisasjonstilgang.infrastructure.altinntilganger.FakeAltinnTilgangerClient
import org.koin.dsl.module

/** Replaces [externalClientsModule] with fakes for local runs and tests. */
internal fun localExternalClientsModule() = module {
    single<AaregClient> { FakeAaregClient() }
    single<DinesykmeldteClient> { FakeDinesykmeldteClient() }
    single<PdlClient> { FakePdlClient() }
    single<AltinnTilgangerClient> { FakeAltinnTilgangerClient() }
    single<DialogportenClient> { FakeDialogportenClient() }
    single<EregClient> { CachedEregClient(delegate = FakeEregClient(), cache = get()) }
    single<AltinnAuthorizationClient> { FakeAltinnAuthorizationClient() }
}
