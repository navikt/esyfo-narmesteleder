package no.nav.syfo.pdl.leesah

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.doubles.shouldBeExactly
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import no.nav.syfo.TestDB
import no.nav.syfo.application.metric.METRICS_REGISTRY
import no.nav.syfo.narmesteleder.domain.PersonalIdentificationNumber
import no.nav.syfo.narmesteleder.exposed.PersonBatchInsertRow
import no.nav.syfo.narmesteleder.exposed.PersonEntity
import no.nav.syfo.narmesteleder.exposed.PersonTable
import no.nav.syfo.narmesteleder.exposed.personTable
import no.nav.syfo.pdl.PdlService
import no.nav.syfo.pdl.Person
import no.nav.syfo.pdl.client.Foedselsdato
import no.nav.syfo.pdl.client.Navn
import no.nav.syfo.pdl.exception.PdlRequestException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import java.time.LocalDate

class PdlLeesahNameUpdateServiceTest :
    DescribeSpec({
        val pdlService = mockk<PdlService>()
        val service = PdlLeesahNameUpdateService(TestDB.exposedDatabase, pdlService)
        val logAppender = ListAppender<ILoggingEvent>()
        val logger = LoggerFactory.getLogger("no.nav.syfo.pdl.leesah.PdlLeesahNameUpdateService") as Logger

        beforeSpec {
            logger.level = Level.INFO
            logAppender.start()
            logger.addAppender(logAppender)
        }

        afterSpec {
            logger.detachAppender(logAppender)
        }

        beforeTest {
            TestDB.clearPersonData()
            clearMocks(pdlService)
            logAppender.list.clear()
            removePdlLeesahPersonUpdateMetrics()
        }

        describe("processNameChanges") {
            it("updates existing persons with current data from PDL in one bulk lookup") {
                val fnr1 = "12345678901"
                val fnr2 = "10987654321"
                insertPerson(fnr = fnr1, fornavn = "Old", mellomnavn = "Name", etternavn = "Person")
                insertPerson(fnr = fnr2, fornavn = "Old", etternavn = "Second")
                coEvery {
                    pdlService.getPersonsBolk(match { it.toSet() == setOf(fnr1, fnr2) })
                } returns mapOf(
                    fnr1 to pdlPerson(
                        fnr = fnr1,
                        fornavn = "Ada",
                        mellomnavn = "Augusta",
                        etternavn = "Lovelace",
                        foedselsdato = LocalDate.of(1985, 12, 10),
                    ),
                    fnr2 to pdlPerson(
                        fnr = fnr2,
                        fornavn = "Grace",
                        mellomnavn = null,
                        etternavn = "Hopper",
                        foedselsdato = LocalDate.of(1906, 12, 9),
                    ),
                )

                lateinit var result: PdlLeesahNameUpdateResult
                runTest {
                    result = service.processNameChanges(listOf(fnr1, fnr2))
                }

                result shouldBe PdlLeesahNameUpdateResult(
                    updatedCount = 2,
                    notFoundInRegisterCount = 0,
                    pdlNotFoundCount = 0,
                )
                coVerify(exactly = 1) {
                    pdlService.getPersonsBolk(match { it.toSet() == setOf(fnr1, fnr2) })
                }
                transaction(TestDB.exposedDatabase) {
                    val personsByFnr = PersonEntity.all().associateBy { it.fnr }
                    personsByFnr.getValue(fnr1).fornavn shouldBe "Ada"
                    personsByFnr.getValue(fnr1).mellomnavn shouldBe "Augusta"
                    personsByFnr.getValue(fnr1).etternavn shouldBe "Lovelace"
                    personsByFnr.getValue(fnr1).foedselsdato shouldBe LocalDate.of(1985, 12, 10)
                    personsByFnr.getValue(fnr1).status shouldBe "PENDING"
                    personsByFnr.getValue(fnr2).fornavn shouldBe "Grace"
                    personsByFnr.getValue(fnr2).etternavn shouldBe "Hopper"
                }
            }

            it("ignores fnr values that do not exist in person table and does not call PDL") {
                lateinit var result: PdlLeesahNameUpdateResult
                runTest {
                    result = service.processNameChanges(listOf("12345678901"))
                }

                result shouldBe PdlLeesahNameUpdateResult(
                    updatedCount = 0,
                    notFoundInRegisterCount = 1,
                    pdlNotFoundCount = 0,
                )
                coVerify(exactly = 0) { pdlService.getPersonsBolk(any()) }
                personUpdateMetricCount(PdlLeesahNameUpdateService.RESULT_NOT_FOUND_IN_REGISTER) shouldBeExactly 0.0
            }

            it("handles null-values from PDL deterministically without updating the person") {
                val fnr = "12345678901"
                insertPerson(fnr = fnr, fornavn = "Old", etternavn = "Name")
                coEvery { pdlService.getPersonsBolk(listOf(fnr)) } returns mapOf(fnr to null)

                lateinit var result: PdlLeesahNameUpdateResult
                runTest {
                    result = service.processNameChanges(listOf(fnr))
                }

                result shouldBe PdlLeesahNameUpdateResult(
                    updatedCount = 0,
                    notFoundInRegisterCount = 0,
                    pdlNotFoundCount = 1,
                )
                transaction(TestDB.exposedDatabase) {
                    val person = PersonEntity.find { PersonTable.fnr eq fnr }.single()
                    person.fornavn shouldBe "Old"
                    person.etternavn shouldBe "Name"
                }
                personUpdateMetricCount(PdlLeesahNameUpdateService.RESULT_PDL_NOT_FOUND) shouldBeExactly 0.0
            }

            it("counts only pdl_error metrics when transient PDL errors leave the batch non-committable") {
                val fnr = "12345678901"
                insertPerson(fnr = fnr, fornavn = "Old", etternavn = "Name")
                coEvery { pdlService.getPersonsBolk(listOf(fnr)) } returns emptyMap()

                runTest {
                    shouldThrow<PdlRequestException> {
                        service.processNameChanges(listOf(fnr))
                    }
                }

                transaction(TestDB.exposedDatabase) {
                    val person = PersonEntity.find { PersonTable.fnr eq fnr }.single()
                    person.fornavn shouldBe "Old"
                    person.etternavn shouldBe "Name"
                }
                personUpdateMetricCount(PdlLeesahNameUpdateService.RESULT_PDL_ERROR) shouldBeExactly 1.0
                personUpdateMetricCount(PdlLeesahNameUpdateService.RESULT_UPDATED) shouldBeExactly 0.0
                personUpdateMetricCount(PdlLeesahNameUpdateService.RESULT_NOT_FOUND_IN_REGISTER) shouldBeExactly 0.0
                personUpdateMetricCount(PdlLeesahNameUpdateService.RESULT_PDL_NOT_FOUND) shouldBeExactly 0.0
            }

            it("calls PDL once for existing persons when multiple events and duplicate idents are present") {
                val existingFnr1 = "12345678901"
                val existingFnr2 = "10987654321"
                val missingFnr = "11111111111"
                insertPerson(fnr = existingFnr1)
                insertPerson(fnr = existingFnr2)
                coEvery {
                    pdlService.getPersonsBolk(match { it.toSet() == setOf(existingFnr1, existingFnr2) })
                } returns mapOf(
                    existingFnr1 to pdlPerson(existingFnr1, "Ada", null, "Lovelace"),
                    existingFnr2 to pdlPerson(existingFnr2, "Grace", null, "Hopper"),
                )

                lateinit var result: PdlLeesahNameUpdateResult
                runTest {
                    result = service.processNameChanges(
                        listOf(
                            existingFnr1,
                            missingFnr,
                            existingFnr2,
                            existingFnr1,
                            "not-an-ident",
                        ),
                    )
                }

                result shouldBe PdlLeesahNameUpdateResult(
                    updatedCount = 2,
                    notFoundInRegisterCount = 1,
                    pdlNotFoundCount = 0,
                )
                coVerify(exactly = 1) {
                    pdlService.getPersonsBolk(match { it.toSet() == setOf(existingFnr1, existingFnr2) })
                }
                personUpdateMetricCount(PdlLeesahNameUpdateService.RESULT_UPDATED) shouldBeExactly 0.0
                personUpdateMetricCount(PdlLeesahNameUpdateService.RESULT_NOT_FOUND_IN_REGISTER) shouldBeExactly 0.0
            }

            it("treats missing keys differently from null-values in the same batch") {
                val fnrWithPerson = "12345678901"
                val fnrWithNull = "10987654321"
                val fnrMissingKey = "11111111111"
                insertPerson(fnr = fnrWithPerson)
                insertPerson(fnr = fnrWithNull)
                insertPerson(fnr = fnrMissingKey)
                coEvery {
                    pdlService.getPersonsBolk(match { it.toSet() == setOf(fnrWithPerson, fnrWithNull, fnrMissingKey) })
                } returns mapOf(
                    fnrWithPerson to pdlPerson(fnrWithPerson, "Ada", null, "Lovelace"),
                    fnrWithNull to null,
                )

                runTest {
                    shouldThrow<PdlRequestException> {
                        service.processNameChanges(listOf(fnrWithPerson, fnrWithNull, fnrMissingKey))
                    }
                }

                transaction(TestDB.exposedDatabase) {
                    val personsByFnr = PersonEntity.all().associateBy { it.fnr }
                    personsByFnr.getValue(fnrWithPerson).fornavn shouldBe null
                    personsByFnr.getValue(fnrWithNull).fornavn shouldBe null
                    personsByFnr.getValue(fnrMissingKey).fornavn shouldBe null
                }
                personUpdateMetricCount(PdlLeesahNameUpdateService.RESULT_PDL_ERROR) shouldBeExactly 1.0
                personUpdateMetricCount(PdlLeesahNameUpdateService.RESULT_UPDATED) shouldBeExactly 0.0
                personUpdateMetricCount(PdlLeesahNameUpdateService.RESULT_PDL_NOT_FOUND) shouldBeExactly 0.0
                personUpdateMetricCount(PdlLeesahNameUpdateService.RESULT_NOT_FOUND_IN_REGISTER) shouldBeExactly 0.0
            }

            it("does not log fnr, names or exception messages when PDL request fails") {
                val fnr = "12345678901"
                val fornavn = "Ola"
                val etternavn = "Nordmann"
                insertPerson(fnr = fnr)
                coEvery { pdlService.getPersonsBolk(listOf(fnr)) } returns emptyMap()

                runTest {
                    shouldThrow<PdlRequestException> {
                        service.processNameChanges(listOf(fnr))
                    }
                }

                val logMessage = logAppender.list.joinToString("\n") { it.formattedMessage }
                logMessage.contains(fnr) shouldBe false
                logMessage.contains(fornavn) shouldBe false
                logMessage.contains(etternavn) shouldBe false
                logMessage.contains("upstream failed") shouldBe false
                logMessage.contains("missingResponseCount=1") shouldBe true
            }
        }
    })

private fun insertPerson(
    fnr: String,
    fornavn: String? = null,
    mellomnavn: String? = null,
    etternavn: String? = null,
    foedselsdato: LocalDate? = null,
) {
    transaction(TestDB.exposedDatabase) {
        personTable.batchInsertIgnoreExisting(
            listOf(
                PersonBatchInsertRow(
                    fnr = fnr,
                    status = "PENDING",
                    fornavn = fornavn,
                    mellomnavn = mellomnavn,
                    etternavn = etternavn,
                    foedselsdato = foedselsdato,
                ),
            ),
        )
    }
}

private fun removePdlLeesahPersonUpdateMetrics() {
    METRICS_REGISTRY.find(PDL_LEESAH_PERSON_UPDATE_TOTAL)
        .meters()
        .forEach(METRICS_REGISTRY::remove)
}

private fun personUpdateMetricCount(result: String): Double = METRICS_REGISTRY.find(PDL_LEESAH_PERSON_UPDATE_TOTAL)
    .tag("result", result)
    .counter()
    ?.count() ?: 0.0

private fun pdlPerson(
    fnr: String,
    fornavn: String,
    mellomnavn: String?,
    etternavn: String,
    foedselsdato: LocalDate? = null,
): Person = Person(
    name = Navn(
        fornavn = fornavn,
        mellomnavn = mellomnavn,
        etternavn = etternavn,
    ),
    nationalIdentificationNumber = PersonalIdentificationNumber(fnr),
    foedselsdato = Foedselsdato(foedselsdato),
)
