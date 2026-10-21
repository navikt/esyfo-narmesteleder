package no.nav.syfo.narmestelederrelasjon.application.get

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import no.nav.esyfo.observability.testkit.LogCapture
import no.nav.esyfo.observability.testkit.RuntimeLogContract
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonalIdentificationNumber
import no.nav.syfo.narmestelederrelasjon.application.FakeNarmestelederrelasjonRepository
import no.nav.syfo.narmestelederrelasjon.application.NarmestelederrelasjonLookup
import no.nav.syfo.organisasjonstilgang.application.AccessToken
import no.nav.syfo.organisasjonstilgang.application.DenialReason
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccessResult
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccessSubject
import no.nav.syfo.platform.observability.ProductionLogs
import no.nav.syfo.platform.upstream.UpstreamFailure
import no.nav.syfo.platform.upstream.UpstreamFailureStage
import no.nav.syfo.platform.upstream.UpstreamName
import java.util.UUID

class GetNarmestelederrelasjonLoggingContractTest :
    FunSpec({
        val mapper = jacksonObjectMapper()
        val contract = RuntimeLogContract.forEvents(
            narmestelederrelasjonNotFound,
            narmestelederrelasjonOrganizationNameMissing,
        )
        val productionLogs = extension(ProductionLogs(GetNarmestelederrelasjonUseCase::class.java))
        lateinit var capture: LogCapture

        val id = UUID.fromString("00000000-0000-0000-0000-00000000abcd")
        val employeeIdent = PersonalIdentificationNumber("12345678901")
        val callerIdent = PersonalIdentificationNumber("11111111111")
        val orgNumber = OrganizationNumber("123456789")
        val caller = OrganizationAccessSubject.PersonnelManager(callerIdent, AccessToken("test-token"))
        val privacyCanaries = listOf(
            id.toString(),
            employeeIdent.value,
            callerIdent.value,
            orgNumber.value,
            "test-token",
            "private-first-name",
            "private-last-name",
        )

        fun lookup(isActive: Boolean = true) = NarmestelederrelasjonLookup(
            id = id,
            organizationNumber = orgNumber,
            employeeIdent = employeeIdent,
            employeeFirstName = "private-first-name",
            employeeMiddleName = null,
            employeeLastName = "private-last-name",
            isActive = isActive,
        )

        fun useCase(
            lookup: NarmestelederrelasjonLookup? = lookup(),
            access: OrganizationAccessResult = OrganizationAccessResult.Granted(organizationName = null),
            activeSykmelding: Boolean = true,
            organizationName: OrganizationNameResult = OrganizationNameResult.Found("Organization"),
        ) = GetNarmestelederrelasjonUseCase(
            repository = FakeNarmestelederrelasjonRepository(lookupById = { lookup }),
            organizationAccess = { _, _ -> access },
            activeSykmeldingLookup = { _, _ -> activeSykmelding },
            organization = FakeNarmestelederrelasjonOrganization(organizationName),
        )

        fun checkNotFoundEvent(outcomeCode: String) = mapper.readTree(capture.records.single()).also {
            contract.assertValid(capture.records, expectedCount = 1)
            it["event_type"].asText() shouldBe "narmestelederrelasjon_not_found"
            it["level"].asText() shouldBe "WARN"
            it["operation"].asText() shouldBe "get_narmestelederrelasjon"
            it["outcome_code"].asText() shouldBe outcomeCode
        }

        beforeTest {
            capture = productionLogs.capture()
        }
        afterTest {
            capture.use { capture ->
                capture.records.forEach { line -> privacyCanaries.forEach { line shouldNotContain it } }
            }
        }

        test("logs RELATION_NOT_FOUND when no relation exists for the id") {
            useCase(lookup = null).execute(id, caller)
            checkNotFoundEvent("RELATION_NOT_FOUND").has("denial_reason") shouldBe false
        }

        test("logs RELATION_INACTIVE when the relation is no longer active") {
            useCase(lookup = lookup(isActive = false)).execute(id, caller)
            checkNotFoundEvent("RELATION_INACTIVE")
        }

        test("logs ACCESS_DENIED with denial reason") {
            useCase(access = OrganizationAccessResult.Denied(DenialReason.MISSING_ORGANIZATION_ACCESS)).execute(id, caller)
            checkNotFoundEvent("ACCESS_DENIED")["denial_reason"].asText() shouldBe "MISSING_ORGANIZATION_ACCESS"
        }

        test("unavailable access does not emit a not-found or rejection event") {
            val failure = UpstreamFailure(UpstreamName("arbeidsgiver-altinn-tilganger"), UpstreamFailureStage.RESPONSE, 503, IllegalStateException("private upstream body ${orgNumber.value}"))
            useCase(access = OrganizationAccessResult.Unavailable(failure)).execute(id, caller) shouldBe
                GetNarmestelederrelasjonResult.UpstreamUnavailable(failure)
            contract.assertValid(capture.records, expectedCount = 0)
        }

        test("logs NO_ACTIVE_SYKMELDING when the employee has no active sykmelding") {
            useCase(activeSykmelding = false).execute(id, caller)
            checkNotFoundEvent("NO_ACTIVE_SYKMELDING")
        }

        test("does not log a not-found event when the relation is returned") {
            useCase().execute(id, caller)
            capture.records.size shouldBe 0
        }

        test("logs exactly one identifier-free WARN when the organization name is missing") {
            useCase(organizationName = OrganizationNameResult.Missing).execute(id, caller)
                .shouldBeInstanceOf<GetNarmestelederrelasjonResult.Found>().organizationName shouldBe null

            contract.assertValid(capture.records, expectedCount = 1)
            val record = mapper.readTree(capture.records.single())
            record["event_type"].asText() shouldBe "narmestelederrelasjon_organization_name_missing"
            record["level"].asText() shouldBe "WARN"
            record["operation"].asText() shouldBe "get_narmestelederrelasjon"
            record["message"].asText() shouldBe "Organization name was not found in Ereg"
            record.has("outcome_code") shouldBe false
            record.has("denial_reason") shouldBe false
        }

        test("does not log when the upstream is unavailable because the edge owns failure logging") {
            val failure = UpstreamFailure(
                UpstreamName("ereg"),
                UpstreamFailureStage.RESPONSE,
                503,
                IllegalStateException("private upstream body ${orgNumber.value}"),
            )

            useCase(organizationName = OrganizationNameResult.Unavailable(failure)).execute(id, caller) shouldBe
                GetNarmestelederrelasjonResult.UpstreamUnavailable(failure)

            capture.records.size shouldBe 0
        }
    })
