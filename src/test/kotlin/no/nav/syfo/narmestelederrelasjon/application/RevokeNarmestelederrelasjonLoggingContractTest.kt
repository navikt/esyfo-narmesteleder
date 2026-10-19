package no.nav.syfo.narmestelederrelasjon.application

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import no.nav.esyfo.observability.testkit.LogCapture
import no.nav.esyfo.observability.testkit.RuntimeLogContract
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonalIdentificationNumber
import no.nav.syfo.logging.ProductionLogs
import no.nav.syfo.organisasjonstilgang.application.AccessToken
import no.nav.syfo.organisasjonstilgang.application.DenialReason
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccessResult
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccessSubject
import no.nav.syfo.platform.upstream.UpstreamFailure
import no.nav.syfo.platform.upstream.UpstreamFailureStage
import no.nav.syfo.platform.upstream.UpstreamName
import java.util.UUID

class RevokeNarmestelederrelasjonLoggingContractTest :
    FunSpec({
        val mapper = jacksonObjectMapper()
        val contract = RuntimeLogContract.forEvents(
            revokeAccessRejected,
            rejectionReasons = setOf("MISSING_ORG_ACCESS", "MISSING_ALITINN_RESOURCE_ACCESS"),
        )
        val productionLogs = extension(ProductionLogs(RevokeNarmestelederrelasjonUseCase::class.java))
        lateinit var capture: LogCapture
        val id = UUID.fromString("00000000-0000-0000-0000-00000000abcd")
        val employee = PersonalIdentificationNumber("12345678901")
        val outsider = PersonalIdentificationNumber("11111111111")
        val org = OrganizationNumber("123456789")
        val user = OrganizationAccessSubject.PersonnelManager(outsider, AccessToken("test-token"))
        val system = OrganizationAccessSubject.LpsSystemUser("test-system-user", org)

        suspend fun execute(
            subject: OrganizationAccessSubject,
            denial: DenialReason,
            found: Boolean = true,
            access: OrganizationAccessResult = OrganizationAccessResult.Denied(denial),
        ) = RevokeNarmestelederrelasjonUseCase(
            repository = object : NarmestelederrelasjonRepository {
                override suspend fun findById(id: UUID): NarmestelederrelasjonLookup? = error("Not used by revoke")
                override suspend fun findRevocableById(id: UUID) = if (found) {
                    RevocableNarmestelederrelasjon(id, employee, PersonalIdentificationNumber("10987654321"), org, isActive = false)
                } else {
                    null
                }
            },
            organizationAccess = { _, _ -> access },
            publisher = RecordingRevocationPublisher { error("Publisher must not be called") },
        ).execute(id, subject)

        beforeTest { capture = productionLogs.capture() }
        afterTest {
            try {
                capture.records.forEach { line ->
                    listOf(employee.value, outsider.value, org.value, "test-token", "test-system-user").forEach {
                        line shouldNotContain it
                    }
                }
            } finally {
                capture.close()
            }
        }

        test("missing organization and missing resource emit exactly the legacy revoke event") {
            listOf(
                DenialReason.MISSING_ORGANIZATION_ACCESS to "MISSING_ORG_ACCESS",
                DenialReason.MISSING_RESOURCE_ACCESS to "MISSING_ALITINN_RESOURCE_ACCESS",
            ).forEach { (reason, expected) ->
                execute(user, reason)
                contract.assertValid(capture.records, expectedCount = 1)
                val event = mapper.readTree(capture.records.single())
                event["event_type"].asText() shouldBe "api_request_rejected"
                event["operation"].asText() shouldBe "revoke_linemanager"
                event["rejection_reason"].asText() shouldBe expected
                event["narmesteleder_id"].asText() shouldBe id.toString()
                event["principal_type"].asText() shouldBe "UserPrincipal"
                capture.close()
                capture = productionLogs.capture()
            }
        }

        test("a denied system user does not emit the revoke event") {
            DenialReason.entries.forEach { reason ->
                execute(system, reason)
                capture.records.size shouldBe 0
            }
        }

        test("unavailable access does not emit a revoke rejection") {
            val failure = UpstreamFailure(UpstreamName("arbeidsgiver-altinn-tilganger"), UpstreamFailureStage.RESPONSE, 503, IllegalStateException("private upstream body ${org.value}"))
            execute(user, DenialReason.MISSING_ORGANIZATION_ACCESS, access = OrganizationAccessResult.Unavailable(failure)) shouldBe
                RevokeNarmestelederrelasjonResult.UpstreamUnavailable(failure)
            contract.assertValid(capture.records, expectedCount = 0)
        }

        test("unknown relation does not emit the revoke event") {
            execute(user, DenialReason.MISSING_ORGANIZATION_ACCESS, found = false)
            capture.records.size shouldBe 0
        }
    })
