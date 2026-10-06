package no.nav.syfo.narmestelederrelasjon.application

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonIdent
import no.nav.syfo.organisasjonstilgang.application.DenialReason
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccess
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccessResult
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccessSubject
import java.time.Instant
import java.util.UUID

private val organizationNumber = OrganizationNumber("123456789")
private val subject = OrganizationAccessSubject.LpsSystemUser("synthetic-system-user", organizationNumber)

class SearchActiveNarmestelederrelasjonerUseCaseTest :
    FunSpec({
        val access = SearchOrganizationAccess()
        val repository = SearchRepository()
        val useCase = SearchActiveNarmestelederrelasjonerUseCase(access, repository)

        beforeTest {
            access.result = OrganizationAccessResult.Granted(null)
            access.calls.clear()
            repository.calls.clear()
            repository.rows = emptyList()
        }

        DenialReason.entries.forEach { reason ->
            test("denial $reason wins over invalid text and token without querying") {
                access.result = OrganizationAccessResult.Denied(reason)
                useCase.execute(
                    subject,
                    SearchActiveNarmestelederrelasjonerCommand(organizationNumber, text = "a".repeat(51), pageToken = "invalid"),
                ) shouldBe SearchActiveNarmestelederrelasjonerResult.AccessDenied(reason)
                access.calls shouldBe listOf(subject to organizationNumber)
                repository.calls.shouldBeEmpty()
            }
        }

        test("text validation precedes token validation after access") {
            useCase.execute(
                subject,
                SearchActiveNarmestelederrelasjonerCommand(organizationNumber, text = "a".repeat(51), pageToken = "invalid"),
            ) shouldBe SearchActiveNarmestelederrelasjonerResult.InvalidText
            access.calls shouldBe listOf(subject to organizationNumber)
            repository.calls.shouldBeEmpty()
        }

        listOf(null to null, "" to null, " \t\n " to null, "  Kari Nordmann  " to "Kari Nordmann", "a".repeat(50) to "a".repeat(50)).forEach { (raw, normalized) ->
            test("normalizes text ${raw?.length} to length ${normalized?.length}") {
                useCase.execute(subject, SearchActiveNarmestelederrelasjonerCommand(organizationNumber, text = raw))
                repository.calls.single().text shouldBe normalized
                repository.calls.single().nationalIdentificationNumber shouldBe null
            }
        }

        test("normalizes eleven-digit text to an employee-or-manager ident filter") {
            useCase.execute(subject, SearchActiveNarmestelederrelasjonerCommand(organizationNumber, text = " 12345678910 "))
            repository.calls.single().nationalIdentificationNumber shouldBe PersonIdent("12345678910")
            repository.calls.single().text shouldBe null
        }

        listOf("1234567891", "123456789100", "1234567891a").forEach { text ->
            test("keeps non-ident text of length ${text.length} ending ${text.last()} as name filter") {
                useCase.execute(subject, SearchActiveNarmestelederrelasjonerCommand(organizationNumber, text = text))
                repository.calls.single().text shouldBe text
                repository.calls.single().nationalIdentificationNumber shouldBe null
            }
        }

        listOf(null to 50, 0 to 50, -1 to 50, 51 to 50, Int.MAX_VALUE to 50, 1 to 1, 49 to 49, 50 to 50).forEach { (raw, normalized) ->
            test("normalizes pageSize $raw to $normalized") {
                val result = useCase.execute(subject, SearchActiveNarmestelederrelasjonerCommand(organizationNumber, pageSize = raw))
                    as SearchActiveNarmestelederrelasjonerResult.Success
                result.pageSize shouldBe normalized
                repository.calls.single().pageSize shouldBe normalized
            }
        }

        test("passes all explicit filters to the port after evaluating access") {
            val employee = PersonIdent("12345678910")
            val manager = PersonIdent("10987654321")
            useCase.execute(
                subject,
                SearchActiveNarmestelederrelasjonerCommand(organizationNumber, manager, employee, hasActiveSickLeave = false),
            )
            access.calls shouldBe listOf(subject to organizationNumber)
            repository.calls.single() shouldBe NarmestelederrelasjonSearchQuery(
                orgNumber = organizationNumber,
                managerNationalIdentificationNumber = manager,
                employeeNationalIdentificationNumber = employee,
                hasActiveSickLeave = false,
                pageSize = 50,
            )
        }

        test("passes a decoded pageToken to the repository as cursor") {
            val cursor = LinemanagerSearchCursor("ø:ystein", null, 42)
            useCase.execute(subject, SearchActiveNarmestelederrelasjonerCommand(organizationNumber, pageToken = cursor.toOpaqueCursor()))
            repository.calls.single().cursor shouldBe cursor
        }

        test("rejects an invalid pageToken without querying") {
            useCase.execute(subject, SearchActiveNarmestelederrelasjonerCommand(organizationNumber, pageToken = "invalid!")) shouldBe
                SearchActiveNarmestelederrelasjonerResult.InvalidPageToken
            access.calls.size shouldBe 1
            repository.calls.shouldBeEmpty()
        }

        test("drops the lookahead row and uses the last visible cursor only when there is more") {
            repository.rows = listOf(searchRow(1), searchRow(2), searchRow(3))
            val result = useCase.execute(subject, SearchActiveNarmestelederrelasjonerCommand(organizationNumber, pageSize = 2))
                as SearchActiveNarmestelederrelasjonerResult.Success
            result.linemanagers shouldBe repository.rows.take(2).map { it.linemanager }
            result.hasMore shouldBe true
            result.nextPageToken shouldBe repository.rows[1].cursor.toOpaqueCursor()
        }

        listOf(0, 1, 2).forEach { size ->
            test("returns no next token for a final page of $size rows") {
                repository.rows = (1..size).map(::searchRow)
                val result = useCase.execute(subject, SearchActiveNarmestelederrelasjonerCommand(organizationNumber, pageSize = 2))
                    as SearchActiveNarmestelederrelasjonerResult.Success
                result.linemanagers shouldBe repository.rows.map { it.linemanager }
                result.hasMore shouldBe false
                result.nextPageToken shouldBe null
            }
        }
    })

private class SearchOrganizationAccess : OrganizationAccess {
    var result: OrganizationAccessResult = OrganizationAccessResult.Granted(null)
    val calls = mutableListOf<Pair<OrganizationAccessSubject, OrganizationNumber>>()

    override suspend fun evaluate(subject: OrganizationAccessSubject, organizationNumber: OrganizationNumber): OrganizationAccessResult {
        calls.add(subject to organizationNumber)
        return result
    }
}

private class SearchRepository : NarmestelederrelasjonSearchRepository {
    var rows = emptyList<NarmestelederrelasjonSearchRow>()
    val calls = mutableListOf<NarmestelederrelasjonSearchQuery>()

    override suspend fun search(query: NarmestelederrelasjonSearchQuery): List<NarmestelederrelasjonSearchRow> {
        calls.add(query)
        return rows
    }
}

private fun searchRow(id: Int) = NarmestelederrelasjonSearchRow(
    cursor = LinemanagerSearchCursor("ola", "nordmann", id),
    linemanager = SearchNarmestelederrelasjon(
        id = UUID(0, id.toLong()),
        orgNumber = organizationNumber,
        activeFrom = Instant.parse("2026-01-01T00:00:00Z"),
        employee = SearchPerson(PersonIdent("12345678910"), null),
        manager = SearchManager(PersonIdent("10987654321"), null, "manager@example.com", "99999999"),
    ),
)
