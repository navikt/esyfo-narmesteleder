package no.nav.syfo

import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import no.nav.syfo.aareg.AaregService
import no.nav.syfo.aareg.client.FakeAaregClient
import no.nav.syfo.altinn.dialogporten.client.FakeDialogportenClient
import no.nav.syfo.altinn.dialogporten.service.DialogportenService
import no.nav.syfo.altinn.pdp.client.FakePdpClient
import no.nav.syfo.altinn.pdp.service.PdpService
import no.nav.syfo.altinntilganger.AltinnTilgangerService
import no.nav.syfo.altinntilganger.client.FakeAltinnTilgangerClient
import no.nav.syfo.dinesykmeldte.DinesykmeldteService
import no.nav.syfo.dinesykmeldte.client.FakeDinesykmeldteClient
import no.nav.syfo.narmesteleder.api.v1.LinemanagerRequirementRESTHandler
import no.nav.syfo.narmesteleder.db.FakeNarmestelederDb
import no.nav.syfo.narmesteleder.kafka.FakeSykemeldingNLKafkaProducer
import no.nav.syfo.narmesteleder.service.NarmestelederKafkaService
import no.nav.syfo.narmesteleder.service.NarmestelederService
import no.nav.syfo.narmesteleder.service.ValidationService
import no.nav.syfo.pdl.PdlService
import no.nav.syfo.pdl.client.FakePdlClient

class FakesWrapper(dispatcher: CoroutineDispatcher = Dispatchers.Default) {
    val fakeDbSpyk = spyk(FakeNarmestelederDb())
    val fakeAaregClientSpyk = spyk(FakeAaregClient())
    val fakePdlClientSpyk = spyk(FakePdlClient())
    val fakeDinesykemeldteClientSpyk = spyk(FakeDinesykmeldteClient())
    val fakeKafkaProducerSpyk = spyk(FakeSykemeldingNLKafkaProducer())
    val fakeAltinnTilgangerClientSpyk = spyk(FakeAltinnTilgangerClient())
    val fakePdpClientSpyk = spyk(FakePdpClient())
    val fakeDialogportenClient = FakeDialogportenClient()
    val dialogportenService = mockk<DialogportenService>(relaxed = true)
    val aaregServiceSpyk = spyk(AaregService(fakeAaregClientSpyk))
    val pdlServiceSpyk = spyk(PdlService(fakePdlClientSpyk))
    val dinesykemeldteServiceSpyk = spyk(DinesykmeldteService(fakeDinesykemeldteClientSpyk))
    val altinnTilgangerServiceSpyk = spyk(AltinnTilgangerService(fakeAltinnTilgangerClientSpyk))
    val pdpServiceSpyk = spyk(PdpService(fakePdpClientSpyk))
    val narmestelederKafkaServiceSpyk = spyk(
        NarmestelederKafkaService(kafkaSykemeldingProducer = fakeKafkaProducerSpyk),
    )
    val validationServiceSpyk = spyk(
        ValidationService(
            pdlServiceSpyk,
            aaregServiceSpyk,
            altinnTilgangerServiceSpyk,
            dinesykemeldteServiceSpyk,
            pdpServiceSpyk
        )
    )
    val narmestelederServiceSpyk = spyk(
        NarmestelederService(
            nlDb = fakeDbSpyk,
            persistLeesahNlBehov = true,
            aaregService = aaregServiceSpyk,
            pdlService = pdlServiceSpyk,
            dinesykmeldteService = dinesykemeldteServiceSpyk,
            dialogportenService = dialogportenService
        )
    )
    val lnReqRESTHandlerSpyk = spyk(
        LinemanagerRequirementRESTHandler(
            narmesteLederService = narmestelederServiceSpyk,
            validationService = validationServiceSpyk,
            narmestelederKafkaService = narmestelederKafkaServiceSpyk,
            altinnTilgangerService = altinnTilgangerServiceSpyk,
        )
    )
}
