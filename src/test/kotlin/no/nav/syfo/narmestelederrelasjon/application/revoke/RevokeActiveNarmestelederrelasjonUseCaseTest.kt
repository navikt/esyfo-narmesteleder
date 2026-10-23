package no.nav.syfo.narmestelederrelasjon.application.revoke

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonalIdentificationNumber
import no.nav.syfo.narmestelederrelasjon.application.ActiveNarmestelederrelasjon
import no.nav.syfo.narmestelederrelasjon.application.FakeActiveNarmestelederrelasjonRepository
import no.nav.syfo.narmestelederrelasjon.application.PersonDetails
import no.nav.syfo.narmestelederrelasjon.application.PersonLookupResult
import no.nav.syfo.narmestelederrelasjon.application.PublishNarmestelederrelasjonRevocationCommand
import no.nav.syfo.narmestelederrelasjon.application.RecordingRevocationPublisher
import no.nav.syfo.narmestelederrelasjon.domain.LastNameMatch
import no.nav.syfo.narmestelederrelasjon.domain.PersonNameDetails
import no.nav.syfo.narmestelederrelasjon.domain.RegisteredName
import no.nav.syfo.organisasjonstilgang.application.AccessToken
import no.nav.syfo.organisasjonstilgang.application.DenialReason
import no.nav.syfo.organisasjonstilgang.application.FakeOrganizationAccess
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccess
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccessResult
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccessSubject
import no.nav.syfo.platform.upstream.UpstreamError
import no.nav.syfo.platform.upstream.UpstreamFailure
import no.nav.syfo.platform.upstream.UpstreamFailureStage
import no.nav.syfo.platform.upstream.UpstreamName
import java.time.Instant
import java.util.UUID

private val employee = PersonalIdentificationNumber("12345678901")
private val resolved = PersonalIdentificationNumber("12345678902")
private val organization = OrganizationNumber("123456789")
private val lps = OrganizationAccessSubject.LpsSystemUser("system-user", OrganizationNumber("999777555"))

class RevokeActiveNarmestelederrelasjonUseCaseTest :
    FunSpec({
        test("unavailable access stops before person lookup name validation active lookup and publishing") {
            val failure = UpstreamFailure(UpstreamName("arbeidsgiver-altinn-tilganger"), UpstreamFailureStage.RESPONSE, 503, IllegalStateException())
            val fixture = RevokeActiveFixture(access = { _, _ -> OrganizationAccessResult.Unavailable(failure) })
            val result = fixture.execute()
            result shouldBe RevokeActiveNarmestelederrelasjonResult.UpstreamUnavailable(failure)
            result.shouldBeInstanceOf<RevokeActiveNarmestelederrelasjonResult.UpstreamUnavailable>().failure shouldBeSameInstanceAs failure
            fixture.calls shouldBe listOf("access")
            fixture.published shouldBe emptyList()
        }

        test("access denial stops before PDL and publishing") {
            val fixture = RevokeActiveFixture(access = { _, _ -> OrganizationAccessResult.Denied(DenialReason.MISSING_ORGANIZATION_ACCESS) })
            fixture.execute(OrganizationAccessSubject.PersonnelManager(employee, AccessToken("test"))) shouldBe
                RevokeActiveNarmestelederrelasjonResult.AccessDenied(DenialReason.MISSING_ORGANIZATION_ACCESS, organization)
            fixture.calls shouldBe listOf("access")
        }

        test("missing employee stops before name validation and active relation lookup") {
            val fixture = RevokeActiveFixture(person = null)
            fixture.execute() shouldBe RevokeActiveNarmestelederrelasjonResult.EmployeeNotFound
            fixture.calls shouldBe listOf("access", "person")
        }

        test("Unavailable stops before name validation active lookup and publishing") {
            val failure = UpstreamFailure(UpstreamName("pdl"), UpstreamFailureStage.RESPONSE, 200, errors = listOf(UpstreamError("server_error")))
            val fixture = RevokeActiveFixture(unavailable = failure)
            fixture.execute() shouldBe RevokeActiveNarmestelederrelasjonResult.UpstreamUnavailable(failure)
            fixture.calls shouldBe listOf("access", "person")
            fixture.published shouldBe emptyList()
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

        listOf(
            lps to RevocationInitiator.LPS,
            OrganizationAccessSubject.PersonnelManager(resolved, AccessToken("test")) to RevocationInitiator.EMPLOYEE,
            OrganizationAccessSubject.PersonnelManager(employee, AccessToken("test")) to RevocationInitiator.PERSONNEL_MANAGER,
        ).forEach { (subject, initiator) ->
            test("a revocation by $initiator uses the resolved ident and the expected Kafka initiator") {
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
    private val access: OrganizationAccess = FakeOrganizationAccess(organization),
    name: String = "Hansen",
    registeredNames: List<RegisteredName> = listOf(RegisteredName(name)),
    person: PersonDetails? = PersonDetails(resolved, PersonNameDetails("Test", name, registeredNames = registeredNames)),
    private val active: Boolean = true,
    unavailable: UpstreamFailure? = null,
) {
    val calls = mutableListOf<String>()
    val matches = mutableListOf<LastNameMatch>()
    private val repository = FakeActiveNarmestelederrelasjonRepository(
        rows = if (active) listOf(ActiveNarmestelederrelasjon(UUID(0, 1), employee, "invalid", Instant.EPOCH)) else emptyList(),
        effects = calls,
    )
    val lookups get() = repository.lookups
    val published = mutableListOf<PublishNarmestelederrelasjonRevocationCommand>()
    private val useCase = RevokeActiveNarmestelederrelasjonUseCase(
        { subject, org ->
            calls += "access"
            access.evaluate(subject, org)
        },
        {
            calls += "person"
            unavailable?.let { PersonLookupResult.Unavailable(it) } ?: person?.let { PersonLookupResult.Found(it) } ?: PersonLookupResult.NotFound
        },
        {
            calls += "name"
            matches += it
        },
        repository,
        RecordingRevocationPublisher {
            calls += "publish"
            published += it
        },
    )

    suspend fun execute(subject: OrganizationAccessSubject = lps) = useCase.execute(
        RevokeActiveNarmestelederrelasjonCommand(subject, employee, organization, "Hansen"),
    )
}
