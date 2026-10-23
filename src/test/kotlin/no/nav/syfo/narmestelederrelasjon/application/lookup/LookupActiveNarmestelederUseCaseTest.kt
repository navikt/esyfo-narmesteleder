package no.nav.syfo.narmestelederrelasjon.application.lookup

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonalIdentificationNumber
import no.nav.syfo.narmestelederrelasjon.application.ActiveNarmestelederrelasjon
import no.nav.syfo.narmestelederrelasjon.application.DiscardedEmailAddressMetrics
import no.nav.syfo.narmestelederrelasjon.application.FakeActiveNarmestelederrelasjonRepository
import no.nav.syfo.narmestelederrelasjon.application.RecordingDiscardedEmailAddressMetrics
import java.time.Instant
import java.util.UUID

class LookupActiveNarmestelederUseCaseTest :
    FunSpec({
        val employee = PersonalIdentificationNumber("12345678901")
        val organization = OrganizationNumber("123456789")
        val manager = PersonalIdentificationNumber("10987654321")
        val first = ActiveNarmestelederrelasjon(
            UUID.fromString("00000000-0000-0000-0000-000000000001"),
            manager,
            " first@example.com, , second@example.com ",
            Instant.parse("2026-01-01T00:00:00Z"),
        )
        var repository = FakeActiveNarmestelederrelasjonRepository()

        afterTest {
            repository.lookups.forEach { it shouldBe (employee to organization) }
        }

        fun useCase(
            rows: List<ActiveNarmestelederrelasjon>,
            metrics: DiscardedEmailAddressMetrics = DiscardedEmailAddressMetrics {},
        ) = LookupActiveNarmestelederUseCase(FakeActiveNarmestelederrelasjonRepository(rows).also { repository = it }, metrics)

        test("returns null when no active relation exists") {
            val metrics = RecordingDiscardedEmailAddressMetrics()
            useCase(emptyList(), metrics).execute(employee, organization) shouldBe null
            metrics.recorded.shouldBeEmpty()
        }

        test("maps the first relation and splits email addresses") {
            val metrics = RecordingDiscardedEmailAddressMetrics()
            val result = useCase(listOf(first), metrics).execute(employee, organization)
            result?.id shouldBe first.id
            result?.managerIdent shouldBe manager
            result?.emailAddresses?.map { it.value } shouldBe listOf("first@example.com", "second@example.com")
            metrics.recorded shouldBe listOf(0)
        }

        test("discards invalid email addresses and records how many were discarded") {
            val metrics = RecordingDiscardedEmailAddressMetrics()
            val relation = first.copy(managerEmail = "first@example.com;not-an-email, second@example.com,also invalid@example.com")
            val result = useCase(listOf(relation), metrics).execute(employee, organization)
            result?.id shouldBe first.id
            result?.emailAddresses?.map { it.value } shouldBe listOf("first@example.com", "second@example.com")
            metrics.recorded shouldBe listOf(2)
        }

        test("returns the line manager with no email addresses when all are invalid") {
            val metrics = RecordingDiscardedEmailAddressMetrics()
            val relation = first.copy(managerEmail = "not-an-email;also-invalid@")
            val result = useCase(listOf(relation), metrics).execute(employee, organization)
            result?.id shouldBe first.id
            result?.managerIdent shouldBe manager
            result?.emailAddresses?.shouldBeEmpty()
            metrics.recorded shouldBe listOf(2)
        }

        test("selects the first row when multiple active relations exist") {
            val second = first.copy(id = UUID.fromString("00000000-0000-0000-0000-000000000002"))
            useCase(listOf(first, second)).execute(employee, organization)?.id shouldBe first.id
        }
    })
