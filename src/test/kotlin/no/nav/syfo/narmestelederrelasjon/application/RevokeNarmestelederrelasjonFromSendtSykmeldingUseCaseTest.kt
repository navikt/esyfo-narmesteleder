package no.nav.syfo.narmestelederrelasjon.application

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonIdent

class RevokeNarmestelederrelasjonFromSendtSykmeldingUseCaseTest :
    FunSpec({
        test("publishes the revocation with the employee as sendt sykmelding initiator") {
            val published = mutableListOf<PublishNarmestelederrelasjonRevocationCommand>()
            val useCase = RevokeNarmestelederrelasjonFromSendtSykmeldingUseCase { published += it }
            val employee = PersonIdent("12345678901")
            val organization = OrganizationNumber("123456789")

            useCase.execute(RevokeNarmestelederrelasjonFromSendtSykmeldingCommand(employee, organization))

            published shouldBe listOf(
                PublishNarmestelederrelasjonRevocationCommand(
                    employeeIdent = employee,
                    organizationNumber = organization,
                    initiator = RevocationInitiator.EMPLOYEE_SENDT_SYKMELDING,
                ),
            )
        }
    })
