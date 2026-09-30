package no.nav.syfo.narmestelederrelasjon.application

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonIdent
import java.time.Instant
import java.util.UUID

class LookupActiveNarmestelederUseCaseTest :
    FunSpec({
        val employee = PersonIdent("12345678901")
        val organization = OrganizationNumber("123456789")
        val manager = PersonIdent("10987654321")
        val first = ActiveNarmestelederrelasjon(
            UUID.fromString("00000000-0000-0000-0000-000000000001"),
            manager,
            " first@example.com, , second@example.com ",
            Instant.parse("2026-01-01T00:00:00Z"),
        )
        class FakeRepository(
            var rows: List<ActiveNarmestelederrelasjon> = emptyList(),
        ) : ActiveNarmestelederrelasjonRepository {
            override suspend fun findActive(employeeIdent: PersonIdent, organizationNumber: OrganizationNumber) = rows.also {
                employeeIdent shouldBe employee
                organizationNumber shouldBe organization
            }
        }

        test("returns null when no active relation exists") {
            LookupActiveNarmestelederUseCase(FakeRepository()).execute(employee, organization) shouldBe null
        }

        test("maps the first relation and splits email addresses") {
            val result = LookupActiveNarmestelederUseCase(FakeRepository(listOf(first))).execute(employee, organization)
            result?.id shouldBe first.id
            result?.managerIdent shouldBe manager
            result?.emailAddresses?.map { it.value } shouldBe listOf("first@example.com", "second@example.com")
        }

        test("selects the first row when multiple active relations exist") {
            val second = first.copy(id = UUID.fromString("00000000-0000-0000-0000-000000000002"))
            LookupActiveNarmestelederUseCase(FakeRepository(listOf(first, second)))
                .execute(employee, organization)?.id shouldBe first.id
        }
    })
