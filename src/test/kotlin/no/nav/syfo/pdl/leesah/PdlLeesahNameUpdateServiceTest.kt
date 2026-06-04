package no.nav.syfo.pdl.leesah

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import no.nav.syfo.TestDB
import no.nav.syfo.narmesteleder.exposed.PersonBatchInsertRow
import no.nav.syfo.narmesteleder.exposed.PersonEntity
import no.nav.syfo.narmesteleder.exposed.PersonTable
import no.nav.syfo.narmesteleder.exposed.personTable
import no.nav.syfo.pdl.PdlService
import no.nav.syfo.pdl.Person
import no.nav.syfo.pdl.client.Foedselsdato
import no.nav.syfo.pdl.client.Navn
import no.nav.syfo.pdl.exception.PdlRequestException
import no.nav.syfo.pdl.exception.PdlResourceNotFoundException
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
        }

        describe("processNameChange") {
            it("updates existing person with current data from PDL") {
                val fnr = "12345678901"
                insertPerson(fnr = fnr, fornavn = "Old", mellomnavn = "Name", etternavn = "Person")
                coEvery { pdlService.getPersonFor(fnr) } returns pdlPerson(
                    fnr = fnr,
                    fornavn = "Ada",
                    mellomnavn = "Augusta",
                    etternavn = "Lovelace",
                    foedselsdato = LocalDate.of(1985, 12, 10),
                )

                lateinit var result: PdlLeesahNameUpdateResult
                runTest {
                    result = service.processNameChange(listOf(fnr))
                }

                result shouldBe PdlLeesahNameUpdateResult(
                    updatedCount = 1,
                    notFoundInRegisterCount = 0,
                    pdlNotFoundCount = 0,
                )
                transaction(TestDB.exposedDatabase) {
                    val person = PersonEntity.find { PersonTable.fnr eq fnr }.single()
                    person.fornavn shouldBe "Ada"
                    person.mellomnavn shouldBe "Augusta"
                    person.etternavn shouldBe "Lovelace"
                    person.foedselsdato shouldBe LocalDate.of(1985, 12, 10)
                    person.status shouldBe "PENDING"
                }
            }

            it("ignores fnr values that do not exist in person table and does not call PDL") {
                lateinit var result: PdlLeesahNameUpdateResult
                runTest {
                    result = service.processNameChange(listOf("12345678901"))
                }

                result shouldBe PdlLeesahNameUpdateResult(
                    updatedCount = 0,
                    notFoundInRegisterCount = 1,
                    pdlNotFoundCount = 0,
                )
                coVerify(exactly = 0) { pdlService.getPersonFor(any()) }
            }

            it("handles PDL resource not found deterministically without updating the person") {
                val fnr = "12345678901"
                insertPerson(fnr = fnr, fornavn = "Old", etternavn = "Name")
                coEvery { pdlService.getPersonFor(fnr) } throws PdlResourceNotFoundException("Not found for $fnr")

                lateinit var result: PdlLeesahNameUpdateResult
                runTest {
                    result = service.processNameChange(listOf(fnr))
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
            }

            it("rethrows transient PDL errors so Kafka can retry and leaves person unchanged") {
                val fnr = "12345678901"
                insertPerson(fnr = fnr, fornavn = "Old", etternavn = "Name")
                coEvery { pdlService.getPersonFor(fnr) } throws PdlRequestException("PDL error for $fnr")

                runTest {
                    shouldThrow<PdlRequestException> {
                        service.processNameChange(listOf(fnr))
                    }
                }

                transaction(TestDB.exposedDatabase) {
                    val person = PersonEntity.find { PersonTable.fnr eq fnr }.single()
                    person.fornavn shouldBe "Old"
                    person.etternavn shouldBe "Name"
                }
            }

            it("calls PDL only for existing persons when multiple idents are present") {
                val existingFnr1 = "12345678901"
                val existingFnr2 = "10987654321"
                val missingFnr = "11111111111"
                insertPerson(fnr = existingFnr1)
                insertPerson(fnr = existingFnr2)
                coEvery { pdlService.getPersonFor(existingFnr1) } returns pdlPerson(existingFnr1, "Ada", null, "Lovelace")
                coEvery { pdlService.getPersonFor(existingFnr2) } returns pdlPerson(existingFnr2, "Grace", null, "Hopper")

                lateinit var result: PdlLeesahNameUpdateResult
                runTest {
                    result = service.processNameChange(
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
                coVerify(exactly = 1) { pdlService.getPersonFor(existingFnr1) }
                coVerify(exactly = 1) { pdlService.getPersonFor(existingFnr2) }
                coVerify(exactly = 0) { pdlService.getPersonFor(missingFnr) }
            }

            it("does not log fnr, names or exception messages when PDL request fails") {
                val fnr = "12345678901"
                val fornavn = "Ola"
                val etternavn = "Nordmann"
                insertPerson(fnr = fnr)
                coEvery { pdlService.getPersonFor(fnr) } throws PdlRequestException("upstream failed for $fnr and $fornavn $etternavn")

                runTest {
                    shouldThrow<PdlRequestException> {
                        service.processNameChange(listOf(fnr))
                    }
                }

                val logMessage = logAppender.list.joinToString("\n") { it.formattedMessage }
                logMessage.contains(fnr) shouldBe false
                logMessage.contains(fornavn) shouldBe false
                logMessage.contains(etternavn) shouldBe false
                logMessage.contains("upstream failed") shouldBe false
                logMessage.contains("exceptionType=PdlRequestException") shouldBe true
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
    nationalIdentificationNumber = fnr,
    foedselsdato = Foedselsdato(foedselsdato),
)
