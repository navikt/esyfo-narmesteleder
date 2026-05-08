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
import no.nav.syfo.application.valkey.EregCache
import no.nav.syfo.application.valkey.PdlCache
import no.nav.syfo.ereg.EregService
import no.nav.syfo.ereg.client.FakeEregClient
import no.nav.syfo.narmesteleder.api.v1.LinemanagerRequirementRESTHandler
import no.nav.syfo.narmesteleder.db.FakeNarmestelederDb
import no.nav.syfo.narmesteleder.kafka.FakeSykmeldingNLKafkaProducer
import no.nav.syfo.narmesteleder.kafka.NlBehovLeesahHandler
import no.nav.syfo.narmesteleder.service.NarmestelederKafkaService
import no.nav.syfo.narmesteleder.service.NarmestelederService
import no.nav.syfo.narmesteleder.service.ValidationService
import no.nav.syfo.narmesteleder.service.validators.PrincipalAccessValidator
import no.nav.syfo.narmesteleder.service.validators.SickLeaveValidator
import no.nav.syfo.pdl.PdlService
import no.nav.syfo.pdl.client.FakePdlClient
import no.nav.syfo.sykmelding.exposed.IActiveSykmeldingRepository

private class FakeActiveSykmeldingRepository : IActiveSykmeldingRepository {
    override suspend fun hasActiveSykmelding(fnr: String, orgnummer: String): Boolean = true
}

class FakesWrapper(dispatcher: CoroutineDispatcher = Dispatchers.Default) {
    val fakeDbSpyk = spyk(FakeNarmestelederDb())
    val fakeAaregClientSpyk = spyk(FakeAaregClient())
    val fakeEregClientSpyk = spyk(FakeEregClient())
    val fakePdlClientSpyk = spyk(FakePdlClient())
    val activeSykmeldingRepositorySpyk: IActiveSykmeldingRepository = spyk(FakeActiveSykmeldingRepository())
    val fakeKafkaProducerSpyk = spyk(FakeSykmeldingNLKafkaProducer())
    val fakeAltinnTilgangerClientSpyk = spyk(FakeAltinnTilgangerClient())
    val fakePdpClientSpyk = spyk(FakePdpClient())
    val fakeDialogportenClient = FakeDialogportenClient()
    val dialogportenService = mockk<DialogportenService>(relaxed = true)
    val aaregServiceSpyk = spyk(AaregService(fakeAaregClientSpyk))
    val eregCacheSpyk = mockk<EregCache>(relaxed = true)
    val eregServiceSpyk = spyk(EregService(fakeEregClientSpyk, eregCacheSpyk))
    val pdlCacheMock = mockk<PdlCache>(relaxed = true)
    val pdlServiceSpyk = spyk(PdlService(fakePdlClientSpyk, pdlCacheMock))
    val altinnTilgangerServiceSpyk = spyk(AltinnTilgangerService(fakeAltinnTilgangerClientSpyk))
    val pdpServiceSpyk = spyk(PdpService(fakePdpClientSpyk))
    val narmestelederKafkaServiceSpyk = spyk(
        NarmestelederKafkaService(kafkaSykemeldingProducer = fakeKafkaProducerSpyk),
    )
    val principalAccessValidatorSpyk = spyk(
        PrincipalAccessValidator(
            altinnTilgangerService = altinnTilgangerServiceSpyk,
            pdpService = pdpServiceSpyk,
            eregService = eregServiceSpyk,
        )
    )
    val sickLeaveValidatorSpyk = spyk(
        SickLeaveValidator(
            activeSykmeldingRepository = activeSykmeldingRepositorySpyk,
        )
    )
    val validationServiceSpyk = spyk(
        ValidationService(
            pdlService = pdlServiceSpyk,
            aaregService = aaregServiceSpyk,
            principalAccessValidator = principalAccessValidatorSpyk,
            sickLeaveValidator = sickLeaveValidatorSpyk,
        )
    )
    val narmestelederServiceSpyk = spyk(
        NarmestelederService(
            nlDb = fakeDbSpyk,
            persistLeesahNlBehov = true,
            aaregService = aaregServiceSpyk,
            pdlService = pdlServiceSpyk,
            activeSykmeldingRepository = activeSykmeldingRepositorySpyk,
            dialogportenService = dialogportenService
        )
    )
    val lnReqRESTHandlerSpyk = spyk(
        LinemanagerRequirementRESTHandler(
            narmesteLederService = narmestelederServiceSpyk,
            validationService = validationServiceSpyk,
            narmestelederKafkaService = narmestelederKafkaServiceSpyk,
        )
    )
    val nlBehovLeesahHandlerSpyk = spyk(
        NlBehovLeesahHandler(
            narmesteLederService = narmestelederServiceSpyk,
        )
    )
}
