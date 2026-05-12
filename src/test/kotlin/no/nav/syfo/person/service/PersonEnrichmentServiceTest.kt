package no.nav.syfo.person.service

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import no.nav.syfo.TestDB
import no.nav.syfo.narmesteleder.exposed.PersonBatchInsertRow
import no.nav.syfo.narmesteleder.exposed.PersonEntity
import no.nav.syfo.narmesteleder.exposed.PersonTable
import no.nav.syfo.narmesteleder.exposed.personTable
import no.nav.syfo.pdl.PdlService
import no.nav.syfo.pdl.Person
import no.nav.syfo.pdl.client.Foedselsdato
import no.nav.syfo.pdl.client.Navn
import no.nav.syfo.person.domain.PersonStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.LocalDate

class PersonEnrichmentServiceTest :
    DescribeSpec({
        val pdlService = mockk<PdlService>()

        beforeTest {
            clearAllMocks()
            TestDB.clearPersonData()
        }

        fun insertPerson(fnr: String, status: PersonStatus) {
            transaction(TestDB.exposedDatabase) {
                personTable.batchInsertIgnoreExisting(
                    listOf(PersonBatchInsertRow(fnr = fnr, status = status.name)),
                )
            }
        }

        fun findPerson(fnr: String): PersonEntity? = transaction(TestDB.exposedDatabase) {
            PersonEntity.find { PersonTable.fnr eq fnr }.firstOrNull()
        }

        fun service() = PersonEnrichmentService(
            database = TestDB.exposedDatabase,
            pdlService = pdlService,
        )

        describe("enrichPendingPersons") {
            it("should do nothing when there are no pending persons") {
                coEvery { pdlService.getPersonsBolk(any()) } returns emptyMap()

                service().enrichPendingPersons()

                coVerify(exactly = 0) { pdlService.getPersonsBolk(any()) }
            }

            it("should enrich a pending person found in PDL") {
                val fnr = "12345678901"
                insertPerson(fnr, PersonStatus.PENDING)

                val navn = Navn(fornavn = "Ola", mellomnavn = null, etternavn = "Nordmann")
                val foedselsdato = LocalDate.of(1990, 1, 15)
                coEvery { pdlService.getPersonsBolk(listOf(fnr)) } returns mapOf(
                    fnr to Person(
                        name = navn,
                        nationalIdentificationNumber = fnr,
                        foedselsdato = Foedselsdato(foedselsdato),
                    ),
                )

                service().enrichPendingPersons()

                val updated = findPerson(fnr)
                updated?.fornavn shouldBe "Ola"
                updated?.mellomnavn shouldBe null
                updated?.etternavn shouldBe "Nordmann"
                updated?.foedselsdato shouldBe foedselsdato
                updated?.status shouldBe PersonStatus.ENRICHED.name
            }

            it("should set NOT_FOUND when PDL returns null for fnr") {
                val fnr = "12345678901"
                insertPerson(fnr, PersonStatus.PENDING)

                coEvery { pdlService.getPersonsBolk(listOf(fnr)) } returns mapOf(fnr to null)

                service().enrichPendingPersons()

                val updated = findPerson(fnr)
                updated?.status shouldBe PersonStatus.NOT_FOUND.name
                updated?.fornavn shouldBe null
            }

            it("should handle mix of found and not-found persons") {
                val fnr1 = "12345678901"
                val fnr2 = "98765432109"
                insertPerson(fnr1, PersonStatus.PENDING)
                insertPerson(fnr2, PersonStatus.PENDING)

                val navn1 = Navn(fornavn = "Kari", mellomnavn = "Marte", etternavn = "Nordmann")
                coEvery { pdlService.getPersonsBolk(any()) } returns mapOf(
                    fnr1 to Person(
                        name = navn1,
                        nationalIdentificationNumber = fnr1,
                        foedselsdato = null,
                    ),
                    fnr2 to null,
                )

                service().enrichPendingPersons()

                val person1 = findPerson(fnr1)
                person1?.fornavn shouldBe "Kari"
                person1?.mellomnavn shouldBe "Marte"
                person1?.etternavn shouldBe "Nordmann"
                person1?.status shouldBe PersonStatus.ENRICHED.name

                val person2 = findPerson(fnr2)
                person2?.status shouldBe PersonStatus.NOT_FOUND.name
            }

            it("should not process persons with status other than PENDING") {
                val fnrEnriched = "12345678901"
                val fnrNotFound = "98765432109"
                insertPerson(fnrEnriched, PersonStatus.ENRICHED)
                insertPerson(fnrNotFound, PersonStatus.NOT_FOUND)

                service().enrichPendingPersons()

                coVerify(exactly = 0) { pdlService.getPersonsBolk(any()) }
            }

            it("should process batches continuously until fewer than batch size persons remain") {
                val fnrs = (1..501).map { i -> i.toString().padStart(11, '0') }
                fnrs.forEach { insertPerson(it, PersonStatus.PENDING) }

                coEvery { pdlService.getPersonsBolk(any()) } returns emptyMap()

                service().enrichPendingPersons()

                coVerify(exactly = 1) {
                    pdlService.getPersonsBolk(match { it.size == 500 })
                }
                coVerify(exactly = 1) {
                    pdlService.getPersonsBolk(match { it.size == 1 })
                }
            }
        }
    })
