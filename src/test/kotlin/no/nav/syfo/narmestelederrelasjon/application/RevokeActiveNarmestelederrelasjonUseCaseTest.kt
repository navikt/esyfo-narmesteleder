package no.nav.syfo.narmestelederrelasjon.application

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonIdent
import no.nav.syfo.narmestelederrelasjon.domain.LastNameMatch
import no.nav.syfo.narmestelederrelasjon.domain.PersonNameDetails
import no.nav.syfo.narmestelederrelasjon.domain.RegisteredName
import no.nav.syfo.organisasjonstilgang.application.AccessToken
import no.nav.syfo.organisasjonstilgang.application.DenialReason
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccess
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccessResult
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccessSubject
import java.time.Instant
import java.util.UUID

private val employee = PersonIdent("12345678901")
private val resolved = PersonIdent("12345678902")
private val organization = OrganizationNumber("123456789")
private val lps = OrganizationAccessSubject.LpsSystemUser("system-user", organization)

class RevokeActiveNarmestelederrelasjonUseCaseTest :
    FunSpec({
        test("access denial stops before PDL and publishing") {
            val fixture = RevokeActiveFixture(access = OrganizationAccess { _, _ -> OrganizationAccessResult.Denied(DenialReason.MISSING_ORGANIZATION_ACCESS) })
            fixture.execute(OrganizationAccessSubject.PersonnelManager(employee, AccessToken("test"))) shouldBe
                RevokeActiveNarmestelederrelasjonResult.AccessDenied(DenialReason.MISSING_ORGANIZATION_ACCESS, organization)
            fixture.calls shouldBe listOf("access")
        }

        test("missing employee stops before name validation and active relation lookup") {
            val fixture = RevokeActiveFixture(person = null)
            fixture.execute() shouldBe RevokeActiveNarmestelederrelasjonResult.EmployeeNotFound
            fixture.calls shouldBe listOf("access", "person")
        }

        test("last-name mismatch stops before active relation lookup") {
            val fixture = RevokeActiveFixture(name = "Other")
            fixture.execute() shouldBe RevokeActiveNarmestelederrelasjonResult.EmployeeNameMismatch
            fixture.calls shouldBe listOf("access", "person", "name")
            fixture.matches.single().shouldBeInstanceOf<LastNameMatch.NoMatch>().hasParallelNames shouldBe false
        }

        test("no active relation does not publish") {
            val fixture = RevokeActiveFixture(active = false)
            fixture.execute() shouldBe RevokeActiveNarmestelederrelasjonResult.NoActiveRelation
            fixture.calls shouldBe listOf("access", "person", "name", "active")
        }

        test("LPS, employee and personnel manager use resolved ident and expected Kafka initiator") {
            listOf(
                lps to RevocationInitiator.LPS,
                OrganizationAccessSubject.PersonnelManager(resolved, AccessToken("test")) to RevocationInitiator.EMPLOYEE,
                OrganizationAccessSubject.PersonnelManager(employee, AccessToken("test")) to RevocationInitiator.PERSONNEL_MANAGER,
            ).forEach { (subject, initiator) ->
                val fixture = RevokeActiveFixture()
                fixture.execute(subject) shouldBe RevokeActiveNarmestelederrelasjonResult.Revoked(initiator)
                fixture.calls shouldBe listOf("access", "person", "name", "active", "publish")
                fixture.published.single() shouldBe PublishNarmestelederrelasjonRevocationCommand(resolved, organization, initiator)
                fixture.lookups shouldBe listOf(resolved to organization)
                fixture.matches.single() shouldBe LastNameMatch.Exact(false)
            }
        }

        test("parallel registered name records matching metrics before publishing") {
            val fixture = RevokeActiveFixture(name = "Other", registeredNames = listOf(RegisteredName("Other"), RegisteredName("Hansen")))
            fixture.execute() shouldBe RevokeActiveNarmestelederrelasjonResult.Revoked(RevocationInitiator.LPS)
            fixture.matches shouldBe listOf(LastNameMatch.Exact(true))
        }
    })

private class RevokeActiveFixture(
    private val access: OrganizationAccess = OrganizationAccess { _, _ -> OrganizationAccessResult.Granted(organizationName = null) },
    name: String = "Hansen",
    registeredNames: List<RegisteredName> = listOf(RegisteredName(name)),
    person: PersonDetails? = PersonDetails(resolved, PersonNameDetails("Test", name, registeredNames = registeredNames)),
    private val active: Boolean = true,
) {
    val calls = mutableListOf<String>()
    val matches = mutableListOf<LastNameMatch>()
    val lookups = mutableListOf<Pair<PersonIdent, OrganizationNumber>>()
    val published = mutableListOf<PublishNarmestelederrelasjonRevocationCommand>()
    private val useCase = RevokeActiveNarmestelederrelasjonUseCase(
        OrganizationAccess { subject, org ->
            calls += "access"
            access.evaluate(subject, org)
        },
        PersonLookup {
            calls += "person"
            person
        },
        NameValidationMetrics {
            calls += "name"
            matches += it
        },
        object : ActiveNarmestelederrelasjonRepository {
            override suspend fun findActive(employeeIdent: PersonIdent, organizationNumber: OrganizationNumber): List<ActiveNarmestelederrelasjon> {
                calls += "active"
                lookups += employeeIdent to organizationNumber
                return if (active) listOf(ActiveNarmestelederrelasjon(UUID(0, 1), employee, "invalid", Instant.EPOCH)) else emptyList()
            }
        },
        PublishNarmestelederrelasjonRevocation {
            calls += "publish"
            published += it
        },
    )

    suspend fun execute(subject: OrganizationAccessSubject = lps) = useCase.execute(
        RevokeActiveNarmestelederrelasjonCommand(subject, employee, organization, "Hansen"),
    )
}
