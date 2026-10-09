package no.nav.syfo

import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import no.nav.syfo.altinn.dialogporten.service.DialogportenService
import no.nav.syfo.integration.aareg.FakeAaregClient
import no.nav.syfo.integration.dinesykmeldte.FakeDinesykmeldteClient
import no.nav.syfo.narmesteleder.db.FakeNarmestelederDb

@Deprecated("Do not use in new tests. Construct focused test dependencies instead.")
class FakesWrapper(dispatcher: CoroutineDispatcher = Dispatchers.Default) {
    val fakeDbSpyk = spyk(FakeNarmestelederDb())
    val fakeAaregClientSpyk = spyk(FakeAaregClient())
    val fakeDinesykmeldteClientSpyk = spyk(FakeDinesykmeldteClient())
    val dialogportenService = mockk<DialogportenService>(relaxed = true)
}
