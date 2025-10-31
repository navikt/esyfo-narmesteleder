package no.nav.syfo.narmesteleder.api.v1

import createMockToken
import io.kotest.core.spec.style.FunSpec
import io.mockk.clearAllMocks
import io.mockk.coVerify
import java.util.*
import kotlinx.coroutines.Dispatchers
import no.nav.syfo.FakesWrapper
import no.nav.syfo.aareg.client.FakeAaregClient
import no.nav.syfo.application.auth.OrganisasjonPrincipal
import no.nav.syfo.narmesteleder.db.NarmestelederBehovEntity
import no.nav.syfo.narmesteleder.domain.BehovStatus
import no.nav.syfo.narmesteleder.domain.LinemanagerRequirementUpdate
import no.nav.syfo.narmesteleder.domain.Manager

class LinemanagerRequirementRESTHandlerTest : FunSpec({
    val servicesWrapper = FakesWrapper(Dispatchers.Default)
    // Map<Personnummer, List<Pair<underenhet, hovedenhet>>>
    val defaultManagerFnr = FakeAaregClient.defaultArbeidsforhold.keys.first()
    val defaultEmployeeFnr = FakeAaregClient.defaultArbeidsforhold.keys.last()
    val arbeidsforholdEmployeeAareg = FakeAaregClient.defaultArbeidsforhold[defaultEmployeeFnr]!!.first()
    val arbeidsforholdManagerAareg = FakeAaregClient.defaultArbeidsforhold[defaultManagerFnr]!!.first()

    val defaultManager = Manager(
        nationalIdentificationNumber = defaultManagerFnr,
        mobile = "99999999",
        email = "mail@manager.no",
        firstName = "FirstName",
        lastName = "LastName",
    )
    val defaultRequirement = NarmestelederBehovEntity(
        id = UUID.randomUUID(),
        orgnummer = arbeidsforholdEmployeeAareg.first,
        hovedenhetOrgnummer = arbeidsforholdEmployeeAareg.second,
        sykmeldtFnr = defaultEmployeeFnr,
        narmestelederFnr = "123456789",
        leesahStatus = "DISABLED",
    )

    beforeTest {
        clearAllMocks()
        servicesWrapper.fakeNlReqDbSpyk.clear()
    }

    test("Should update linemanager and keep other fields intact") {
        val handler = servicesWrapper.lnReqRESTHandlerSpyk
        val db = servicesWrapper.fakeNlReqDbSpyk
        db.insertNlBehov(defaultRequirement)

        val id = defaultRequirement.id!!
        val principal = OrganisasjonPrincipal(
            ident = "0192:${arbeidsforholdManagerAareg.first}",
            token = createMockToken(
                ident = "0192:${arbeidsforholdManagerAareg.first}",
            )
        )

        val updatedLm = LinemanagerRequirementUpdate(
            manager = defaultManager,
        )

        handler.handleUpdatedRequirement(
            requirementId = id,
            linemanagerUpdate = updatedLm,
            principal = principal,
        )

        coVerify(exactly = 1) {
            servicesWrapper.narmestelederServiceSpyk.updateNlBehov(match {
                it.manager.nationalIdentificationNumber == defaultManager.nationalIdentificationNumber &&
                        it.manager.nationalIdentificationNumber != defaultRequirement.narmestelederFnr
            }, match { it == id }, match { it == BehovStatus.PENDING })
        }
        coVerify(exactly = 1) {
            servicesWrapper.fakeNlReqDbSpyk.updateNlBehov(match {
                it.narmestelederFnr == defaultManager.nationalIdentificationNumber &&
                        it.id == defaultRequirement.id &&
                        it.behovStatus == BehovStatus.PENDING &&
                        it.orgnummer == defaultRequirement.orgnummer &&
                        it.hovedenhetOrgnummer == defaultRequirement.hovedenhetOrgnummer &&
                        it.sykmeldtFnr == defaultRequirement.sykmeldtFnr &&
                        it.leesahStatus == defaultRequirement.leesahStatus
            })
        }
    }
})
