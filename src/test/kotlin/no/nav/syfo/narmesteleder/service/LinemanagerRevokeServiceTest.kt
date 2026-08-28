package no.nav.syfo.narmesteleder.service

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import no.nav.syfo.application.auth.SystemPrincipal
import no.nav.syfo.application.auth.UserPrincipal
import no.nav.syfo.application.exception.ApiErrorException
import no.nav.syfo.narmesteleder.db.INarmestelederRevokeDb
import no.nav.syfo.narmesteleder.db.RevokableNarmestelederEntity
import no.nav.syfo.narmesteleder.domain.OrganizationNumber
import no.nav.syfo.narmesteleder.domain.PersonalIdentificationNumber
import no.nav.syfo.narmesteleder.domain.RevokeInitiator
import no.nav.syfo.narmesteleder.kafka.ISykmeldingNLKafkaProducer
import no.nav.syfo.narmesteleder.kafka.model.NlResponseSource
import java.util.UUID

class LinemanagerRevokeServiceTest :
    DescribeSpec({
        val revokeDb = mockk<INarmestelederRevokeDb>()
        val kafkaProducer = mockk<ISykmeldingNLKafkaProducer>(relaxed = true)
        val validationService = mockk<ValidationService>()
        val service = LinemanagerRevokeService(
            narmestelederRevokeDb = revokeDb,
            narmestelederKafkaService = NarmestelederKafkaService(kafkaProducer),
            validationService = validationService,
        )

        val narmestelederId = UUID.randomUUID()
        val employee = PersonalIdentificationNumber("12345678901")
        val manager = PersonalIdentificationNumber("10987654321")
        val orgNumber = OrganizationNumber("123456789")
        val outsider = UserPrincipal("11111111111", "token")
        val systemUser = SystemPrincipal("123456789", "token", "owner", "userId")
        val context = "test"

        fun relation(isActive: Boolean = true) = RevokableNarmestelederEntity(
            narmestelederId = narmestelederId,
            employeeIdentificationNumber = employee,
            managerIdentificationNumber = manager,
            orgNumber = orgNumber,
            isActive = isActive,
        )

        fun grantAltinnAccess() {
            coEvery { validationService.validatePrincipalAccessToOrgnumber(any(), orgNumber) } returns "Org AS"
        }

        fun denyAltinnAccess() {
            coEvery {
                validationService.validatePrincipalAccessToOrgnumber(any(), orgNumber)
            } throws ApiErrorException.ForbiddenException("no access")
        }

        beforeTest {
            clearMocks(revokeDb, kafkaProducer, validationService)
        }

        describe("revoke as a party in the relation") {
            it("publishes an ARBEIDSTAGER_REVOKE message when the employee revokes") {
                coEvery { revokeDb.findByNarmestelederId(narmestelederId) } returns relation()

                val outcome = service.revoke(narmestelederId, UserPrincipal(employee.value, "token"), context)

                outcome shouldBe RevokeOutcome.Revoked(RevokeInitiator.EMPLOYEE)
                verify(exactly = 1) {
                    kafkaProducer.sendSykmldingNLBrudd(
                        match { it.sykmeldtFnr == employee.value && it.orgnummer == orgNumber.value },
                        NlResponseSource.ARBEIDSTAGER_REVOKE,
                    )
                }
            }

            it("publishes a NARMESTELEDER_REVOKE message when the line manager revokes") {
                coEvery { revokeDb.findByNarmestelederId(narmestelederId) } returns relation()

                val outcome = service.revoke(narmestelederId, UserPrincipal(manager.value, "token"), context)

                outcome shouldBe RevokeOutcome.Revoked(RevokeInitiator.LINEMANAGER)
                verify(exactly = 1) {
                    kafkaProducer.sendSykmldingNLBrudd(
                        match { it.sykmeldtFnr == employee.value && it.orgnummer == orgNumber.value },
                        NlResponseSource.NARMESTELEDER_REVOKE,
                    )
                }
            }

            it("does not run the Altinn check for a party in the relation") {
                coEvery { revokeDb.findByNarmestelederId(narmestelederId) } returns relation()

                service.revoke(narmestelederId, UserPrincipal(employee.value, "token"), context)

                coVerify(exactly = 0) { validationService.validatePrincipalAccessToOrgnumber(any(), orgNumber) }
            }
        }

        describe("revoke on behalf of the employer") {
            it("publishes a PERSONALLEDER_REVOKE message when a person with Altinn access revokes") {
                coEvery { revokeDb.findByNarmestelederId(narmestelederId) } returns relation()
                grantAltinnAccess()

                val outcome = service.revoke(narmestelederId, outsider, context)

                outcome shouldBe RevokeOutcome.Revoked(RevokeInitiator.PERSONNEL_MANAGER)
                verify(exactly = 1) {
                    kafkaProducer.sendSykmldingNLBrudd(
                        match { it.sykmeldtFnr == employee.value && it.orgnummer == orgNumber.value },
                        NlResponseSource.PERSONALLEDER_REVOKE,
                    )
                }
            }

            it("publishes an LPS_REVOKE message when a system user with Altinn access revokes") {
                coEvery { revokeDb.findByNarmestelederId(narmestelederId) } returns relation()
                grantAltinnAccess()

                val outcome = service.revoke(narmestelederId, systemUser, context)

                outcome shouldBe RevokeOutcome.Revoked(RevokeInitiator.LPS)
                verify(exactly = 1) {
                    kafkaProducer.sendSykmldingNLBrudd(
                        match { it.sykmeldtFnr == employee.value && it.orgnummer == orgNumber.value },
                        NlResponseSource.LPS_REVOKE,
                    )
                }
            }

            it("checks the Altinn access against the organization of the relation") {
                coEvery { revokeDb.findByNarmestelederId(narmestelederId) } returns relation()
                grantAltinnAccess()

                service.revoke(narmestelederId, outsider, context)

                coVerify(exactly = 1) { validationService.validatePrincipalAccessToOrgnumber(outsider, orgNumber) }
            }

            it("throws NotFoundException when a person lacks Altinn access") {
                coEvery { revokeDb.findByNarmestelederId(narmestelederId) } returns relation()
                denyAltinnAccess()

                shouldThrow<ApiErrorException.NotFoundException> {
                    service.revoke(narmestelederId, outsider, context)
                }
                verify(exactly = 0) { kafkaProducer.sendSykmldingNLBrudd(any(), any()) }
            }

            it("throws NotFoundException when a system user lacks Altinn access") {
                coEvery { revokeDb.findByNarmestelederId(narmestelederId) } returns relation()
                denyAltinnAccess()

                shouldThrow<ApiErrorException.NotFoundException> {
                    service.revoke(narmestelederId, systemUser, context)
                }
                verify(exactly = 0) { kafkaProducer.sendSykmldingNLBrudd(any(), any()) }
            }
        }

        describe("revoke of an unknown or already revoked relation") {
            it("is idempotent and does not republish when the relation is already revoked") {
                coEvery { revokeDb.findByNarmestelederId(narmestelederId) } returns relation(isActive = false)

                val outcome = service.revoke(narmestelederId, UserPrincipal(employee.value, "token"), context)

                outcome shouldBe RevokeOutcome.AlreadyRevoked
                verify(exactly = 0) { kafkaProducer.sendSykmldingNLBrudd(any(), any()) }
            }

            it("is idempotent for a caller acting on behalf of the employer") {
                coEvery { revokeDb.findByNarmestelederId(narmestelederId) } returns relation(isActive = false)
                grantAltinnAccess()

                val outcome = service.revoke(narmestelederId, systemUser, context)

                outcome shouldBe RevokeOutcome.AlreadyRevoked
                verify(exactly = 0) { kafkaProducer.sendSykmldingNLBrudd(any(), any()) }
            }

            it("throws NotFoundException when the relation does not exist") {
                coEvery { revokeDb.findByNarmestelederId(narmestelederId) } returns null

                shouldThrow<ApiErrorException.NotFoundException> {
                    service.revoke(narmestelederId, UserPrincipal(employee.value, "token"), context)
                }
                verify(exactly = 0) { kafkaProducer.sendSykmldingNLBrudd(any(), any()) }
            }

            it("does not run the Altinn check when the relation does not exist") {
                coEvery { revokeDb.findByNarmestelederId(narmestelederId) } returns null

                shouldThrow<ApiErrorException.NotFoundException> {
                    service.revoke(narmestelederId, outsider, context)
                }
                coVerify(exactly = 0) { validationService.validatePrincipalAccessToOrgnumber(any(), orgNumber) }
            }
        }
    })
