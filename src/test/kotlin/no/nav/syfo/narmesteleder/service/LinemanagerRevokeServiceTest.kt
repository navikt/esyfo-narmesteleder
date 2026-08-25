package no.nav.syfo.narmesteleder.service

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.verify
import no.nav.syfo.application.auth.SystemPrincipal
import no.nav.syfo.application.auth.UserPrincipal
import no.nav.syfo.application.exception.ApiErrorException
import no.nav.syfo.narmesteleder.db.INarmestelederRevokeDb
import no.nav.syfo.narmesteleder.db.RevokableNarmestelederEntity
import no.nav.syfo.narmesteleder.domain.OrganizationNumber
import no.nav.syfo.narmesteleder.domain.PersonalIdentificationNumber
import no.nav.syfo.narmesteleder.domain.RevokedBy
import no.nav.syfo.narmesteleder.kafka.ISykmeldingNLKafkaProducer
import no.nav.syfo.narmesteleder.kafka.model.NlResponseSource
import java.util.UUID

class LinemanagerRevokeServiceTest :
    DescribeSpec({
        val revokeDb = mockk<INarmestelederRevokeDb>()
        val kafkaProducer = mockk<ISykmeldingNLKafkaProducer>(relaxed = true)
        val service = LinemanagerRevokeService(revokeDb, NarmestelederKafkaService(kafkaProducer))

        val narmestelederId = UUID.randomUUID()
        val employee = PersonalIdentificationNumber("12345678901")
        val manager = PersonalIdentificationNumber("10987654321")
        val orgNumber = OrganizationNumber("123456789")
        val context = "test"

        fun relation(isActive: Boolean = true) = RevokableNarmestelederEntity(
            narmestelederId = narmestelederId,
            employeeIdentificationNumber = employee,
            managerIdentificationNumber = manager,
            orgNumber = orgNumber,
            isActive = isActive,
        )

        beforeTest {
            clearMocks(revokeDb, kafkaProducer)
        }

        describe("revoke") {
            it("publishes an ARBEIDSTAGER_REVOKE message when the employee revokes") {
                coEvery { revokeDb.findByNarmestelederId(narmestelederId) } returns relation()

                val outcome = service.revoke(narmestelederId, UserPrincipal(employee.value, "token"), context)

                outcome shouldBe RevokeOutcome.Revoked(RevokedBy.EMPLOYEE)
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

                outcome shouldBe RevokeOutcome.Revoked(RevokedBy.LINEMANAGER)
                verify(exactly = 1) {
                    kafkaProducer.sendSykmldingNLBrudd(
                        match { it.sykmeldtFnr == employee.value && it.orgnummer == orgNumber.value },
                        NlResponseSource.NARMESTELEDER_REVOKE,
                    )
                }
            }

            it("is idempotent and does not republish when the relation is already revoked") {
                coEvery { revokeDb.findByNarmestelederId(narmestelederId) } returns relation(isActive = false)

                val outcome = service.revoke(narmestelederId, UserPrincipal(employee.value, "token"), context)

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

            it("throws NotFoundException when the person is not part of the relation") {
                coEvery { revokeDb.findByNarmestelederId(narmestelederId) } returns relation()

                shouldThrow<ApiErrorException.NotFoundException> {
                    service.revoke(narmestelederId, UserPrincipal("11111111111", "token"), context)
                }
                verify(exactly = 0) { kafkaProducer.sendSykmldingNLBrudd(any(), any()) }
            }

            it("throws ForbiddenException for a system principal without looking up the relation") {
                shouldThrow<ApiErrorException.ForbiddenException> {
                    service.revoke(narmestelederId, SystemPrincipal("orgnummer", "token", "owner", "userId"), context)
                }
                verify(exactly = 0) { kafkaProducer.sendSykmldingNLBrudd(any(), any()) }
            }
        }
    })
