package no.nav.syfo.narmesteleder.service

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.CapturingSlot
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import java.util.*
import no.nav.syfo.aareg.AaregService
import no.nav.syfo.dinesykmeldte.DinesykmeldteService
import no.nav.syfo.narmesteleder.db.INarmestelederDb
import no.nav.syfo.narmesteleder.db.NarmestelederBehovEntity
import no.nav.syfo.narmesteleder.domain.BehovStatus
import no.nav.syfo.narmesteleder.domain.LinemanagerRequirementWrite
import no.nav.syfo.narmesteleder.domain.Manager
import no.nav.syfo.narmesteleder.exception.HovedenhetNotFoundException
import no.nav.syfo.narmesteleder.exception.LinemanagerRequirementNotFoundException
import no.nav.syfo.pdl.PdlService
import no.nav.syfo.pdl.Person
import no.nav.syfo.pdl.client.Navn

class NarmestelederServiceTest : DescribeSpec({
    val nlDb = mockk<INarmestelederDb>(relaxed = true)
    val aaregService = mockk<AaregService>()
    val pdlService = mockk<PdlService>()
    val dinesykmeldteService = mockk<DinesykmeldteService>()

    beforeTest {
        clearMocks(nlDb, aaregService, pdlService)
    }

    fun service(persist: Boolean = true) = NarmestelederService(
        nlDb = nlDb,
        persistLeesahNlBehov = persist,
        aaregService = aaregService,
        pdlService = pdlService,
        dinesykmeldteService = dinesykmeldteService,
    )

    val defaultManager = Manager(
        nationalIdentificationNumber = "01999999999",
        firstName = "Ola",
        lastName = "Nordmann",
        mobile = "99999999",
        email = "manager@epost.no"
    )

    describe("createNewNlBehov") {
        it("persists entity with resolved hovedenhet") {
            // Arrange
            val sykmeldtFnr = "12345678910"
            val underenhetOrg = "123456789"
            val hovedenhetOrg = "987654321"
            val write = LinemanagerRequirementWrite(
                employeeIdentificationNumber = sykmeldtFnr,
                orgNumber = underenhetOrg,
                managerIdentificationNumber = "01987654321",
                leesahStatus = "ACTIVE",
                revokedLinemanagerId = UUID.randomUUID(),
            )
            val captured: CapturingSlot<NarmestelederBehovEntity> = slot()

            coEvery { aaregService.findOrgNumbersByPersonIdent(sykmeldtFnr) } returns mapOf(underenhetOrg to hovedenhetOrg)
            coEvery { nlDb.insertNlBehov(capture(captured)) } answers { UUID.randomUUID() }
            coEvery {
                dinesykmeldteService.getIsActiveSykmelding(
                    eq(write.employeeIdentificationNumber), eq(write.orgNumber)
                )
            } returns true

            // Act
            service().createNewNlBehov(write)

            // Assert
            coVerify(exactly = 1) { nlDb.insertNlBehov(any()) }
            coVerify(exactly = 1) { aaregService.findOrgNumbersByPersonIdent(eq(write.employeeIdentificationNumber)) }

            captured.isCaptured shouldBe true
            val entity = captured.captured
            entity.sykmeldtFnr shouldBe sykmeldtFnr
            entity.orgnummer shouldBe underenhetOrg
            entity.hovedenhetOrgnummer shouldBe hovedenhetOrg
            entity.narmestelederFnr shouldBe write.managerIdentificationNumber
            entity.leesahStatus shouldBe write.leesahStatus
            entity.behovStatus shouldBe BehovStatus.BEHOV_CREATED
        }

        it("skips persistence when flag is false") {
            // Arrange
            val sykmeldtFnr = "12345678910"
            val underenhetOrg = "123456789"
            val write = LinemanagerRequirementWrite(
                employeeIdentificationNumber = sykmeldtFnr,
                orgNumber = underenhetOrg,
                managerIdentificationNumber = "01987654321",
                leesahStatus = "ACTIVE",
                revokedLinemanagerId = UUID.randomUUID(),
            )

            coEvery { nlDb.insertNlBehov(any()) } throws AssertionError("insertNlBehov should not be called when persistLeesahNlBehov=false")
            coEvery { aaregService.findOrgNumbersByPersonIdent(any()) } throws AssertionError("AaregService should not be called when persistLeesahNlBehov=false")

            // Act
            service(persist = false).createNewNlBehov(write)

            // Assert
            coVerify(exactly = 0) { nlDb.insertNlBehov(any()) }
            coVerify(exactly = 0) { aaregService.findOrgNumbersByPersonIdent(any()) }
        }

        it("throws when hovedenhet missing for underenhet") {
            // Arrange
            val sykmeldtFnr = "12345678910"
            val underenhetOrg = "123456789"
            val write = LinemanagerRequirementWrite(
                employeeIdentificationNumber = sykmeldtFnr,
                orgNumber = underenhetOrg,
                managerIdentificationNumber = "01987654321",
                leesahStatus = "ACTIVE",
                revokedLinemanagerId = UUID.randomUUID(),
            )
            coEvery { aaregService.findOrgNumbersByPersonIdent(sykmeldtFnr) } returns emptyMap()
            coEvery {
                dinesykmeldteService.getIsActiveSykmelding(
                    eq(write.employeeIdentificationNumber), eq(write.orgNumber)
                )
            } returns true

            // Act
            shouldThrow<HovedenhetNotFoundException> { service().createNewNlBehov(write) }

            // Assert
            coVerify(exactly = 0) { nlDb.insertNlBehov(any()) }
            coVerify(exactly = 1) { aaregService.findOrgNumbersByPersonIdent(eq(write.employeeIdentificationNumber)) }
        }

        it("should skip persists if active sykmelding is missing") {
            // Arrange
            val sykmeldtFnr = "12345678910"
            val underenhetOrg = "123456789"
            val write = LinemanagerRequirementWrite(
                employeeIdentificationNumber = sykmeldtFnr,
                orgNumber = underenhetOrg,
                managerIdentificationNumber = "01987654321",
                leesahStatus = "ACTIVE",
                revokedLinemanagerId = UUID.randomUUID(),
            )

            coEvery {
                dinesykmeldteService.getIsActiveSykmelding(
                    eq(write.employeeIdentificationNumber), eq(write.orgNumber)
                )
            } returns false

            // Act
            service().createNewNlBehov(write)

            // Assert
            coVerify(exactly = 0) { nlDb.insertNlBehov(any()) }
            coVerify(exactly = 0) { aaregService.findOrgNumbersByPersonIdent(any()) }
        }
    }

    describe("getLinemanagerRequirementReadById") {
        it("returns mapped read DTO with name from database") {
            // Arrange
            val id = UUID.randomUUID()
            val entity = NarmestelederBehovEntity(
                id = id,
                orgnummer = "123456789",
                hovedenhetOrgnummer = "987654321",
                sykmeldtFnr = "12345678910",
                narmestelederFnr = "01987654321",
                leesahStatus = "ACTIVE",
                behovStatus = BehovStatus.BEHOV_CREATED,
                avbruttNarmesteLederId = UUID.randomUUID(),
                fornavn = "Kari",
                mellomnavn = null,
                etternavn = "Nordmann",
            )
            coEvery { nlDb.findBehovById(id) } returns entity
            coVerify(exactly = 0) { pdlService.getPersonFor(any()) }
            val read = service().getLinemanagerRequirementReadById(id)
            read.id shouldBe id
            read.orgNumber shouldBe entity.orgnummer
            read.mainOrgNumber shouldBe entity.hovedenhetOrgnummer
            read.employeeIdentificationNumber shouldBe entity.sykmeldtFnr
            read.managerIdentificationNumber shouldBe entity.narmestelederFnr
            read.name.firstName shouldBe entity.fornavn
            read.name.middleName shouldBe entity.mellomnavn
            read.name.lastName shouldBe entity.etternavn
        }

        it("throws when missing") {
            // Arrange
            val id = UUID.randomUUID()
            coEvery { nlDb.findBehovById(id) } returns null

            // Act + Assert
            shouldThrow<LinemanagerRequirementNotFoundException> { service().getLinemanagerRequirementReadById(id) }
        }

        it("returns mapped read DTO with name from PDL when empty name in entity") {
            // Arrange
            val id = UUID.randomUUID()
            val entity = NarmestelederBehovEntity(
                id = id,
                orgnummer = "123456789",
                hovedenhetOrgnummer = "987654321",
                sykmeldtFnr = "12345678910",
                narmestelederFnr = "01987654321",
                leesahStatus = "ACTIVE",
                behovStatus = BehovStatus.BEHOV_CREATED,
                avbruttNarmesteLederId = UUID.randomUUID(),
            )
            val navn = Navn(fornavn = "Ola", mellomnavn = null, etternavn = "Nordmann")
            coEvery { nlDb.findBehovById(id) } returns entity
            coEvery { pdlService.getPersonFor(entity.sykmeldtFnr) } returns Person(
                name = navn, nationalIdentificationNumber = entity.sykmeldtFnr
            )
            // Act
            val read = service().getLinemanagerRequirementReadById(id)

            // Assert
            coVerify(exactly = 1) { pdlService.getPersonFor(eq(entity.sykmeldtFnr)) }
            coVerify(exactly = 1) { nlDb.updateNlBehov(any()) }
            read.id shouldBe id
            read.orgNumber shouldBe entity.orgnummer
            read.mainOrgNumber shouldBe entity.hovedenhetOrgnummer
            read.employeeIdentificationNumber shouldBe entity.sykmeldtFnr
            read.managerIdentificationNumber shouldBe entity.narmestelederFnr
            read.name.firstName shouldBe navn.fornavn
            read.name.lastName shouldBe navn.etternavn
            read.name.middleName shouldBe navn.mellomnavn
        }

        it("throws when missing") {
            // Arrange
            val id = UUID.randomUUID()
            coEvery { nlDb.findBehovById(id) } returns null

            // Act + Assert
            shouldThrow<LinemanagerRequirementNotFoundException> { service().getLinemanagerRequirementReadById(id) }
        }
    }

    describe("updateNlBehov") {
        it("updates entity") {
            // Arrange
            val id = UUID.randomUUID()
            val original = NarmestelederBehovEntity(
                id = id,
                orgnummer = "111111111",
                hovedenhetOrgnummer = "222222222",
                sykmeldtFnr = "12345678910",
                narmestelederFnr = "01987654321",
                leesahStatus = "ACTIVE",
                avbruttNarmesteLederId = UUID.randomUUID(),
                behovStatus = BehovStatus.BEHOV_CREATED,
            )

            coEvery { nlDb.findBehovById(id) } returns original
            coEvery { nlDb.updateNlBehov(any()) } returns Unit

            // Act
            service().updateNlBehov(defaultManager, original.id!!, BehovStatus.BEHOV_FULFILLED)
            coVerify(exactly = 1) {
                nlDb.updateNlBehov(any())
            }
        }

        it("retains everything except manager social security number and status") {
            // Arrange
            val id = UUID.randomUUID()
            val original = NarmestelederBehovEntity(
                id = id,
                orgnummer = "111111111",
                hovedenhetOrgnummer = "222222222",
                sykmeldtFnr = "12345678910",
                narmestelederFnr = "01987654321",
                leesahStatus = "ACTIVE",
                behovStatus = BehovStatus.BEHOV_CREATED,
                avbruttNarmesteLederId = UUID.randomUUID(),
            )

            coEvery { nlDb.findBehovById(id) } returns original
            coEvery { nlDb.updateNlBehov(any()) } returns Unit

            // Act
            service().updateNlBehov(defaultManager, original.id!!, BehovStatus.BEHOV_FULFILLED)

            // Assert
            coVerify {
                nlDb.updateNlBehov(match { updated ->
                    updated.id == id
                            && updated.orgnummer == original.orgnummer
                            && updated.hovedenhetOrgnummer == original.hovedenhetOrgnummer
                            && updated.sykmeldtFnr == original.sykmeldtFnr
                            && updated.narmestelederFnr == defaultManager.nationalIdentificationNumber
                            && updated.behovStatus == BehovStatus.BEHOV_FULFILLED
                })
            }
        }

        it("throws when behov not found") {
            // Arrange
            val id = UUID.randomUUID()

            coEvery { nlDb.findBehovById(id) } returns null
            // Act + Assert
            shouldThrow<LinemanagerRequirementNotFoundException> {
                service().updateNlBehov(
                    defaultManager, id, BehovStatus.BEHOV_FULFILLED
                )
            }
        }
    }
})
