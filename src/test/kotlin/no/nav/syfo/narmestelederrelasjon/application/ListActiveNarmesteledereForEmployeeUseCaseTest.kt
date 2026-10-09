package no.nav.syfo.narmestelederrelasjon.application

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonIdent
import java.time.Instant
import java.util.UUID

class ListActiveNarmesteledereForEmployeeUseCaseTest :
    FunSpec({
        val employee = PersonIdent("12345678901")
        val organization = OrganizationNumber("123456789")
        val repository = RecordingEmployeeRepository()
        val discardedEmailAddressMetrics = RecordingDiscardedEmailAddressMetrics()
        val useCase = ListActiveNarmesteledereForEmployeeUseCase(repository, discardedEmailAddressMetrics)

        beforeTest {
            repository.rows = emptyList()
            repository.lookups.clear()
            discardedEmailAddressMetrics.recorded.clear()
        }

        test("passes employee identity without an organization filter") {
            useCase.execute(employee, null)
            repository.lookups shouldBe listOf(employee to null)
        }

        test("passes the organization filter") {
            useCase.execute(employee, organization)
            repository.lookups shouldBe listOf(employee to organization)
        }

        test("preserves repository order and maps identity, organization, activeFrom and mobile") {
            val first = employeeRelation(UUID(0, 2))
            val second = employeeRelation(UUID(0, 1)).copy(organizationNumber = OrganizationNumber("987654321"))
            repository.rows = listOf(first, second)
            val result = useCase.execute(employee, null)
            result.map { it.id } shouldBe listOf(first.id, second.id)
            result.map { it.organizationNumber } shouldBe listOf(first.organizationNumber, second.organizationNumber)
            result.first().activeFrom shouldBe first.activeFrom
            result.first().mobile shouldBe first.managerMobile
        }

        test("returns an empty result and records no discarded addresses") {
            useCase.execute(employee, null) shouldBe emptyList()
            discardedEmailAddressMetrics.recorded shouldBe listOf(0)
        }

        test("maps full names including optional middle name") {
            repository.rows = listOf(employeeRelation(), employeeRelation().copy(managerMiddleName = null))
            useCase.execute(employee, null).map { it.name } shouldBe listOf(
                NarmestelederName("Manager", "Middle", "Person"),
                NarmestelederName("Manager", null, "Person"),
            )
        }

        test("omits name when first or last name is missing") {
            repository.rows = listOf(
                employeeRelation().copy(managerFirstName = null),
                employeeRelation().copy(managerLastName = null),
                employeeRelation().copy(managerFirstName = null, managerLastName = null),
            )
            useCase.execute(employee, null).map { it.name } shouldBe listOf(null, null, null)
        }

        test("splits emails on comma and semicolon, trims and records the summed discarded addresses") {
            repository.rows = listOf(
                employeeRelation().copy(managerEmail = " first@example.com, invalid; second@example.com; "),
                employeeRelation().copy(managerEmail = "bad;also-bad,third@example.com"),
            )
            val result = useCase.execute(employee, null)
            result.map { manager -> manager.emailAddresses.map { it.value } } shouldBe listOf(
                listOf("first@example.com", "second@example.com"),
                listOf("third@example.com"),
            )
            discardedEmailAddressMetrics.recorded shouldBe listOf(3)
        }
    })

private class RecordingEmployeeRepository : EmployeeNarmestelederrelasjonRepository {
    var rows = emptyList<EmployeeNarmestelederrelasjon>()
    val lookups = mutableListOf<Pair<PersonIdent, OrganizationNumber?>>()

    override suspend fun findActive(employeeIdent: PersonIdent, organizationNumber: OrganizationNumber?): List<EmployeeNarmestelederrelasjon> {
        lookups.add(employeeIdent to organizationNumber)
        return rows
    }
}

private fun employeeRelation(id: UUID = UUID(0, 1)) = EmployeeNarmestelederrelasjon(
    id = id,
    organizationNumber = OrganizationNumber("123456789"),
    activeFrom = Instant.parse("2026-01-01T00:00:00Z"),
    managerFirstName = "Manager",
    managerMiddleName = "Middle",
    managerLastName = "Person",
    managerEmail = "manager@example.com",
    managerMobile = "99999999",
)
