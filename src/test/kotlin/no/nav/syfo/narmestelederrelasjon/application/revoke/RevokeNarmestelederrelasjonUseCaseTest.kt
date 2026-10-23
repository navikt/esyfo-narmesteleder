package no.nav.syfo.narmestelederrelasjon.application.revoke

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import no.nav.esyfo.observability.testkit.RuntimeLogContract
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonalIdentificationNumber
import no.nav.syfo.narmestelederrelasjon.application.FakeNarmestelederrelasjonRepository
import no.nav.syfo.narmestelederrelasjon.application.PublishNarmestelederrelasjonRevocationCommand
import no.nav.syfo.narmestelederrelasjon.application.RecordingRevocationPublisher
import no.nav.syfo.narmestelederrelasjon.application.RevocableNarmestelederrelasjon
import no.nav.syfo.organisasjonstilgang.application.AccessToken
import no.nav.syfo.organisasjonstilgang.application.DenialReason
import no.nav.syfo.organisasjonstilgang.application.FakeOrganizationAccess
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccessResult
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccessSubject
import no.nav.syfo.platform.observability.ProductionLogCapture
import no.nav.syfo.platform.observability.ProductionLogs
import no.nav.syfo.platform.upstream.UpstreamFailure
import no.nav.syfo.platform.upstream.UpstreamFailureStage
import no.nav.syfo.platform.upstream.UpstreamName
import java.util.UUID

class RevokeNarmestelederrelasjonUseCaseTest :
    FunSpec({
        val mapper = jacksonObjectMapper()
        val contract = RuntimeLogContract.forEvents(
            revokeAccessRejected,
            rejectionReasons = setOf("MISSING_ORG_ACCESS", "MISSING_ALITINN_RESOURCE_ACCESS"),
        )
        val productionLogs = extension(ProductionLogs(RevokeNarmestelederrelasjonUseCase::class.java))
        lateinit var capture: ProductionLogCapture

        val id = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val employee = PersonalIdentificationNumber("12345678901")
        val manager = PersonalIdentificationNumber("10987654321")
        val outsider = PersonalIdentificationNumber("11111111111")
        val organization = OrganizationNumber("123456789")
        val system = OrganizationAccessSubject.LpsSystemUser("test-system-user", OrganizationNumber("999777555"))
        val privacyCanaries = listOf(employee.value, manager.value, outsider.value, organization.value, "test-token", "test-system-user")

        fun user(ident: PersonalIdentificationNumber) = OrganizationAccessSubject.PersonnelManager(ident, AccessToken("test-token"))
        fun relation(revocable: Boolean = true) = RevocableNarmestelederrelasjon(
            id = id,
            organizationNumber = organization,
            employeeIdent = employee,
            managerIdent = manager,
            isActive = revocable,
        )

        fun fixture(
            lookup: RevocableNarmestelederrelasjon? = relation(),
            access: OrganizationAccessResult = OrganizationAccessResult.Granted(organizationName = null),
        ): RevokeFixture {
            val relationId = id
            val effects = mutableListOf<String>()
            val commands = mutableListOf<PublishNarmestelederrelasjonRevocationCommand>()
            val useCase = RevokeNarmestelederrelasjonUseCase(
                repository = FakeNarmestelederrelasjonRepository(
                    revocableById = { id -> if (id == relationId) lookup else null },
                    effects = effects,
                ),
                organizationAccess = FakeOrganizationAccess(organization, access, effects),
                publisher = RecordingRevocationPublisher { command ->
                    effects.add("publish")
                    commands.add(command)
                },
            )
            return RevokeFixture(useCase, effects, commands)
        }

        beforeTest { capture = productionLogs.capture() }
        afterTest {
            capture.use { capture ->
                capture.records.forEach { line -> privacyCanaries.forEach { line shouldNotContain it } }
            }
        }

        listOf(true, false).forEach { active ->
            test("unavailable access stops outsiders before publishing an ${if (active) "active" else "inactive"} relation without a revoke rejection") {
                val failure = UpstreamFailure(
                    UpstreamName("arbeidsgiver-altinn-tilganger"),
                    UpstreamFailureStage.RESPONSE,
                    503,
                    IllegalStateException("private upstream body ${organization.value}"),
                )
                val (useCase, effects, commands) = fixture(lookup = relation(active), access = OrganizationAccessResult.Unavailable(failure))
                val result = useCase.execute(id, user(outsider))
                result shouldBe RevokeNarmestelederrelasjonResult.UpstreamUnavailable(failure)
                result.shouldBeInstanceOf<RevokeNarmestelederrelasjonResult.UpstreamUnavailable>().failure shouldBeSameInstanceAs failure
                effects shouldBe listOf("lookup", "access")
                commands shouldBe emptyList()
                contract.assertValid(capture.records, expectedCount = 0)
            }
        }

        listOf(employee to RevocationInitiator.EMPLOYEE, manager to RevocationInitiator.LINEMANAGER).forEach { (ident, initiator) ->
            test("$initiator bypasses unavailable organization access and publishes once") {
                val failure = UpstreamFailure(UpstreamName("arbeidsgiver-altinn-tilganger"), UpstreamFailureStage.RESPONSE, 503, IllegalStateException())
                val (useCase, effects, commands) = fixture(access = OrganizationAccessResult.Unavailable(failure))
                useCase.execute(id, user(ident)) shouldBe RevokeNarmestelederrelasjonResult.Revoked(initiator)
                effects shouldBe listOf("lookup", "publish")
                commands.single().initiator shouldBe initiator
            }
        }

        listOf(employee to RevocationInitiator.EMPLOYEE, manager to RevocationInitiator.LINEMANAGER).forEach { (ident, initiator) ->
            test("$initiator bypasses denied organization access and publishes once") {
                val (useCase, effects, commands) = fixture(access = OrganizationAccessResult.Denied(DenialReason.MISSING_ORGANIZATION_ACCESS))
                useCase.execute(id, user(ident)) shouldBe RevokeNarmestelederrelasjonResult.Revoked(initiator)
                effects shouldBe listOf("lookup", "publish")
                commands shouldBe listOf(PublishNarmestelederrelasjonRevocationCommand(employee, organization, initiator))
                capture.records.shouldBeEmpty()
            }
        }

        listOf(
            Triple("an authorized outsider", user(outsider), RevocationInitiator.PERSONNEL_MANAGER),
            Triple("a system user", system, RevocationInitiator.LPS),
        ).forEach { (subjectName, subject, initiator) ->
            test("$subjectName uses the distinct source $initiator") {
                val (useCase, effects, commands) = fixture()
                useCase.execute(id, subject) shouldBe RevokeNarmestelederrelasjonResult.Revoked(initiator)
                effects shouldBe listOf("lookup", "access", "publish")
                commands.single().initiator shouldBe initiator
            }
        }

        test("unknown relation never checks access, publishes or logs a revoke rejection") {
            val (useCase, effects) = fixture(lookup = null)
            useCase.execute(id, user(outsider)) shouldBe
                RevokeNarmestelederrelasjonResult.NotFound(RevokeNarmestelederrelasjonResult.Reason.RELATION_NOT_FOUND)
            effects shouldBe listOf("lookup")
            capture.records.shouldBeEmpty()
        }

        val publishedRejectionReasons = mapOf(
            DenialReason.MISSING_ORGANIZATION_ACCESS to "MISSING_ORG_ACCESS",
            DenialReason.MISSING_RESOURCE_ACCESS to "MISSING_ALITINN_RESOURCE_ACCESS",
        )
        listOf("outsider" to user(outsider), "system user" to system).forEach { (subjectName, subject) ->
            DenialReason.entries.forEach { reason ->
                val expectedRejection = publishedRejectionReasons[reason].takeIf { subject is OrganizationAccessSubject.PersonnelManager }
                test("denied $subjectName with $reason never publishes, even for an inactive relation, and logs ${expectedRejection ?: "nothing"}") {
                    val (useCase, effects, commands) = fixture(
                        lookup = relation(revocable = false),
                        access = OrganizationAccessResult.Denied(reason),
                    )
                    useCase.execute(id, subject) shouldBe
                        RevokeNarmestelederrelasjonResult.NotFound(RevokeNarmestelederrelasjonResult.Reason.ACCESS_DENIED, reason)
                    effects shouldBe listOf("lookup", "access")
                    commands shouldBe emptyList()

                    if (expectedRejection == null) {
                        capture.records.shouldBeEmpty()
                    } else {
                        contract.assertValid(capture.records, expectedCount = 1)
                        val event = mapper.readTree(capture.records.single())
                        event["event_type"].asText() shouldBe "api_request_rejected"
                        event["operation"].asText() shouldBe "revoke_linemanager"
                        event["rejection_reason"].asText() shouldBe expectedRejection
                        event["narmesteleder_id"].asText() shouldBe id.toString()
                        event["principal_type"].asText() shouldBe "UserPrincipal"
                    }
                }
            }
        }

        test("authorized already revoked relation is idempotent") {
            val (useCase, effects) = fixture(lookup = relation(revocable = false))
            useCase.execute(id, user(outsider)) shouldBe RevokeNarmestelederrelasjonResult.AlreadyRevoked
            effects shouldBe listOf("lookup", "access")
        }

        test("authorized system user gets AlreadyRevoked without publishing") {
            val (useCase, effects, commands) = fixture(lookup = relation(revocable = false))
            useCase.execute(id, system) shouldBe RevokeNarmestelederrelasjonResult.AlreadyRevoked
            effects shouldBe listOf("lookup", "access")
            commands shouldBe emptyList()
        }
    })

private data class RevokeFixture(
    val useCase: RevokeNarmestelederrelasjonUseCase,
    val effects: List<String>,
    val commands: List<PublishNarmestelederrelasjonRevocationCommand>,
)
