package no.nav.syfo.narmestelederrelasjon.infrastructure

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import no.nav.syfo.TestDB
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonIdent
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

private val employeeIdent = PersonIdent("12345678901")
private val organizationNumber = OrganizationNumber("123456789")
private val activeFrom = OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)

class ExposedActiveNarmestelederrelasjonRepositoryTest :
    FunSpec({
        val repository = ExposedActiveNarmestelederrelasjonRepository(TestDB.exposedDatabase)

        beforeTest {
            TestDB.clearNarmestelederData()
        }

        test("findActive returns only active relations for the given employee and organization") {
            val narmestelederId = UUID.fromString("4ffc41ed-75df-4802-9867-b5262783da5d")
            insertRelation(id = narmestelederId, from = activeFrom)
            insertRelation(managerIdent = "10987654322", from = activeFrom.minusYears(1), to = activeFrom)
            insertRelation(managerIdent = "10987654323", organization = "987654321", from = activeFrom)
            insertRelation(managerIdent = "10987654324", employee = "12345678902", from = activeFrom)

            val result = repository.findActive(employeeIdent, organizationNumber)

            result.size shouldBe 1
            result.first().id shouldBe narmestelederId
            result.first().managerIdent shouldBe PersonIdent("10987654321")
            result.first().managerEmail shouldBe "leder@example.com"
            result.first().activeFrom shouldBe activeFrom.toInstant()
        }

        test("findActive orders multiple active relations by newest aktiv_fom first") {
            insertRelation(managerIdent = "10987654321", from = activeFrom.minusMonths(1))
            insertRelation(managerIdent = "10987654322", from = activeFrom)

            repository.findActive(employeeIdent, organizationNumber).map { it.managerIdent.value } shouldBe
                listOf("10987654322", "10987654321")
        }

        test("findActive orders relations with the same aktiv_fom by id descending") {
            insertRelation(
                id = UUID.fromString("00000000-0000-0000-0000-000000000001"),
                managerIdent = "10987654321",
                from = activeFrom,
            )
            insertRelation(
                id = UUID.fromString("00000000-0000-0000-0000-000000000002"),
                managerIdent = "10987654322",
                from = activeFrom,
            )

            repository.findActive(employeeIdent, organizationNumber).map { it.managerIdent.value } shouldBe
                listOf("10987654322", "10987654321")
        }

        test("findActive returns an empty list when no relation exists") {
            repository.findActive(employeeIdent, organizationNumber) shouldBe emptyList()
        }

        test("findActive returns stored manager email without parsing it") {
            insertRelation(email = "leder@nav", from = activeFrom)

            repository.findActive(employeeIdent, organizationNumber).single().managerEmail shouldBe "leder@nav"
        }
    })

private fun insertRelation(
    id: UUID = UUID.randomUUID(),
    managerIdent: String = "10987654321",
    email: String = "leder@example.com",
    employee: String = employeeIdent.value,
    organization: String = organizationNumber.value,
    from: OffsetDateTime,
    to: OffsetDateTime? = null,
) {
    transaction(TestDB.exposedDatabase) {
        NarmestelederTable.insert {
            it[narmestelederId] = id
            it[orgnummer] = organization
            it[sykmeldtFnr] = employee
            it[narmestelederFnr] = managerIdent
            it[narmestelederTelefonnummer] = "99887766"
            it[narmestelederEpost] = email
            it[aktivFom] = from
            it[aktivTom] = to
        }
    }
}
