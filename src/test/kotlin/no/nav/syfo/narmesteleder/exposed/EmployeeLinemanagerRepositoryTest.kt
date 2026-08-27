package no.nav.syfo.narmesteleder.exposed

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import no.nav.syfo.TestDB
import no.nav.syfo.narmesteleder.domain.EmployeeLinemanagerQuery
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

class EmployeeLinemanagerRepositoryTest :
    DescribeSpec({
        val fixedInstant = Instant.parse("2026-02-01T12:00:00Z")
        val fixedClock = Clock.fixed(fixedInstant, ZoneOffset.UTC)
        val repository = EmployeeLinemanagerRepository(TestDB.exposedDatabase, fixedClock)
        val now = OffsetDateTime.ofInstant(fixedInstant, ZoneOffset.UTC)
        val employeeFnr = "12345678910"
        val otherEmployeeFnr = "12345678911"
        val managerFnr = "10987654321"
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
            employee: String = employeeFnr,
            manager: String = managerFnr,
            organization: String = orgNumber.value,
            activeFrom: OffsetDateTime = now.minusDays(1),
            activeTo: OffsetDateTime? = null,
            email: String = "leder@example.com",
            mobile: String = "99999999",
            linemanagerId: UUID = UUID.randomUUID(),
        ) {
            transaction(TestDB.exposedDatabase) {
                NarmestelederEntity.new {
                    narmesteLederId = linemanagerId
                    orgnummer = organization
                    sykmeldtFnr = employee
                    narmestelederFnr = manager
                    narmestelederTelefonnummer = mobile
                    narmestelederEpost = email
                    arbeidsgiverForskutterer = true
                    aktivFom = activeFrom
                    aktivTom = activeTo
                }
            }
        }

        fun query(orgNumber: OrganizationNumber? = null) = EmployeeLinemanagerQuery(
            employeeNationalIdentificationNumber = PersonalIdentificationNumber(employeeFnr),
            orgNumber = orgNumber,
        )

        describe("findActiveForEmployee") {
            it("returns active relations across organizations sorted by organization number") {
                val secondOrgId = UUID.randomUUID()
                val firstOrgId = UUID.randomUUID()
                insertRelation(organization = "987654321", linemanagerId = secondOrgId)
                insertRelation(organization = "123456789", linemanagerId = firstOrgId)

                val results = repository.findActiveForEmployee(query()).linemanagers

                results.map { it.orgNumber.value } shouldBe listOf("123456789", "987654321")
                results.map { it.id } shouldBe listOf(firstOrgId, secondOrgId)
                results.first().activeFrom shouldBe now.minusDays(1).toInstant()
            }

            it("filters active relations by organization number when provided") {
                insertRelation(organization = "123456789")
                insertRelation(organization = "987654321")

                val results = repository.findActiveForEmployee(query(OrganizationNumber("987654321"))).linemanagers

                results.map { it.orgNumber.value } shouldBe listOf("987654321")
            }

            it("excludes relations with an end date") {
                insertRelation(activeTo = now.minusSeconds(1))

                repository.findActiveForEmployee(query()).linemanagers shouldBe emptyList()
            }

            it("excludes relations that start in the future") {
                insertRelation(activeFrom = now.plusDays(1))

                repository.findActiveForEmployee(query()).linemanagers shouldBe emptyList()
            }

            it("includes relations that started immediately before now") {
                insertRelation(activeFrom = now.minusSeconds(1))

                repository.findActiveForEmployee(query()).linemanagers.shouldHaveSize(1)
            }

            it("includes relations that start exactly now") {
                insertRelation(activeFrom = now)

                repository.findActiveForEmployee(query()).linemanagers.shouldHaveSize(1)
            }

            it("excludes relations for another employee") {
                insertRelation(employee = otherEmployeeFnr)

                repository.findActiveForEmployee(query()).linemanagers shouldBe emptyList()
            }

            it("returns a null name when the manager person row is missing") {
                insertRelation()

                repository.findActiveForEmployee(query()).linemanagers.single().name.shouldBeNull()
            }

            it("does not expose personal identification numbers") {
                insertPerson(managerFnr, firstName = "Kari", lastName = "Nordmann")
                insertRelation()

                val result = repository.findActiveForEmployee(query()).linemanagers.single().toString()

                result.contains(employeeFnr) shouldBe false
                result.contains(managerFnr) shouldBe false
            }

            it("maps the manager mobile number") {
                insertPerson(managerFnr, firstName = "Kari", middleName = "Mellom", lastName = "Nordmann")
                insertRelation(mobile = "90000000")

                val result = repository.findActiveForEmployee(query()).linemanagers.single()

                result.mobile shouldBe "90000000"
                result.name shouldBe Name(
                    firstName = "Kari",
                    middleName = "Mellom",
                    lastName = "Nordmann",
                )
            }

            it("returns all active relations without a result limit") {
                repeat(150) {
                    insertRelation()
                }

                repository.findActiveForEmployee(query()).linemanagers.shouldHaveSize(150)
            }

            it("returns an empty list when the employee has no relations") {
                repository.findActiveForEmployee(query()).linemanagers shouldBe emptyList()
            }

            it("returns an empty list when the employee has no relations in the organization") {
                insertRelation(organization = "123456789")

                repository.findActiveForEmployee(query(OrganizationNumber("987654321"))).linemanagers shouldBe emptyList()
            }

            it("maps one email address") {
                insertRelation(email = "leder@example.com")

                repository.findActiveForEmployee(query()).linemanagers.single().emailAddresses shouldBe
                    listOf("leder@example.com")
            }

            it("splits comma-separated email addresses") {
                insertRelation(email = "leder@example.com,annen@example.com")

                repository.findActiveForEmployee(query()).linemanagers.single().emailAddresses shouldBe
                    listOf("leder@example.com", "annen@example.com")
            }

            it("splits semicolon-separated email addresses") {
                insertRelation(email = "leder@example.com;annen@example.com")

                repository.findActiveForEmployee(query()).linemanagers.single().emailAddresses shouldBe
                    listOf("leder@example.com", "annen@example.com")
            }

            it("trims valid email addresses and discards invalid addresses") {
                insertRelation(email = " leder@example.com , invalid-address; annen@example.com; ")
                val result = repository.findActiveForEmployee(query())

                result.linemanagers.single().emailAddresses shouldBe
                    listOf("leder@example.com", "annen@example.com")
                result.discardedEmailAddressCount shouldBe 1
            }
        }
    })
