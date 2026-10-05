package no.nav.syfo.narmestelederrelasjon.infrastructure

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import no.nav.syfo.TestDB
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonIdent
import no.nav.syfo.narmesteleder.exposed.PersonTable
import no.nav.syfo.narmestelederrelasjon.application.ListActiveNarmesteledereForEmployeeUseCase
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

private val employee = PersonIdent("12345678910")
private val now = OffsetDateTime.parse("2026-02-01T12:00:00Z")

class ExposedEmployeeNarmestelederrelasjonRepositoryTest :
    FunSpec({
        val repository = ExposedEmployeeNarmestelederrelasjonRepository(
            TestDB.exposedDatabase,
            Clock.fixed(now.toInstant(), ZoneOffset.UTC),
        )
        val useCase = ListActiveNarmesteledereForEmployeeUseCase(repository)

        beforeTest {
            TestDB.clearNarmestelederData()
            TestDB.clearPersonData()
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

        test("excludes relations with an end date") {
            insertEmployeeRelation(to = now.minusSeconds(1))
            repository.findActive(employee, null) shouldBe emptyList()
        }

        test("excludes relations that start in the future") {
            insertEmployeeRelation(from = now.plusDays(1))
            repository.findActive(employee, null) shouldBe emptyList()
        }

        test("includes relations that started immediately before now") {
            insertEmployeeRelation(from = now.minusSeconds(1))
            repository.findActive(employee, null).shouldHaveSize(1)
        }

        test("includes relations that start exactly now") {
            insertEmployeeRelation(from = now)
            repository.findActive(employee, null).shouldHaveSize(1)
        }

        test("excludes relations for another employee") {
            insertEmployeeRelation(employeeIdent = "12345678911")
            repository.findActive(employee, null) shouldBe emptyList()
        }

        test("keeps the relation with null raw names when the manager person row is missing") {
            insertEmployeeRelation()
            val result = repository.findActive(employee, null).single()
            result.managerFirstName shouldBe null
            result.managerMiddleName shouldBe null
            result.managerLastName shouldBe null
            useCase.execute(employee, null).narmesteledere.single().name shouldBe null
        }

        test("does not expose personal identification numbers") {
            insertManagerPerson()
            insertEmployeeRelation()
            val result = repository.findActive(employee, null).single().toString()
            result.contains(employee.value) shouldBe false
            result.contains("10987654321") shouldBe false
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

        test("maps one email address without parsing it") {
            insertEmployeeRelation(email = "manager@example.com")
            repository.findActive(employee, null).single().managerEmail shouldBe "manager@example.com"
        }

        test("preserves comma-separated emails and the use case splits them") {
            val raw = "first@example.com,second@example.com"
            insertEmployeeRelation(email = raw)
            repository.findActive(employee, null).single().managerEmail shouldBe raw
            useCase.execute(employee, null).narmesteledere.single().emailAddresses.map { it.value } shouldBe listOf("first@example.com", "second@example.com")
        }

        test("preserves semicolon-separated emails and the use case splits them") {
            val raw = "first@example.com;second@example.com"
            insertEmployeeRelation(email = raw)
            repository.findActive(employee, null).single().managerEmail shouldBe raw
            useCase.execute(employee, null).narmesteledere.single().emailAddresses.map { it.value } shouldBe listOf("first@example.com", "second@example.com")
        }

        test("preserves invalid raw emails and the use case trims and discards them") {
            val raw = " first@example.com , invalid-address; second@example.com; "
            insertEmployeeRelation(email = raw)
            repository.findActive(employee, null).single().managerEmail shouldBe raw
            val result = useCase.execute(employee, null)
            result.narmesteledere.single().emailAddresses.map { it.value } shouldBe listOf("first@example.com", "second@example.com")
            result.discardedEmailAddressCount shouldBe 1
        }
    })

private fun insertManagerPerson() {
    transaction(TestDB.exposedDatabase) {
        PersonTable.insert {
            it[fnr] = "10987654321"
            it[status] = "ENRICHED"
            it[fornavn] = "Manager"
            it[mellomnavn] = "Middle"
            it[etternavn] = "Person"
        }
    }
}

private fun insertEmployeeRelation(
    id: UUID = UUID.randomUUID(),
    organization: String = "123456789",
    employeeIdent: String = employee.value,
    from: OffsetDateTime = now.minusDays(1),
    to: OffsetDateTime? = null,
    email: String = "manager@example.com",
    mobile: String = "99999999",
) {
    transaction(TestDB.exposedDatabase) {
        NarmestelederTable.insert {
            it[narmestelederId] = id
            it[orgnummer] = organization
            it[sykmeldtFnr] = employeeIdent
            it[narmestelederFnr] = "10987654321"
            it[narmestelederEpost] = email
            it[narmestelederTelefonnummer] = mobile
            it[aktivFom] = from
            it[aktivTom] = to
        }
    }
}
