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
import no.nav.syfo.dinesykmeldte.ClientDinesykmeldteService
import no.nav.syfo.dinesykmeldte.DinesykmeldteService
import no.nav.syfo.dinesykmeldte.client.FakeDinesykmeldteClient
import no.nav.syfo.ereg.EregService
import no.nav.syfo.ereg.client.FakeEregClient
import no.nav.syfo.narmesteleder.api.v1.LinemanagerRequirementRESTHandler
import no.nav.syfo.narmesteleder.db.FakeNarmestelederDb
import no.nav.syfo.narmesteleder.kafka.NlBehovLeesahHandler
import no.nav.syfo.narmesteleder.service.NarmestelederService
import no.nav.syfo.narmesteleder.service.ValidationService
import no.nav.syfo.narmesteleder.service.validators.PrincipalAccessValidator
import no.nav.syfo.pdl.PdlService
import no.nav.syfo.pdl.client.FakePdlClient

@Deprecated("Do not use in new tests. Construct focused test dependencies instead.")
class FakesWrapper(dispatcher: CoroutineDispatcher = Dispatchers.Default) {
    val fakeDbSpyk = spyk(FakeNarmestelederDb())
    val fakeAaregClientSpyk = spyk(FakeAaregClient())
    val fakeEregClientSpyk = spyk(FakeEregClient())
    val fakePdlClientSpyk = spyk(FakePdlClient())
    val fakeDinesykmeldteClientSpyk = spyk(FakeDinesykmeldteClient())
    val fakeAltinnTilgangerClientSpyk = spyk(FakeAltinnTilgangerClient())
    val fakePdpClientSpyk = spyk(FakePdpClient())
    val fakeDialogportenClient = FakeDialogportenClient()
    val dialogportenService = mockk<DialogportenService>(relaxed = true)
    val aaregServiceSpyk = spyk(AaregService(fakeAaregClientSpyk))
    val eregCacheSpyk = mockk<EregCache>(relaxed = true)
    val eregServiceSpyk = spyk(EregService(fakeEregClientSpyk, eregCacheSpyk))
    val pdlServiceSpyk = spyk(PdlService(fakePdlClientSpyk))
    val dinesykmeldteServiceSpyk: DinesykmeldteService = spyk(ClientDinesykmeldteService(fakeDinesykmeldteClientSpyk))
    val altinnTilgangerServiceSpyk = spyk(AltinnTilgangerService(fakeAltinnTilgangerClientSpyk))
    val pdpServiceSpyk = spyk(PdpService(fakePdpClientSpyk))
    val principalAccessValidatorSpyk = spyk(
        PrincipalAccessValidator(
            altinnTilgangerService = altinnTilgangerServiceSpyk,
            pdpService = pdpServiceSpyk,
            eregService = eregServiceSpyk,
        )
    )
    val validationServiceSpyk = spyk(
        ValidationService(
            principalAccessValidator = principalAccessValidatorSpyk,
        )
    )
    val narmestelederServiceSpyk = spyk(
        NarmestelederService(
            nlDb = fakeDbSpyk,
            persistLeesahNlBehov = true,
            aaregService = aaregServiceSpyk,
            pdlService = pdlServiceSpyk,
            dinesykmeldteService = dinesykmeldteServiceSpyk,
            dialogportenService = dialogportenService
        )
    )
    val lnReqRESTHandlerSpyk = spyk(
        LinemanagerRequirementRESTHandler(
            narmesteLederService = narmestelederServiceSpyk,
            validationService = validationServiceSpyk,
        )
    )
    val nlBehovLeesahHandlerSpyk = spyk(
        NlBehovLeesahHandler(
            narmesteLederService = narmestelederServiceSpyk,
        )
    )
}
