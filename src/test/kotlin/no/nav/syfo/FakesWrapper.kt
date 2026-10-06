package no.nav.syfo

import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import no.nav.syfo.aareg.AaregService
import no.nav.syfo.altinn.dialogporten.service.DialogportenService
import no.nav.syfo.dinesykmeldte.ClientDinesykmeldteService
import no.nav.syfo.dinesykmeldte.DinesykmeldteService
import no.nav.syfo.integration.aareg.FakeAaregClient
import no.nav.syfo.integration.dinesykmeldte.FakeDinesykmeldteClient
import no.nav.syfo.narmesteleder.db.FakeNarmestelederDb
import no.nav.syfo.narmesteleder.kafka.NlBehovLeesahHandler
import no.nav.syfo.narmesteleder.service.NarmestelederService

@Deprecated("Do not use in new tests. Construct focused test dependencies instead.")
class FakesWrapper(dispatcher: CoroutineDispatcher = Dispatchers.Default) {
    val fakeDbSpyk = spyk(FakeNarmestelederDb())
    val fakeAaregClientSpyk = spyk(FakeAaregClient())
    val fakeDinesykmeldteClientSpyk = spyk(FakeDinesykmeldteClient())
    val dialogportenService = mockk<DialogportenService>(relaxed = true)
    val aaregServiceSpyk = spyk(AaregService(fakeAaregClientSpyk))
    val dinesykmeldteServiceSpyk: DinesykmeldteService = spyk(ClientDinesykmeldteService(fakeDinesykmeldteClientSpyk))
    val narmestelederServiceSpyk = spyk(
        NarmestelederService(
            nlDb = fakeDbSpyk,
            persistLeesahNlBehov = true,
            aaregService = aaregServiceSpyk,
            dinesykmeldteService = dinesykmeldteServiceSpyk,
            dialogportenService = dialogportenService
        )
    )
    val nlBehovLeesahHandlerSpyk = spyk(
        NlBehovLeesahHandler(
            narmesteLederService = narmestelederServiceSpyk,
        )
    )
}
