package no.nav.syfo.narmestelederrelasjon.application

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonIdent
import no.nav.syfo.organisasjonstilgang.application.AccessToken
import no.nav.syfo.organisasjonstilgang.application.DenialReason
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccessResult
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccessSubject
import java.util.UUID

class RevokeNarmestelederrelasjonTest :
    FunSpec({
        val id = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val employee = PersonIdent("12345678901")
        val manager = PersonIdent("10987654321")
        val outsider = PersonIdent("11111111111")
        val organization = OrganizationNumber("123456789")
        val system = OrganizationAccessSubject.LpsSystemUser("test-system", organization)

        fun user(ident: PersonIdent) = OrganizationAccessSubject.PersonnelManager(ident, AccessToken("test-token"))
        fun relation(revocable: Boolean = true) = RevocableNarmestelederrelasjon(
            id = id,
            organizationNumber = organization,
            employeeIdent = employee,
            managerIdent = manager,
            isActive = revocable,
        )

        fun fixture(
            lookup: RevocableNarmestelederrelasjon? = relation(),
            access: OrganizationAccessResult = OrganizationAccessResult.Granted,
        ): RevokeFixture {
            val relationId = id
            val effects = mutableListOf<String>()
            val commands = mutableListOf<PublishNarmestelederrelasjonRevocationCommand>()
            val useCase = RevokeNarmestelederrelasjon(
                repository = object : NarmestelederrelasjonRepository {
                    override suspend fun findById(id: UUID): NarmestelederrelasjonLookup? = error("Not used by revoke")
                    override suspend fun findRevocableById(id: UUID): RevocableNarmestelederrelasjon? {
                        effects.add("lookup")
                        return if (id == relationId) lookup else null
                    }
                },
                organizationAccess = { _, org ->
                    org shouldBe organization
                    effects.add("access")
                    access
                },
                publisher = { command ->
                    effects.add("publish")
                    commands.add(command)
                },
            )
            return RevokeFixture(useCase, effects, commands)
        }

        test("employee and manager bypass organization access and publish once") {
            listOf(employee to RevocationInitiator.EMPLOYEE, manager to RevocationInitiator.LINEMANAGER).forEach { (ident, initiator) ->
                val (useCase, effects, commands) = fixture(access = OrganizationAccessResult.Denied(DenialReason.MISSING_ORGANIZATION_ACCESS))
                useCase.execute(id, user(ident)) shouldBe RevokeNarmestelederrelasjonResult.Revoked(initiator)
                effects shouldBe listOf("lookup", "publish")
                commands shouldBe listOf(PublishNarmestelederrelasjonRevocationCommand(employee, organization, initiator))
            }
        }

        test("authorized outsider and system user use distinct sources") {
            listOf(
                user(outsider) to RevocationInitiator.PERSONNEL_MANAGER,
                system to RevocationInitiator.LPS,
            ).forEach { (subject, initiator) ->
                val (useCase, effects, commands) = fixture()
                useCase.execute(id, subject) shouldBe RevokeNarmestelederrelasjonResult.Revoked(initiator)
                effects shouldBe listOf("lookup", "access", "publish")
                commands.single().initiator shouldBe initiator
            }
        }

        test("unknown relation never checks access or publishes") {
            val (useCase, effects) = fixture(lookup = null)
            useCase.execute(id, user(outsider)) shouldBe
                RevokeNarmestelederrelasjonResult.NotFound(RevokeNarmestelederrelasjonResult.Reason.RELATION_NOT_FOUND)
            effects shouldBe listOf("lookup")
        }

        test("denied outsider and system user never publish, even for inactive relations") {
            DenialReason.entries.forEach { reason ->
                val subject = if (reason == DenialReason.SYSTEM_USER_REJECTED) system else user(outsider)
                val (useCase, effects) = fixture(
                    lookup = relation(revocable = false),
                    access = OrganizationAccessResult.Denied(reason),
                )
                useCase.execute(id, subject) shouldBe
                    RevokeNarmestelederrelasjonResult.NotFound(RevokeNarmestelederrelasjonResult.Reason.ACCESS_DENIED, reason)
                effects shouldBe listOf("lookup", "access")
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
    val useCase: RevokeNarmestelederrelasjon,
    val effects: List<String>,
    val commands: List<PublishNarmestelederrelasjonRevocationCommand>,
)
