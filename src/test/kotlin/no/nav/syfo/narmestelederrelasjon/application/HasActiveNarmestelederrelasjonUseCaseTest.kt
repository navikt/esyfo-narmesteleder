package no.nav.syfo.narmestelederrelasjon.application

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonalIdentificationNumber
import java.time.Instant
import java.util.UUID

class HasActiveNarmestelederrelasjonUseCaseTest :
    FunSpec({
        test("returns true when the repository finds an active relation for the employee and organization") {
            val employee = PersonalIdentificationNumber("12345678901")
            val organization = OrganizationNumber("123456789")
            val repository = FakeActiveNarmestelederrelasjonRepository().apply {
                rows = listOf(
                    ActiveNarmestelederrelasjon(
                        id = UUID.randomUUID(),
                        managerIdent = PersonalIdentificationNumber("10987654321"),
                        managerEmail = "manager@example.com",
                        activeFrom = Instant.parse("2026-01-01T00:00:00Z"),
                    ),
                )
            }

            HasActiveNarmestelederrelasjonUseCase(repository).execute(employee, organization) shouldBe true
            repository.lookups shouldBe listOf(employee to organization)
        }

        test("returns false when the repository finds no active relation") {
            val employee = PersonalIdentificationNumber("12345678901")
            val organization = OrganizationNumber("123456789")
            val repository = FakeActiveNarmestelederrelasjonRepository()

            HasActiveNarmestelederrelasjonUseCase(repository).execute(employee, organization) shouldBe false
            repository.lookups shouldBe listOf(employee to organization)
        }
    })
