package no.nav.syfo.narmesteleder.service

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.CapturingSlot
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import java.util.*
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import no.nav.syfo.aareg.AaregService
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

class NarmestelederServiceTest : FunSpec({
    val dispatcher = StandardTestDispatcher()
    val nlDb = mockk<INarmestelederDb>(relaxed = true)
    val aaregService = mockk<AaregService>()
    val pdlService = mockk<PdlService>()

    beforeTest {
        clearMocks(nlDb, aaregService, pdlService)
    }

    fun service(persist: Boolean = true) = NarmestelederService(
        nlDb = nlDb,
        persistLeesahNlBehov = persist,
        aaregService = aaregService,
        pdlService = pdlService
    )

    test("createNewNlBehov persists entity with resolved hovedenhet") {
        runTest(dispatcher) {
            val sykmeldtFnr = "12345678910"
            val underenhetOrg = "123456789"
            val hovedenhetOrg = "987654321"
            val write = LinemanagerRequirementWrite(
                employeeIdentificationNumber = sykmeldtFnr,
                orgnumber = underenhetOrg,
                managerIdentificationNumber = "01987654321",
                leesahStatus = "ACTIVE",
            )
            val captured: CapturingSlot<NarmestelederBehovEntity> = slot()

            coEvery { aaregService.findOrgNumbersByPersonIdent(sykmeldtFnr) } returns mapOf(underenhetOrg to hovedenhetOrg)
            coEvery { nlDb.insertNlBehov(capture(captured)) } answers { UUID.randomUUID() }

            service().createNewNlBehov(write)

            captured.isCaptured shouldBe true
            val entity = captured.captured
            entity.sykmeldtFnr shouldBe sykmeldtFnr
            entity.orgnummer shouldBe underenhetOrg
            entity.hovedenhetOrgnummer shouldBe hovedenhetOrg
            entity.narmestelederFnr shouldBe write.managerIdentificationNumber
            entity.leesahStatus shouldBe write.leesahStatus
            entity.behovStatus shouldBe BehovStatus.RECEIVED
        }
    }

    test("createNewNlBehov skips persistence when flag is false") {
        runTest(dispatcher) {
            val sykmeldtFnr = "12345678910"
            val underenhetOrg = "123456789"
            val write = LinemanagerRequirementWrite(
                employeeIdentificationNumber = sykmeldtFnr,
                orgnumber = underenhetOrg,
                managerIdentificationNumber = "01987654321",
                leesahStatus = "ACTIVE",
            )

            coEvery { nlDb.insertNlBehov(any()) } throws AssertionError("insertNlBehov should not be called when persistLeesahNlBehov=false")
            coEvery { aaregService.findOrgNumbersByPersonIdent(any()) } throws AssertionError("AaregService should not be called when persistLeesahNlBehov=false")

            service(persist = false).createNewNlBehov(write)
        }
    }

    test("createNewNlBehov throws when hovedenhet missing for underenhet") {
        runTest(dispatcher) {
            val sykmeldtFnr = "12345678910"
            val underenhetOrg = "123456789"
            val write = LinemanagerRequirementWrite(
                employeeIdentificationNumber = sykmeldtFnr,
                orgnumber = underenhetOrg,
                managerIdentificationNumber = "01987654321",
                leesahStatus = "ACTIVE",
            )
            coEvery { aaregService.findOrgNumbersByPersonIdent(sykmeldtFnr) } returns emptyMap()

            shouldThrow<HovedenhetNotFoundException> { service().createNewNlBehov(write) }
        }
    }

    test("getNlBehovById returns mapped read DTO with name") {
        runTest(dispatcher) {
            val id = UUID.randomUUID()
            val entity = NarmestelederBehovEntity(
                id = id,
                orgnummer = "123456789",
                hovedenhetOrgnummer = "987654321",
                sykmeldtFnr = "12345678910",
                narmestelederFnr = "01987654321",
                leesahStatus = "ACTIVE",
                behovStatus = BehovStatus.RECEIVED,
            )
            val navn = Navn(fornavn = "Ola", mellomnavn = null, etternavn = "Nordmann")
            coEvery { nlDb.findBehovById(id) } returns entity
            coEvery { pdlService.getPersonFor(entity.sykmeldtFnr) } returns Person(
                name = navn,
                nationalIdentificationNumber = entity.sykmeldtFnr
            )

            val read = service().getNlBehovById(id)
            read.id shouldBe id
            read.orgnumber shouldBe entity.orgnummer
            read.mainOrgnumber shouldBe entity.hovedenhetOrgnummer
            read.employeeIdentificationNumber shouldBe entity.sykmeldtFnr
            read.managerIdentificationNumber shouldBe entity.narmestelederFnr
            read.name.firstName shouldBe navn.fornavn
            read.name.lastName shouldBe navn.etternavn
            read.name.middleName shouldBe navn.mellomnavn
        }
    }

    test("getNlBehovById throws when missing") {
        runTest(dispatcher) {
            val id = UUID.randomUUID()
            coEvery { nlDb.findBehovById(id) } returns null
            shouldThrow<LinemanagerRequirementNotFoundException> { service().getNlBehovById(id) }
        }
    }

    val defaultManager = Manager(
        nationalIdentificationNumber = "01999999999",
        firstName = "Ola",
        lastName = "Nordmann",
        mobile = "99999999",
        email = "manager@epost.no"
    )

    test("updateNlBehov updates entity") {
        runTest(dispatcher) {
            val id = UUID.randomUUID()
            val original = NarmestelederBehovEntity(
                id = id,
                orgnummer = "111111111",
                hovedenhetOrgnummer = "222222222",
                sykmeldtFnr = "12345678910",
                narmestelederFnr = "01987654321",
                leesahStatus = "ACTIVE",
                behovStatus = BehovStatus.RECEIVED,
            )

            coEvery { nlDb.findBehovById(id) } returns original
            coEvery { nlDb.updateNlBehov(any()) } returns Unit

            service().updateNlBehov(defaultManager, original.id!!, BehovStatus.COMPLETED) { employee ->
                employee.nationalIdentificationNumber shouldBe original.sykmeldtFnr
                employee.orgnumber shouldBe original.orgnummer
            }

            coVerify(exactly = 1) {
                nlDb.updateNlBehov(any())
            }
        }
    }

    test("updateNlBehov retains everything except manager social security number and status") {
        runTest(dispatcher) {
            val id = UUID.randomUUID()
            val original = NarmestelederBehovEntity(
                id = id,
                orgnummer = "111111111",
                hovedenhetOrgnummer = "222222222",
                sykmeldtFnr = "12345678910",
                narmestelederFnr = "01987654321",
                leesahStatus = "ACTIVE",
                behovStatus = BehovStatus.RECEIVED,
            )

            coEvery { nlDb.findBehovById(id) } returns original
            coEvery { nlDb.updateNlBehov(any()) } returns Unit

            service().updateNlBehov(defaultManager, original.id!!, BehovStatus.COMPLETED) { employee ->
                employee.nationalIdentificationNumber shouldBe original.sykmeldtFnr
                employee.orgnumber shouldBe original.orgnummer
            }

            coVerify {
                nlDb.updateNlBehov(match { updated ->
                    updated.id == id &&
                            updated.orgnummer == original.orgnummer &&
                            updated.hovedenhetOrgnummer == original.hovedenhetOrgnummer &&
                            updated.sykmeldtFnr == original.sykmeldtFnr &&
                            updated.narmestelederFnr == defaultManager.nationalIdentificationNumber &&
                            updated.behovStatus == BehovStatus.COMPLETED
                })
            }
        }
    }

    test("updateNlBehov throws when behov not found") {
        runTest(dispatcher) {
            val id = UUID.randomUUID()

            coEvery { nlDb.findBehovById(id) } returns null
            shouldThrow<LinemanagerRequirementNotFoundException> {
                service().updateNlBehov(
                    defaultManager,
                    id,
                    BehovStatus.ERROR
                ) { /* no-op */ }
            }
        }
    }
})
