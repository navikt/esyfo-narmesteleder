package no.nav.syfo.narmestelederrelasjon.infrastructure

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import no.nav.syfo.TestDB
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonalIdentificationNumber
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

private val employee = PersonalIdentificationNumber("12345678910")
private val now = OffsetDateTime.parse("2026-02-01T12:00:00Z")

class ExposedEmployeeNarmestelederrelasjonRepositoryTest :
    FunSpec({
        val repository = ExposedEmployeeNarmestelederrelasjonRepository(
            TestDB.exposedDatabase,
            Clock.fixed(now.toInstant(), ZoneOffset.UTC),
        )

        beforeTest {
            TestDB.clearAllData()
        }

        test("returns active relations across organizations sorted by organization number") {
            insertEmployeeRelation(organization = "987654321", id = UUID(0, 2))
            insertEmployeeRelation(id = UUID(0, 1))
            val result = repository.findActive(employee, null)
            result.map { it.organizationNumber.value } shouldBe listOf("123456789", "987654321")
            result.map { it.id } shouldBe listOf(UUID(0, 1), UUID(0, 2))
            result.first().activeFrom shouldBe now.minusDays(1).toInstant()
        }

        test("orders by newest activeFrom within an organization and then database row id ascending") {
            insertEmployeeRelation(id = UUID(0, 3), from = now.minusDays(2))
            insertEmployeeRelation(id = UUID(0, 2), from = now.minusDays(1))
            insertEmployeeRelation(id = UUID(0, 1), from = now.minusDays(1))
            repository.findActive(employee, null).map { it.id } shouldBe listOf(UUID(0, 2), UUID(0, 1), UUID(0, 3))
        }

        test("filters by organization number when provided") {
            insertEmployeeRelation()
            insertEmployeeRelation(organization = "987654321")
            repository.findActive(employee, OrganizationNumber("987654321")).map { it.organizationNumber.value } shouldBe listOf("987654321")
        }

        test("returns only the employee's relations that have started and not ended at now") {
            insertEmployeeRelation(id = UUID(0, 1), to = now.minusSeconds(1))
            insertEmployeeRelation(id = UUID(0, 2), from = now.plusDays(1))
            insertEmployeeRelation(id = UUID(0, 3), from = now.minusSeconds(1))
            insertEmployeeRelation(id = UUID(0, 4), from = now)
            insertEmployeeRelation(id = UUID(0, 5), employeeIdent = "12345678911")
            repository.findActive(employee, null).map { it.id } shouldBe listOf(UUID(0, 4), UUID(0, 3))
        }

        test("keeps the relation with null raw names when the manager person row is missing") {
            insertEmployeeRelation()
            val result = repository.findActive(employee, null).single()
            result.managerFirstName shouldBe null
            result.managerMiddleName shouldBe null
            result.managerLastName shouldBe null
        }

        test("maps manager mobile and raw names") {
            insertManagerPerson()
            insertEmployeeRelation(mobile = "90000000")
            val result = repository.findActive(employee, null).single()
            result.managerMobile shouldBe "90000000"
            result.managerFirstName shouldBe "Manager"
            result.managerMiddleName shouldBe "Middle"
            result.managerLastName shouldBe "Person"
        }

        test("returns all active relations without a result limit") {
            repeat(150) { insertEmployeeRelation() }
            repository.findActive(employee, null).shouldHaveSize(150)
        }

        test("returns an empty list when the employee has no relations") {
            repository.findActive(employee, null) shouldBe emptyList()
        }

        test("returns an empty list when the organization has no matches") {
            insertEmployeeRelation()
            repository.findActive(employee, OrganizationNumber("987654321")) shouldBe emptyList()
        }

        test("returns the raw manager email without parsing separators, invalid addresses or whitespace") {
            val raw = " first@example.com , invalid-address; second@example.com; "
            insertEmployeeRelation(email = raw)
            repository.findActive(employee, null).single().managerEmail shouldBe raw
        }
    })

private fun insertManagerPerson() = insertPerson("10987654321", firstName = "Manager", middleName = "Middle", lastName = "Person")

private fun insertEmployeeRelation(
    id: UUID = UUID.randomUUID(),
    organization: String = "123456789",
    employeeIdent: String = employee.value,
    from: OffsetDateTime = now.minusDays(1),
    to: OffsetDateTime? = null,
    email: String = "manager@example.com",
    mobile: String = "99999999",
) {
    insertRelation(
        narmestelederId = id,
        employeeIdent = employeeIdent,
        organizationNumber = organization,
        activeFrom = from,
        activeTo = to,
        email = email,
        mobile = mobile,
    )
}
