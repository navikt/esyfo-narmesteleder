package no.nav.syfo.organisasjonstilgang.infrastructure

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlinx.coroutines.CancellationException
import no.nav.esyfo.observability.testkit.LogCapture
import no.nav.esyfo.observability.testkit.RuntimeLogContract
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonalIdentificationNumber
import no.nav.syfo.integration.ereg.CachedEregClient
import no.nav.syfo.integration.ereg.EregCache
import no.nav.syfo.integration.ereg.FakeEregClient
import no.nav.syfo.integration.ereg.Organisasjon
import no.nav.syfo.organisasjonstilgang.application.AccessToken
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccessResult
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccessSubject
import no.nav.syfo.organisasjonstilgang.infrastructure.altinnauthorization.AltinnAuthorizationClient
import no.nav.syfo.organisasjonstilgang.infrastructure.altinnauthorization.AuthorizationDecision
import no.nav.syfo.organisasjonstilgang.infrastructure.altinnauthorization.User
import no.nav.syfo.organisasjonstilgang.infrastructure.altinntilganger.AltinnTilgangerService
import no.nav.syfo.organisasjonstilgang.infrastructure.altinntilganger.FakeAltinnTilgangerClient
import no.nav.syfo.platform.observability.ProductionLogs
import no.nav.syfo.platform.upstream.UpstreamError
import no.nav.syfo.platform.upstream.UpstreamFailure
import no.nav.syfo.platform.upstream.UpstreamFailureStage
import no.nav.syfo.platform.upstream.UpstreamName
import no.nav.syfo.platform.upstream.UpstreamResult
import org.slf4j.MDC

class AltinnOrganizationAccessLoggingContractTest :
    FunSpec({
        val mapper = jacksonObjectMapper()
        val contract = RuntimeLogContract.forEvents(
            systemUserAccessRejected,
            rejectionReasons = setOf(SYSTEM_USER_ACCESS_NOT_GRANTED),
        )
        val productionLogs = extension(ProductionLogs(AltinnOrganizationAccess::class.java))
        lateinit var capture: LogCapture
        val privacyCanaries = listOf(REQUESTED_ORG, SYSTEM_USER_ORG, SYSTEM_USER_ID, PERSON_IDENT, ACCESS_TOKEN)

        fun rejectionRecord() = capture.records.single().let {
            contract.assertValid(capture.records, expectedCount = 1)
            mapper.readTree(it)
        }

        beforeTest {
            capture = productionLogs.capture()
        }
        afterTest {
            capture.use { capture ->
                capture.records.forEach { line -> privacyCanaries.forEach { line shouldNotContain it } }
            }
        }

        test("logs one rejection without a fallback decision when the system user is outside the hierarchy") {
            val fixture = LoggingFixture(AuthorizationDecision.Deny, parentOrgNumber = UNRELATED_ORG)

            fixture.access.evaluate(systemUser(), OrganizationNumber(REQUESTED_ORG))

            val record = rejectionRecord()
            record["level"].asText() shouldBe "WARN"
            record["message"].asText() shouldBe "System user access was not granted after resource and organization checks"
            record["logger_name"].asText() shouldBe AltinnOrganizationAccess::class.java.name
            record["event_type"].asText() shouldBe "api_request_rejected"
            record["operation"].asText() shouldBe "validate_system_user_access"
            record["error_code"].asText() shouldBe "MISSING_ALTINN_RESOURCE_ACCESS"
            record["rejection_reason"].asText() shouldBe "SYSTEM_USER_ACCESS_NOT_GRANTED"
            record["pdp_decision"].asText() shouldBe "Deny"
            record["pdp_fallback_decision"].asText() shouldBe "not_checked"
        }

        test("logs one rejection with the fallback decision when the system user is in the hierarchy") {
            val fixture = LoggingFixture(AuthorizationDecision.NotApplicable, AuthorizationDecision.Deny, parentOrgNumber = SYSTEM_USER_ORG)

            fixture.access.evaluate(systemUser(), OrganizationNumber(REQUESTED_ORG))

            val record = rejectionRecord()
            record["pdp_decision"].asText() shouldBe "NotApplicable"
            record["pdp_fallback_decision"].asText() shouldBe "Deny"
        }

        test("logs nothing when the fallback grants access") {
            val fixture = LoggingFixture(AuthorizationDecision.Deny, AuthorizationDecision.Permit, parentOrgNumber = SYSTEM_USER_ORG)

            fixture.access.evaluate(systemUser(), OrganizationNumber(REQUESTED_ORG))

            capture.records.shouldBeEmpty()
        }

        test("logs nothing when a personnel manager is denied") {
            val fixture = LoggingFixture(AuthorizationDecision.Deny, parentOrgNumber = UNRELATED_ORG)

            fixture.access.evaluate(
                OrganizationAccessSubject.PersonnelManager(PersonalIdentificationNumber(PERSON_IDENT), AccessToken(ACCESS_TOKEN)),
                OrganizationNumber(REQUESTED_ORG),
            )

            capture.records.shouldBeEmpty()
        }
        test("logs nothing when the personnel manager lookup is unavailable") {
            val failure = UpstreamFailure(UpstreamName("arbeidsgiver-altinn-tilganger"), UpstreamFailureStage.REQUEST, null, IllegalStateException("Synthetic failure"))
            val fixture = LoggingFixture(AuthorizationDecision.Deny, parentOrgNumber = UNRELATED_ORG, altinnFailure = failure)
            val result = fixture.access.evaluate(
                OrganizationAccessSubject.PersonnelManager(PersonalIdentificationNumber(PERSON_IDENT), AccessToken(ACCESS_TOKEN)),
                OrganizationNumber(REQUESTED_ORG),
            )
            result shouldBe OrganizationAccessResult.Unavailable(failure)
            result.shouldBeInstanceOf<OrganizationAccessResult.Unavailable>().failure shouldBeSameInstanceAs failure
            capture.records.shouldBeEmpty()
        }
        AuthorizationDecision.entries.filter { it != AuthorizationDecision.Indeterminate }.forEach { directDecision ->
            test("direct $directDecision outside the hierarchy never checks a fallback decision") {
                val fixture = LoggingFixture(directDecision, parentOrgNumber = UNRELATED_ORG)

                val result = fixture.access.evaluate(systemUser(), OrganizationNumber(REQUESTED_ORG))

                fixture.checkedOrganizations shouldBe listOf(REQUESTED_ORG)
                if (directDecision == AuthorizationDecision.Permit) {
                    result.shouldBeInstanceOf<OrganizationAccessResult.Granted>()
                    capture.records.shouldBeEmpty()
                } else {
                    result.shouldBeInstanceOf<OrganizationAccessResult.Denied>()
                    val record = rejectionRecord()
                    record["pdp_decision"].asText() shouldBe directDecision.name
                    record["pdp_fallback_decision"].asText() shouldBe "not_checked"
                }
            }

            AuthorizationDecision.entries.filter { it != AuthorizationDecision.Indeterminate }.forEach { fallbackDecision ->
                test("direct $directDecision and hierarchy $fallbackDecision keep the authorization outcome") {
                    val fixture = LoggingFixture(directDecision, fallbackDecision, parentOrgNumber = SYSTEM_USER_ORG)

                    val result = fixture.access.evaluate(systemUser(), OrganizationNumber(REQUESTED_ORG))

                    fixture.checkedOrganizations shouldBe if (directDecision == AuthorizationDecision.Permit) {
                        listOf(REQUESTED_ORG)
                    } else {
                        listOf(REQUESTED_ORG, SYSTEM_USER_ORG)
                    }
                    if (directDecision == AuthorizationDecision.Permit || fallbackDecision == AuthorizationDecision.Permit) {
                        result.shouldBeInstanceOf<OrganizationAccessResult.Granted>()
                        capture.records.shouldBeEmpty()
                    } else {
                        result.shouldBeInstanceOf<OrganizationAccessResult.Denied>()
                        val record = rejectionRecord()
                        record["pdp_decision"].asText() shouldBe directDecision.name
                        record["pdp_fallback_decision"].asText() shouldBe fallbackDecision.name
                    }
                }
            }
        }

        listOf(
            UpstreamFailure(UpstreamName("altinn-pdp"), UpstreamFailureStage.REQUEST, null, IllegalStateException()),
            UpstreamFailure(UpstreamName("altinn-pdp"), UpstreamFailureStage.RESPONSE, 200, errors = listOf(UpstreamError("indeterminate", detail = "unknown"))),
            UpstreamFailure(UpstreamName("texas"), UpstreamFailureStage.TOKEN_EXCHANGE, 400, errors = listOf(UpstreamError("invalid_grant"))),
            UpstreamFailure(UpstreamName("altinn-authentication"), UpstreamFailureStage.TOKEN_EXCHANGE, 200, IllegalArgumentException()),
        ).forEach { failure ->
            listOf(REQUESTED_ORG, SYSTEM_USER_ORG).forEach { failingOrganization ->
                test("returns original ${failure.upstream} ${failure.status} failure for $failingOrganization without logging a rejection") {
                    val fixture = LoggingFixture(
                        AuthorizationDecision.Deny,
                        parentOrgNumber = SYSTEM_USER_ORG,
                        pdpFailures = mapOf(failingOrganization to failure),
                    )

                    fixture.access.evaluate(systemUser(), OrganizationNumber(REQUESTED_ORG))
                        .shouldBeInstanceOf<OrganizationAccessResult.Unavailable>().failure shouldBeSameInstanceAs failure
                    fixture.checkedOrganizations shouldBe if (failingOrganization == REQUESTED_ORG) listOf(REQUESTED_ORG) else listOf(REQUESTED_ORG, SYSTEM_USER_ORG)

                    capture.records.shouldBeEmpty()
                }
            }
        }

        AuthorizationDecision.entries.filter { it != AuthorizationDecision.Permit && it != AuthorizationDecision.Indeterminate }.forEach { directDecision ->
            test("Ereg failure after direct $directDecision stops before fallback without logging a rejection") {
                val failure = UpstreamFailure(
                    UpstreamName("ereg"),
                    UpstreamFailureStage.RESPONSE,
                    503,
                    IllegalStateException("private upstream body $REQUESTED_ORG"),
                )
                val fixture = LoggingFixture(
                    directDecision,
                    fallbackDecision = AuthorizationDecision.Permit,
                    parentOrgNumber = SYSTEM_USER_ORG,
                    eregFailure = failure,
                )

                fixture.access.evaluate(systemUser(), OrganizationNumber(REQUESTED_ORG))
                    .shouldBeInstanceOf<OrganizationAccessResult.Unavailable>().failure shouldBeSameInstanceAs failure

                fixture.checkedOrganizations shouldBe listOf(REQUESTED_ORG)
                capture.records.shouldBeEmpty()
            }
        }

        test("propagates cancellation without logging a rejection") {
            val fixture = LoggingFixture(
                AuthorizationDecision.Deny,
                parentOrgNumber = SYSTEM_USER_ORG,
                cancelled = CancellationException("Request cancelled"),
            )

            shouldThrow<CancellationException> {
                fixture.access.evaluate(systemUser(), OrganizationNumber(REQUESTED_ORG))
            }

            capture.records.shouldBeEmpty()
        }

        test("keeps an existing trace id without synthesizing a new one") {
            val traceId = "0123456789abcdef0123456789abcdef"
            val fixture = LoggingFixture(AuthorizationDecision.Deny, parentOrgNumber = UNRELATED_ORG)

            MDC.put("trace_id", traceId)
            try {
                fixture.access.evaluate(systemUser(), OrganizationNumber(REQUESTED_ORG))
            } finally {
                MDC.remove("trace_id")
            }

            rejectionRecord()["trace_id"].asText() shouldBe traceId
        }
    })

private const val REQUESTED_ORG = "910000011"
private const val SYSTEM_USER_ORG = "910000012"
private const val UNRELATED_ORG = "910000013"
private const val SYSTEM_USER_ID = "privacy-canary-system-user"
private const val PERSON_IDENT = "12345678902"
private const val ACCESS_TOKEN = "privacy-canary-token"

private class LoggingFixture(
    directDecision: AuthorizationDecision,
    fallbackDecision: AuthorizationDecision = AuthorizationDecision.Deny,
    parentOrgNumber: String,
    pdpFailures: Map<String, UpstreamFailure> = emptyMap(),
    cancelled: CancellationException? = null,
    eregFailure: UpstreamFailure? = null,
    altinnFailure: UpstreamFailure? = null,
) {
    val checkedOrganizations = mutableListOf<String>()
    private val decisions = mapOf(REQUESTED_ORG to directDecision, SYSTEM_USER_ORG to fallbackDecision)
    private val pdp = object : AltinnAuthorizationClient {
        override suspend fun authorize(user: User, orgNumberSet: Set<String>, resource: String): UpstreamResult<AuthorizationDecision> {
            val organization = orgNumberSet.single()
            checkedOrganizations += organization
            cancelled?.let { throw it }
            pdpFailures[organization]?.let { return UpstreamResult.Failure(it) }
            return UpstreamResult.Success(decisions.getValue(organization))
        }
    }
    private val ereg = FakeEregClient().also {
        it.organisasjoner.clear()
        it.organisasjoner[REQUESTED_ORG] = Organisasjon(
            organisasjonsnummer = REQUESTED_ORG,
            inngaarIJuridiskEnheter = listOf(Organisasjon(organisasjonsnummer = parentOrgNumber)),
        )
        if (eregFailure != null) it.setFailure(eregFailure)
    }
    private val eregCache = mockk<EregCache> {
        every { getOrganisasjon(any()) } returns null
        every { putOrganisasjon(any(), any()) } just runs
    }
    val access = AltinnOrganizationAccess(
        AltinnTilgangerService(
            FakeAltinnTilgangerClient().also {
                it.accessPolicy.clear()
                if (altinnFailure != null) it.setFailure(altinnFailure)
            }
        ),
        pdp,
        CachedEregClient(ereg, eregCache),
    )
}

private fun systemUser() = OrganizationAccessSubject.LpsSystemUser(
    systemUserId = SYSTEM_USER_ID,
    systemUserOrganizationNumber = OrganizationNumber(SYSTEM_USER_ORG),
)
