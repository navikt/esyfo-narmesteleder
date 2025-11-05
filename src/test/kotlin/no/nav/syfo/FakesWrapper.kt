package no.nav.syfo

import io.mockk.spyk
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import no.nav.syfo.aareg.AaregService
import no.nav.syfo.aareg.client.FakeAaregClient
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

    val aaregServiceSpyk = spyk(AaregService(fakeAaregClientSpyk))
    val pdlServiceSpyk = spyk(PdlService(fakePdlClientSpyk))
    val dinesykemeldteServiceSpyk = spyk(DinesykmeldteService(fakeDinesykemeldteClientSpyk))
    val altinnTilgangerServiceSpyk = spyk(AltinnTilgangerService(fakeAltinnTilgangerClientSpyk))
    val narmestelederKafkaServiceSpyk = spyk(
        NarmestelederKafkaService(kafkaSykemeldingProducer = fakeKafkaProducerSpyk),
    )
    val validationServiceSpyk = spyk(
        ValidationService(
            pdlServiceSpyk,
            aaregServiceSpyk,
            altinnTilgangerServiceSpyk,
            dinesykemeldteServiceSpyk
        )
    )
    val narmestelederServiceSpyk = spyk(
        NarmestelederService(
            nlDb = fakeDbSpyk,
            persistLeesahNlBehov = true,
            aaregService = aaregServiceSpyk,
            pdlService = pdlServiceSpyk,
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
