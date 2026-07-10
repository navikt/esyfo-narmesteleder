package no.nav.syfo.narmesteleder.exposed

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import no.nav.syfo.TestDB
import no.nav.syfo.narmesteleder.domain.LinemanagerSearchCursor
import no.nav.syfo.narmesteleder.domain.LinemanagerSearchQuery
import no.nav.syfo.narmesteleder.domain.Name
import no.nav.syfo.narmesteleder.domain.OrganizationNumber
import no.nav.syfo.narmesteleder.domain.PersonalIdentificationNumber
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

class LinemanagerSearchRepositoryTest :
    DescribeSpec({
        val fixedInstant = Instant.parse("2026-02-01T12:00:00Z")
        val fixedClock = Clock.fixed(fixedInstant, ZoneOffset.UTC)
        val repository = LinemanagerSearchRepository(TestDB.exposedDatabase, fixedClock)
        val now = OffsetDateTime.ofInstant(fixedInstant, ZoneOffset.UTC)
        val orgNumber = OrganizationNumber("123456789")

        beforeTest {
            TestDB.clearNarmestelederData()
            TestDB.clearPersonData()
        }

        fun insertPerson(
            fnr: String,
            firstName: String? = null,
            middleName: String? = null,
            lastName: String? = null,
        ) {
            transaction(TestDB.exposedDatabase) {
                personTable.batchInsertIgnoreExisting(
                    listOf(
                        PersonBatchInsertRow(
                            fnr = fnr,
                            status = "ENRICHED",
                            fornavn = firstName,
                            mellomnavn = middleName,
                            etternavn = lastName,
                            foedselsdato = LocalDate.parse("1990-01-01"),
                        ),
                    ),
                )
            }
        }

        fun insertRelation(
            employeeFnr: String,
            managerFnr: String,
            orgnummer: String = orgNumber.value,
            aktivFom: OffsetDateTime = now.minusDays(1),
            aktivTom: OffsetDateTime? = null,
            email: String = "leder@example.com",
            mobile: String = "99999999",
        ): Int = transaction(TestDB.exposedDatabase) {
            NarmestelederEntity.new {
                narmesteLederId = UUID.randomUUID()
                this.orgnummer = orgnummer
                sykmeldtFnr = employeeFnr
                narmestelederFnr = managerFnr
                narmestelederTelefonnummer = mobile
                narmestelederEpost = email
                arbeidsgiverForskutterer = true
                this.aktivFom = aktivFom
                this.aktivTom = aktivTom
            }.id.value
        }

        describe("search") {
            it("returns active linemanager relations with names for both employee and manager") {
                val employeeFnr = "12345678910"
                val managerFnr = "10987654321"
                insertPerson(employeeFnr, firstName = "Ola", middleName = "Mellom", lastName = "Nordmann")
                insertPerson(managerFnr, firstName = "Kari", lastName = "Nordmann")
                insertRelation(
                    employeeFnr = employeeFnr,
                    managerFnr = managerFnr,
                    email = "kari@example.com",
                    mobile = "90000000",
                )

                val results = repository.search(
                    LinemanagerSearchQuery(
                        orgNumber = orgNumber,
                        pageSize = 50,
                    ),
                )

                results.shouldHaveSize(1)
                val relation = results.single().linemanager
                relation.orgNumber shouldBe orgNumber
                relation.activeFrom shouldBe now.minusDays(1).toInstant()
                relation.employee.nationalIdentificationNumber shouldBe PersonalIdentificationNumber(employeeFnr)
                relation.employee.name shouldBe Name(
                    firstName = "Ola",
                    middleName = "Mellom",
                    lastName = "Nordmann",
                )
                relation.manager.nationalIdentificationNumber shouldBe PersonalIdentificationNumber(managerFnr)
                relation.manager.name shouldBe Name(
                    firstName = "Kari",
                    middleName = null,
                    lastName = "Nordmann",
                )
                relation.manager.email shouldBe "kari@example.com"
                relation.manager.mobile shouldBe "90000000"
            }

            it("keeps active relations when person rows are missing and returns null names") {
                insertRelation(
                    employeeFnr = "12345678910",
                    managerFnr = "10987654321",
                )

                val results = repository.search(
                    LinemanagerSearchQuery(
                        orgNumber = orgNumber,
                        pageSize = 50,
                    ),
                )

                results.shouldHaveSize(1)
                val relation = results.single().linemanager
                relation.employee.name.shouldBeNull()
                relation.manager.name.shouldBeNull()
            }

            it("filters on orgnumber, manager fnr, activeTom null and activeFrom not in the future") {
                val expectedEmployeeFnr = "12345678910"
                val expectedManagerFnr = "10987654321"
                insertRelation(
                    employeeFnr = expectedEmployeeFnr,
                    managerFnr = expectedManagerFnr,
                )
                insertRelation(
                    employeeFnr = "12345678911",
                    managerFnr = expectedManagerFnr,
                    orgnummer = "987654321",
                )
                insertRelation(
                    employeeFnr = "12345678912",
                    managerFnr = expectedManagerFnr,
                    aktivTom = now.minusDays(1),
                )
                insertRelation(
                    employeeFnr = "12345678913",
                    managerFnr = expectedManagerFnr,
                    aktivFom = now.plusDays(1),
                )
                insertRelation(
                    employeeFnr = "12345678914",
                    managerFnr = "10987654322",
                )

                val results = repository.search(
                    LinemanagerSearchQuery(
                        orgNumber = orgNumber,
                        managerNationalIdentificationNumber = PersonalIdentificationNumber(expectedManagerFnr),
                        pageSize = 50,
                    ),
                )

                results.map { it.linemanager.employee.nationalIdentificationNumber.value } shouldBe listOf(expectedEmployeeFnr)
            }

            it("supports deterministic cursor pagination sorted by relation id") {
                val firstId = insertRelation(
                    employeeFnr = "12345678910",
                    managerFnr = "10987654321",
                )
                val secondId = insertRelation(
                    employeeFnr = "12345678911",
                    managerFnr = "10987654321",
                )
                val thirdId = insertRelation(
                    employeeFnr = "12345678912",
                    managerFnr = "10987654321",
                )

                val firstPage = repository.search(
                    LinemanagerSearchQuery(
                        orgNumber = orgNumber,
                        pageSize = 2,
                    ),
                )

                firstPage.map { it.cursor.id } shouldBe listOf(firstId, secondId, thirdId)

                val secondPage = repository.search(
                    LinemanagerSearchQuery(
                        orgNumber = orgNumber,
                        pageSize = 2,
                        cursor = LinemanagerSearchCursor(id = secondId),
                    ),
                )

                secondPage.map { it.cursor.id } shouldBe listOf(thirdId)
            }
        }
    })
