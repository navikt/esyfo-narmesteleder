package no.nav.syfo.narmesteleder.service

import io.kotest.assertions.throwables.shouldNotThrow
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.equality.shouldBeEqualUsingFields
import io.kotest.matchers.shouldBe
import io.mockk.CapturingSlot
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import java.util.*
import net.bytebuddy.description.type.TypeDefinition.Sort.describe
import no.nav.syfo.aareg.AaregService
import no.nav.syfo.aareg.Arbeidsforhold
import no.nav.syfo.aareg.client.ArbeidsstedType
import no.nav.syfo.aareg.client.OpplysningspliktigType
import no.nav.syfo.application.database.ResultPage
import no.nav.syfo.dinesykmeldte.DinesykmeldteService
import no.nav.syfo.narmesteleder.db.INarmestelederDb
import no.nav.syfo.narmesteleder.db.NarmestelederBehovEntity
import no.nav.syfo.narmesteleder.domain.BehovReason
import no.nav.syfo.narmesteleder.domain.BehovStatus
import no.nav.syfo.narmesteleder.domain.LinemanagerRequirementWrite
import no.nav.syfo.narmesteleder.exception.HovedenhetNotFoundException
import no.nav.syfo.narmesteleder.exception.LinemanagerRequirementNotFoundException
import no.nav.syfo.pdl.PdlService
import no.nav.syfo.pdl.Person
import no.nav.syfo.pdl.client.Navn
import no.nav.syfo.sykmelding.model.Arbeidsgiver

class NarmestelederServiceTest :
    DescribeSpec({
        val nlDb = mockk<INarmestelederDb>(relaxed = true)
        val aaregService = mockk<AaregService>()
        val pdlService = mockk<PdlService>()
        val dinesykmeldteService = mockk<DinesykmeldteService>()

        beforeTest {
            clearMocks(nlDb, aaregService, pdlService, dinesykmeldteService)
        }

        fun service(persist: Boolean = true) = NarmestelederService(
            nlDb = nlDb,
            persistLeesahNlBehov = persist,
            aaregService = aaregService,
            pdlService = pdlService,
            dinesykmeldteService = dinesykmeldteService,
            dialogportenService = mockk(relaxed = true)
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
                    behovReason = BehovReason.DEAKTIVERT_LEDER,
                    revokedLinemanagerId = UUID.randomUUID(),
                )
                val captured: CapturingSlot<NarmestelederBehovEntity> = slot()

                coEvery { aaregService.findArbeidsforholdByPersonIdent(sykmeldtFnr) } returns listOf(
                    Arbeidsforhold(
                        underenhetOrg,
                        ArbeidsstedType.Underenhet,
                        opplysningspliktigOrgnummer = hovedenhetOrg,
                        OpplysningspliktigType.Hovedenhet
                    )
                )
                coEvery { nlDb.insertNlBehov(capture(captured)) } answers {
                    NarmestelederBehovEntity.fromLinemanagerRequirementWrite(
                        write,
                        hovedenhetOrg,
                        BehovStatus.BEHOV_CREATED
                    )
                        .copy(id = UUID.randomUUID())
                }
                coEvery {
                    dinesykmeldteService.getIsActiveSykmelding(
                        eq(write.employeeIdentificationNumber),
                        eq(write.orgNumber)
                    )
                } returns true

                // Act
                service().createNewNlBehov(
                    write,
                    behovSource = BehovSource(id = UUID.randomUUID().toString(), source = "test")
                )

                // Assert
                coVerify(exactly = 1) { nlDb.insertNlBehov(any()) }
                coVerify(exactly = 1) { aaregService.findArbeidsforholdByPersonIdent(eq(write.employeeIdentificationNumber)) }

                captured.isCaptured shouldBe true
                val entity = captured.captured
                entity.sykmeldtFnr shouldBe sykmeldtFnr
                entity.orgnummer shouldBe underenhetOrg
                entity.hovedenhetOrgnummer shouldBe hovedenhetOrg
                entity.narmestelederFnr shouldBe write.managerIdentificationNumber
                entity.behovReason shouldBe write.behovReason
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
                    behovReason = BehovReason.DEAKTIVERT_LEDER,
                    revokedLinemanagerId = UUID.randomUUID(),
                )

                coEvery {
                    nlDb.insertNlBehov(any())
                } throws AssertionError(
                    "insertNlBehov should not be called when persistLeesahNlBehov=false"
                )
                coEvery {
                    aaregService.findOrgNumbersByPersonIdent(any())
                } throws AssertionError(
                    "AaregService should not be called when persistLeesahNlBehov=false"
                )

                // Act
                service(persist = false).createNewNlBehov(
                    write,
                    behovSource = BehovSource(id = UUID.randomUUID().toString(), source = "test")
                )

                // Assert
                coVerify(exactly = 0) { nlDb.insertNlBehov(any()) }
                coVerify(exactly = 0) { aaregService.findOrgNumbersByPersonIdent(any()) }
            }

            it("persists with status ARBEIDSFORHOLD_NOT_FOUND when arbeidsforhold missing") {
                // Arrange
                val sykmeldtFnr = "12345678910"
                val underenhetOrg = "123456789"
                val write = LinemanagerRequirementWrite(
                    employeeIdentificationNumber = sykmeldtFnr,
                    orgNumber = underenhetOrg,
                    managerIdentificationNumber = "01987654321",
                    behovReason = BehovReason.DEAKTIVERT_LEDER,
                    revokedLinemanagerId = UUID.randomUUID(),
                )
                coEvery { aaregService.findArbeidsforholdByPersonIdent(sykmeldtFnr) } returns emptyList()
                coEvery {
                    dinesykmeldteService.getIsActiveSykmelding(
                        eq(write.employeeIdentificationNumber),
                        eq(write.orgNumber)
                    )
                } returns true

                // Act
                shouldNotThrow<HovedenhetNotFoundException> {
                    service().createNewNlBehov(
                        write,
                        behovSource = BehovSource(id = UUID.randomUUID().toString(), source = "test")
                    )
                }

                // Assert
                coVerify(exactly = 1) { aaregService.findArbeidsforholdByPersonIdent(eq(write.employeeIdentificationNumber)) }
                coVerify(exactly = 1) {
                    nlDb.insertNlBehov(
                        withArg {
                            it.behovStatus shouldBe BehovStatus.ARBEIDSFORHOLD_NOT_FOUND
                            it.hovedenhetOrgnummer shouldBe "UNKNOWN"
                        }
                    )
                }
            }

            it("persists with status HOVEDENHET_NOT_FOUND when hovedenhet missing for underenhet") {
                // Arrange
                val sykmeldtFnr = "12345678910"
                val underenhetOrg = "123456789"
                val write = LinemanagerRequirementWrite(
                    employeeIdentificationNumber = sykmeldtFnr,
                    orgNumber = underenhetOrg,
                    managerIdentificationNumber = "01987654321",
                    behovReason = BehovReason.DEAKTIVERT_LEDER,
                    revokedLinemanagerId = UUID.randomUUID(),
                )
                coEvery { aaregService.findArbeidsforholdByPersonIdent(sykmeldtFnr) } returns listOf(
                    Arbeidsforhold(
                        underenhetOrg,
                        ArbeidsstedType.Underenhet,
                        opplysningspliktigOrgnummer = null,
                        OpplysningspliktigType.Person
                    )
                )
                coEvery {
                    dinesykmeldteService.getIsActiveSykmelding(
                        eq(write.employeeIdentificationNumber),
                        eq(write.orgNumber)
                    )
                } returns true

                // Act
                shouldNotThrow<HovedenhetNotFoundException> {
                    service().createNewNlBehov(
                        write,
                        behovSource = BehovSource(id = UUID.randomUUID().toString(), source = "test")
                    )
                }

                // Assert
                coVerify(exactly = 1) { aaregService.findArbeidsforholdByPersonIdent(eq(write.employeeIdentificationNumber)) }
                coVerify(exactly = 1) {
                    nlDb.insertNlBehov(
                        withArg {
                            it.behovStatus shouldBe BehovStatus.HOVEDENHET_NOT_FOUND
                            it.hovedenhetOrgnummer shouldBe "UNKNOWN"
                        }
                    )
                }
            }

            it("persists with status HOVEDENHET_NOT_FOUND when juridiskOrgnummer missing for arbeidsgiver from sykmelding") {
                // Arrange
                val sykmeldtFnr = "12345678910"
                val underenhetOrg = "123456789"
                val write = LinemanagerRequirementWrite(
                    employeeIdentificationNumber = sykmeldtFnr,
                    orgNumber = underenhetOrg,
                    managerIdentificationNumber = "01987654321",
                    behovReason = BehovReason.DEAKTIVERT_LEDER,
                    revokedLinemanagerId = UUID.randomUUID(),
                )

                val arbeidsgiver = Arbeidsgiver(
                    orgnummer = underenhetOrg,
                    juridiskOrgnummer = null,
                    orgNavn = "Test AS"
                )

                coEvery {
                    dinesykmeldteService.getIsActiveSykmelding(
                        eq(write.employeeIdentificationNumber),
                        eq(write.orgNumber)
                    )
                } returns true

                // Act
                shouldNotThrow<HovedenhetNotFoundException> {
                    service().createNewNlBehov(
                        write,
                        behovSource = BehovSource(
                            id = UUID.randomUUID().toString(),
                            source = "test"
                        ),
                        arbeidsgiver = arbeidsgiver
                    )
                }

                // Assert
                coVerify(exactly = 0) { aaregService.findArbeidsforholdByPersonIdent(any()) }
                coVerify(exactly = 1) {
                    nlDb.insertNlBehov(
                        withArg {
                            it.behovStatus shouldBe BehovStatus.HOVEDENHET_NOT_FOUND
                            it.hovedenhetOrgnummer shouldBe "UNKNOWN"
                        }
                    )
                }
            }

            it("should skip persists if active sykmelding is missing") {
                // Arrange
                val sykmeldtFnr = "12345678910"
                val underenhetOrg = "123456789"
                val write = LinemanagerRequirementWrite(
                    employeeIdentificationNumber = sykmeldtFnr,
                    orgNumber = underenhetOrg,
                    managerIdentificationNumber = "01987654321",
                    behovReason = BehovReason.DEAKTIVERT_LEDER,
                    revokedLinemanagerId = UUID.randomUUID(),
                )

                coEvery {
                    dinesykmeldteService.getIsActiveSykmelding(
                        eq(write.employeeIdentificationNumber),
                        eq(write.orgNumber)
                    )
                } returns false

                // Act
                service().createNewNlBehov(
                    nlBehov = write,
                    behovSource = BehovSource(id = UUID.randomUUID().toString(), source = "test")
                )

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
                    behovReason = BehovReason.DEAKTIVERT_LEDER,
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
                    behovReason = BehovReason.DEAKTIVERT_LEDER,
                    behovStatus = BehovStatus.BEHOV_CREATED,
                    avbruttNarmesteLederId = UUID.randomUUID(),
                )
                val navn = Navn(fornavn = "Ola", mellomnavn = null, etternavn = "Nordmann")
                coEvery { nlDb.findBehovById(id) } returns entity
                coEvery { pdlService.getPersonFor(entity.sykmeldtFnr) } returns Person(
                    name = navn,
                    nationalIdentificationNumber = entity.sykmeldtFnr
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
                    behovReason = BehovReason.DEAKTIVERT_LEDER,
                    avbruttNarmesteLederId = UUID.randomUUID(),
                    behovStatus = BehovStatus.BEHOV_CREATED,
                )

                coEvery { nlDb.findBehovById(id) } returns original
                coEvery { nlDb.updateNlBehov(any()) } returns Unit

                // Act
                service().updateNlBehov(original.id!!, BehovStatus.BEHOV_FULFILLED)
                coVerify(exactly = 1) {
                    nlDb.updateNlBehov(any())
                }
            }

            it("retains everything except status") {
                // Arrange
                val id = UUID.randomUUID()
                val original = NarmestelederBehovEntity(
                    id = id,
                    orgnummer = "111111111",
                    hovedenhetOrgnummer = "222222222",
                    sykmeldtFnr = "12345678910",
                    narmestelederFnr = "01987654321",
                    behovReason = BehovReason.DEAKTIVERT_LEDER,
                    behovStatus = BehovStatus.BEHOV_CREATED,
                    avbruttNarmesteLederId = UUID.randomUUID(),
                )

                val narmestelederBehovEntitSlot: CapturingSlot<NarmestelederBehovEntity> = slot()
                coEvery { nlDb.findBehovById(id) } returns original
                coEvery { nlDb.updateNlBehov(capture(narmestelederBehovEntitSlot)) } returns Unit

                // Act
                service().updateNlBehov(original.id!!, BehovStatus.BEHOV_FULFILLED)

                // Assert
                narmestelederBehovEntitSlot.captured.shouldBeEqualUsingFields({
                    excludedProperties = setOf(NarmestelederBehovEntity::behovStatus)
                    original
                })
                narmestelederBehovEntitSlot.captured.behovStatus shouldBe BehovStatus.BEHOV_FULFILLED
            }

            it("throws when behov not found") {
                // Arrange
                val id = UUID.randomUUID()

                coEvery { nlDb.findBehovById(id) } returns null
                // Act + Assert
                shouldThrow<LinemanagerRequirementNotFoundException> {
                    service().updateNlBehov(id, BehovStatus.BEHOV_FULFILLED)
                }
            }
        }

        describe("expireOldLinemanagerRequirements") {
            it("expires only behovs where sykmelding is inactive and returns count") {
                // Arrange
                val createdBeforeDays = 7L
                val inactiveId = UUID.randomUUID()
                val activeId = UUID.randomUUID()

                val inactiveBehov = NarmestelederBehovEntity(
                    id = inactiveId,
                    orgnummer = "111111111",
                    hovedenhetOrgnummer = "222222222",
                    sykmeldtFnr = "12345678910",
                    narmestelederFnr = "01987654321",
                    behovReason = BehovReason.DEAKTIVERT_LEDER,
                    behovStatus = BehovStatus.BEHOV_CREATED,
                    avbruttNarmesteLederId = UUID.randomUUID(),
                )
                val activeBehov = inactiveBehov.copy(
                    id = activeId,
                    sykmeldtFnr = "10987654321",
                    behovStatus = BehovStatus.DIALOGPORTEN_STATUS_SET_REQUIRES_ATTENTION,
                )

                val updatedSlot: CapturingSlot<NarmestelederBehovEntity> = slot()

                coEvery { nlDb.findByCreatedBeforeAndStatus(any(), any(), any(), any()) } coAnswers {
                    ResultPage(
                        listOf(
                            inactiveBehov,
                            activeBehov
                        ),
                        page = secondArg()
                    )
                }
                coEvery {
                    dinesykmeldteService.getIsActiveSykmelding(
                        inactiveBehov.sykmeldtFnr,
                        inactiveBehov.orgnummer
                    )
                } returns false
                coEvery {
                    dinesykmeldteService.getIsActiveSykmelding(
                        activeBehov.sykmeldtFnr,
                        activeBehov.orgnummer
                    )
                } returns true
                coEvery { nlDb.updateNlBehov(capture(updatedSlot)) } returns Unit

                // Act
                val expiredCount = service().expireOldLinemanagerRequirements(createdBeforeDays)

                // Assert
                expiredCount shouldBe 1

                coVerify(exactly = 1) {
                    nlDb.findByCreatedBeforeAndStatus(
                        createdBefore = any(),
                        page = 0,
                        pageSize = 100,
                        status = listOf(
                            BehovStatus.BEHOV_CREATED,
                            BehovStatus.DIALOGPORTEN_STATUS_SET_REQUIRES_ATTENTION,
                        )
                    )
                }
                coVerify(exactly = 2) { dinesykmeldteService.getIsActiveSykmelding(any(), any()) }
                coVerify(exactly = 1) { nlDb.updateNlBehov(any()) }

                updatedSlot.isCaptured shouldBe true
                updatedSlot.captured.id shouldBe inactiveId
                updatedSlot.captured.behovStatus shouldBe BehovStatus.BEHOV_EXPIRED
            }

            it("returns 0 and does not update when no candidates are found") {
                // Arrange
                coEvery { nlDb.findByCreatedBeforeAndStatus(any(), any(), any(), any()) } answers {
                    ResultPage(
                        emptyList(),
                        page = secondArg()
                    )
                }

                // Act
                val expiredCount = service().expireOldLinemanagerRequirements(30)

                // Assert
                expiredCount shouldBe 0
                coVerify(exactly = 0) { dinesykmeldteService.getIsActiveSykmelding(any(), any()) }
                coVerify(exactly = 0) { nlDb.updateNlBehov(any()) }
            }
        }
    })
