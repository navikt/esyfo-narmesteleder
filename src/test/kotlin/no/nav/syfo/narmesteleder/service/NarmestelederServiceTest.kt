package no.nav.syfo.narmesteleder.service

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.CapturingSlot
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import no.nav.syfo.aareg.AaregService
import no.nav.syfo.aareg.Arbeidsforhold
import no.nav.syfo.aareg.client.ArbeidsstedType
import no.nav.syfo.aareg.client.OpplysningspliktigType
import no.nav.syfo.dinesykmeldte.DinesykmeldteService
import no.nav.syfo.narmesteleder.db.NarmestelederBehovEntity
import no.nav.syfo.narmesteleder.db.NarmestelederDb
import no.nav.syfo.narmesteleder.domain.BehovReason
import no.nav.syfo.narmesteleder.domain.BehovStatus
import no.nav.syfo.narmesteleder.domain.LinemanagerRequirementWrite
import no.nav.syfo.narmesteleder.domain.OrganizationNumber
import no.nav.syfo.narmesteleder.domain.PersonalIdentificationNumber
import no.nav.syfo.narmesteleder.exception.LinemanagerRequirementNotFoundException
import no.nav.syfo.narmesteleder.kafka.TEAMSYKMELDING_NL_LEESAH_TOPIC
import no.nav.syfo.pdl.PdlService
import no.nav.syfo.pdl.Person
import no.nav.syfo.pdl.client.Navn
import no.nav.syfo.sykmelding.kafka.SENDT_SYKMELDING_TOPIC
import no.nav.syfo.sykmelding.model.Arbeidsgiver
import org.slf4j.LoggerFactory
import java.util.*

class NarmestelederServiceTest :
    DescribeSpec({
        val nlDb = mockk<NarmestelederDb>(relaxed = true)
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
            it("correlates degraded outcomes with UUIDs from their known source only") {
                val logger = LoggerFactory.getLogger(NarmestelederService::class.java) as Logger
                val previousLevel = logger.level
                val appender = ListAppender<ILoggingEvent>()
                appender.start()
                logger.level = Level.WARN
                logger.addAppender(appender)
                try {
                    val write = LinemanagerRequirementWrite(
                        employeeIdentificationNumber = PersonalIdentificationNumber("12345678910"),
                        orgNumber = OrganizationNumber("123456789"),
                        managerIdentificationNumber = PersonalIdentificationNumber("01987654321"),
                        behovReason = BehovReason.DEAKTIVERT_LEDER,
                    )
                    coEvery { nlDb.insertNlBehov(any()) } answers { firstArg<NarmestelederBehovEntity>().copy(id = UUID.randomUUID()) }
                    coEvery { aaregService.findArbeidsforholdByPersonIdent(any()) } returns emptyList()
                    val sykmeldingId = UUID.randomUUID()
                    val relationId = UUID.randomUUID()

                    service().createNewNlBehov(write, skipSykmeldingCheck = true, behovSource = BehovSource(sykmeldingId.toString(), SENDT_SYKMELDING_TOPIC))
                    service().createNewNlBehov(
                        write,
                        skipSykmeldingCheck = true,
                        behovSource = BehovSource(relationId.toString(), TEAMSYKMELDING_NL_LEESAH_TOPIC),
                        arbeidsgiver = Arbeidsgiver(orgnummer = "123456789", juridiskOrgnummer = null, orgNavn = "Test"),
                    )

                    val events = appender.list.map { it.keyValuePairs.associate { field -> field.key to field.value } }
                    events.map { it["event_type"] } shouldBe listOf("narmestelederbehov_stored_degraded", "narmestelederbehov_stored_degraded")
                    events.map { it["reason"] } shouldBe listOf("EMPLOYMENT_MISSING", "SICK_LEAVE_MAIN_ORG_MISSING")
                    events[0]["behov_source"] shouldBe SENDT_SYKMELDING_TOPIC
                    events[0]["sykmelding_id"] shouldBe sykmeldingId.toString()
                    events[0]["narmesteleder_id"] shouldBe null
                    events[1]["behov_source"] shouldBe TEAMSYKMELDING_NL_LEESAH_TOPIC
                    events[1]["narmesteleder_id"] shouldBe relationId.toString()
                    events[1]["sykmelding_id"] shouldBe null
                    events.toString().contains("12345678910") shouldBe false
                } finally {
                    logger.detachAppender(appender)
                    appender.stop()
                    logger.level = previousLevel
                }
            }

            it("persists entity with resolved hovedenhet") {
                // Arrange
                val sykmeldtFnr = "12345678910"
                val underenhetOrg = "123456789"
                val hovedenhetOrg = "987654321"
                val write = LinemanagerRequirementWrite(
                    employeeIdentificationNumber = PersonalIdentificationNumber(sykmeldtFnr),
                    orgNumber = OrganizationNumber(underenhetOrg),
                    managerIdentificationNumber = PersonalIdentificationNumber("01987654321"),
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
                        eq(write.employeeIdentificationNumber.value),
                        eq(write.orgNumber.value)
                    )
                } returns true

                // Act
                service().createNewNlBehov(
                    write,
                    behovSource = BehovSource(id = UUID.randomUUID().toString(), source = "test")
                )

                // Assert
                coVerify(exactly = 1) { nlDb.insertNlBehov(any()) }
                coVerify(exactly = 1) { aaregService.findArbeidsforholdByPersonIdent(eq(write.employeeIdentificationNumber.value)) }

                captured.isCaptured shouldBe true
                val entity = captured.captured
                entity.sykmeldtFnr shouldBe sykmeldtFnr
                entity.orgnummer shouldBe underenhetOrg
                entity.hovedenhetOrgnummer shouldBe hovedenhetOrg
                entity.narmestelederFnr shouldBe write.managerIdentificationNumber?.value
                entity.behovReason shouldBe write.behovReason
                entity.behovStatus shouldBe BehovStatus.BEHOV_CREATED
            }

            it("skips persistence when flag is false") {
                // Arrange
                val sykmeldtFnr = "12345678910"
                val underenhetOrg = "123456789"
                val write = LinemanagerRequirementWrite(
                    employeeIdentificationNumber = PersonalIdentificationNumber(sykmeldtFnr),
                    orgNumber = OrganizationNumber(underenhetOrg),
                    managerIdentificationNumber = PersonalIdentificationNumber("01987654321"),
                    behovReason = BehovReason.DEAKTIVERT_LEDER,
                    revokedLinemanagerId = UUID.randomUUID(),
                )

                coEvery {
                    nlDb.insertNlBehov(any())
                } throws AssertionError(
                    "insertNlBehov should not be called when persistLeesahNlBehov=false"
                )
                coEvery {
                    aaregService.findArbeidsforholdByPersonIdent(any())
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
                coVerify(exactly = 0) { aaregService.findArbeidsforholdByPersonIdent(any()) }
            }

            it("persists with status ARBEIDSFORHOLD_NOT_FOUND when arbeidsforhold missing") {
                // Arrange
                val sykmeldtFnr = "12345678910"
                val underenhetOrg = "123456789"
                val write = LinemanagerRequirementWrite(
                    employeeIdentificationNumber = PersonalIdentificationNumber(sykmeldtFnr),
                    orgNumber = OrganizationNumber(underenhetOrg),
                    managerIdentificationNumber = PersonalIdentificationNumber("01987654321"),
                    behovReason = BehovReason.DEAKTIVERT_LEDER,
                    revokedLinemanagerId = UUID.randomUUID(),
                )
                coEvery { aaregService.findArbeidsforholdByPersonIdent(sykmeldtFnr) } returns emptyList()
                coEvery {
                    dinesykmeldteService.getIsActiveSykmelding(
                        eq(write.employeeIdentificationNumber.value),
                        eq(write.orgNumber.value)
                    )
                } returns true

                // Act
                shouldNotThrowAny {
                    service().createNewNlBehov(
                        write,
                        behovSource = BehovSource(id = UUID.randomUUID().toString(), source = "test")
                    )
                }

                // Assert
                coVerify(exactly = 1) { aaregService.findArbeidsforholdByPersonIdent(eq(write.employeeIdentificationNumber.value)) }
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
                    employeeIdentificationNumber = PersonalIdentificationNumber(sykmeldtFnr),
                    orgNumber = OrganizationNumber(underenhetOrg),
                    managerIdentificationNumber = PersonalIdentificationNumber("01987654321"),
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
                        eq(write.employeeIdentificationNumber.value),
                        eq(write.orgNumber.value)
                    )
                } returns true

                // Act
                shouldNotThrowAny {
                    service().createNewNlBehov(
                        write,
                        behovSource = BehovSource(id = UUID.randomUUID().toString(), source = "test")
                    )
                }

                // Assert
                coVerify(exactly = 1) { aaregService.findArbeidsforholdByPersonIdent(eq(write.employeeIdentificationNumber.value)) }
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
                    employeeIdentificationNumber = PersonalIdentificationNumber(sykmeldtFnr),
                    orgNumber = OrganizationNumber(underenhetOrg),
                    managerIdentificationNumber = PersonalIdentificationNumber("01987654321"),
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
                        eq(write.employeeIdentificationNumber.value),
                        eq(write.orgNumber.value)
                    )
                } returns true

                // Act
                shouldNotThrowAny {
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
                    employeeIdentificationNumber = PersonalIdentificationNumber(sykmeldtFnr),
                    orgNumber = OrganizationNumber(underenhetOrg),
                    managerIdentificationNumber = PersonalIdentificationNumber("01987654321"),
                    behovReason = BehovReason.DEAKTIVERT_LEDER,
                    revokedLinemanagerId = UUID.randomUUID(),
                )

                coEvery {
                    dinesykmeldteService.getIsActiveSykmelding(
                        eq(write.employeeIdentificationNumber.value),
                        eq(write.orgNumber.value)
                    )
                } returns false

                // Act
                service().createNewNlBehov(
                    nlBehov = write,
                    behovSource = BehovSource(id = UUID.randomUUID().toString(), source = "test")
                )

                // Assert
                coVerify(exactly = 0) { nlDb.insertNlBehov(any()) }
                coVerify(exactly = 0) { aaregService.findArbeidsforholdByPersonIdent(any()) }
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
                read.orgNumber.value shouldBe entity.orgnummer
                read.mainOrgNumber.value shouldBe entity.hovedenhetOrgnummer
                read.employeeIdentificationNumber.value shouldBe entity.sykmeldtFnr
                read.managerIdentificationNumber?.value shouldBe entity.narmestelederFnr
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
                    nationalIdentificationNumber = PersonalIdentificationNumber(entity.sykmeldtFnr)
                )
                // Act
                val read = service().getLinemanagerRequirementReadById(id)

                // Assert
                coVerify(exactly = 1) { pdlService.getPersonFor(eq(entity.sykmeldtFnr)) }
                coVerify(exactly = 1) { nlDb.updateNlBehov(any()) }
                read.id shouldBe id
                read.orgNumber.value shouldBe entity.orgnummer
                read.mainOrgNumber.value shouldBe entity.hovedenhetOrgnummer
                read.employeeIdentificationNumber.value shouldBe entity.sykmeldtFnr
                read.managerIdentificationNumber?.value shouldBe entity.narmestelederFnr
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

        describe("updateStatusOnExpiredBehovs") {
            it("expires behovs with matching sykmelding tom and returns total count") {
                // Arrange
                val daysAfterTom = 16L
                val updatedCount = 5

                coEvery {
                    nlDb.setBehovStatusForSykmeldingWithTomBeforeAndStatus(
                        tomBefore = any(),
                        fromStatus = listOf(
                            BehovStatus.BEHOV_CREATED,
                            BehovStatus.DIALOGPORTEN_STATUS_SET_REQUIRES_ATTENTION
                        ),
                        newStatus = BehovStatus.BEHOV_EXPIRED,
                        limit = any()
                    )
                } returnsMany listOf(updatedCount, 0)

                // Act
                service().updateStatusOnExpiredBehovs(daysAfterTom)

                // Assert
                coVerify(exactly = 2) {
                    nlDb.setBehovStatusForSykmeldingWithTomBeforeAndStatus(
                        tomBefore = any(),
                        fromStatus = listOf(
                            BehovStatus.BEHOV_CREATED,
                            BehovStatus.DIALOGPORTEN_STATUS_SET_REQUIRES_ATTENTION
                        ),
                        newStatus = BehovStatus.BEHOV_EXPIRED,
                        limit = any()
                    )
                }
            }

            it("loops until no more behovs are updated") {
                // Arrange
                val daysAfterTom = 16L

                coEvery {
                    nlDb.setBehovStatusForSykmeldingWithTomBeforeAndStatus(
                        tomBefore = any(),
                        fromStatus = any(),
                        newStatus = any(),
                        limit = any()
                    )
                } returnsMany listOf(500, 500, 300, 0)

                // Act
                service().updateStatusOnExpiredBehovs(daysAfterTom)

                // Assert
                coVerify(exactly = 4) {
                    nlDb.setBehovStatusForSykmeldingWithTomBeforeAndStatus(
                        tomBefore = any(),
                        fromStatus = any(),
                        newStatus = any(),
                        limit = any()
                    )
                }
            }

            it("does nothing when no behovs match") {
                // Arrange
                coEvery {
                    nlDb.setBehovStatusForSykmeldingWithTomBeforeAndStatus(
                        tomBefore = any(),
                        fromStatus = any(),
                        newStatus = any(),
                        limit = any()
                    )
                } returns 0

                // Act
                service().updateStatusOnExpiredBehovs(16L)

                // Assert
                coVerify(exactly = 1) {
                    nlDb.setBehovStatusForSykmeldingWithTomBeforeAndStatus(
                        tomBefore = any(),
                        fromStatus = any(),
                        newStatus = any(),
                        limit = any()
                    )
                }
            }
        }
    })
