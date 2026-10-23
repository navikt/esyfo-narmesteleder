package no.nav.syfo.narmestelederrelasjon.application.get

import com.fasterxml.jackson.databind.JsonNode
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
import no.nav.syfo.narmestelederrelasjon.application.ActiveSykmeldingLookup
import no.nav.syfo.narmestelederrelasjon.application.FakeNarmestelederrelasjonRepository
import no.nav.syfo.narmestelederrelasjon.application.NarmestelederrelasjonLookup
import no.nav.syfo.narmestelederrelasjon.domain.Narmestelederrelasjon
import no.nav.syfo.narmestelederrelasjon.domain.RelationPerson
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

class GetNarmestelederrelasjonUseCaseTest :
    FunSpec({
        val mapper = jacksonObjectMapper()
        val contract = RuntimeLogContract.forEvents(
            narmestelederrelasjonNotFound,
            narmestelederrelasjonOrganizationNameMissing,
        )
        val productionLogs = extension(ProductionLogs(GetNarmestelederrelasjonUseCase::class.java))
        lateinit var capture: ProductionLogCapture

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

        fun lookup() = NarmestelederrelasjonLookup(
            id = id,
            organizationNumber = orgNumber,
            employeeIdent = employeeIdent,
            employeeFirstName = "private-first-name",
            employeeMiddleName = null,
            employeeLastName = "private-last-name",
            isActive = true,
        )

        fun query(
            lookup: NarmestelederrelasjonLookup? = lookup(),
            access: OrganizationAccessResult = OrganizationAccessResult.Granted(organizationName = null),
            activeSykmelding: Boolean = true,
            organizationName: OrganizationNameResult = OrganizationNameResult.Found("Organization"),
            effects: MutableList<String> = mutableListOf(),
            organization: FakeNarmestelederrelasjonOrganization = FakeNarmestelederrelasjonOrganization(organizationName, effects),
        ) = GetNarmestelederrelasjonUseCase(
            FakeNarmestelederrelasjonRepository(lookupById = { lookup }, effects = effects),
            FakeOrganizationAccess(orgNumber, access, effects),
            FakeGetActiveSykmeldingLookup(activeSykmelding, effects),
            organization,
        )

        fun notFoundEvent(outcomeCode: String): JsonNode {
            contract.assertValid(capture.records, expectedCount = 1)
            return mapper.readTree(capture.records.single()).also {
                it["event_type"].asText() shouldBe "narmestelederrelasjon_not_found"
                it["level"].asText() shouldBe "WARN"
                it["operation"].asText() shouldBe "get_narmestelederrelasjon"
                it["outcome_code"].asText() shouldBe outcomeCode
            }
        }

        beforeTest {
            capture = productionLogs.capture()
        }
        afterTest {
            capture.use { capture ->
                capture.records.forEach { line -> privacyCanaries.forEach { line shouldNotContain it } }
            }
        }

        listOf(
            "personnel manager" to caller,
            "system user" to OrganizationAccessSubject.LpsSystemUser("system-user", OrganizationNumber("999777555")),
        ).forEach { (description, subject) ->
            test("returns the relation only to an organization-authorized $description without logging") {
                val effects = mutableListOf<String>()
                val organization = FakeNarmestelederrelasjonOrganization(effects = effects)
                query(effects = effects, organization = organization).execute(id, subject) shouldBe GetNarmestelederrelasjonResult.Found(
                    Narmestelederrelasjon(
                        id = id,
                        orgNumber = orgNumber,
                        employee = RelationPerson(employeeIdent, "private-first-name", null, "private-last-name"),
                    ),
                    "Organization",
                )
                effects shouldBe listOf("lookup", "access", "sykmelding", "organization")
                organization.calls shouldBe listOf(orgNumber)
                capture.records.shouldBeEmpty()
            }
        }

        test("masks missing organization access and logs the denial reason") {
            val effects = mutableListOf<String>()
            query(
                access = OrganizationAccessResult.Denied(DenialReason.MISSING_ORGANIZATION_ACCESS),
                effects = effects,
            ).execute(id, caller) shouldBe GetNarmestelederrelasjonResult.NotFound(
                GetNarmestelederrelasjonResult.NotFoundReason.ACCESS_DENIED,
                DenialReason.MISSING_ORGANIZATION_ACCESS,
            )
            effects shouldBe listOf("lookup", "access")
            notFoundEvent("ACCESS_DENIED")["denial_reason"].asText() shouldBe "MISSING_ORGANIZATION_ACCESS"
        }

        test("unavailable access stops before sykmelding and organization lookup without a not-found event") {
            val effects = mutableListOf<String>()
            val failure = UpstreamFailure(
                UpstreamName("arbeidsgiver-altinn-tilganger"),
                UpstreamFailureStage.RESPONSE,
                503,
                IllegalStateException("private upstream body ${orgNumber.value}"),
            )
            val result = query(access = OrganizationAccessResult.Unavailable(failure), effects = effects).execute(id, caller)
            result shouldBe GetNarmestelederrelasjonResult.UpstreamUnavailable(failure)
            result.shouldBeInstanceOf<GetNarmestelederrelasjonResult.UpstreamUnavailable>().failure shouldBeSameInstanceAs failure
            effects shouldBe listOf("lookup", "access")
            contract.assertValid(capture.records, expectedCount = 0)
        }

        test("masks missing active sykmelding after authorization and before organization lookup") {
            val effects = mutableListOf<String>()
            query(activeSykmelding = false, effects = effects).execute(id, caller) shouldBe
                GetNarmestelederrelasjonResult.NotFound(GetNarmestelederrelasjonResult.NotFoundReason.NO_ACTIVE_SYKMELDING)
            effects shouldBe listOf("lookup", "access", "sykmelding")
            notFoundEvent("NO_ACTIVE_SYKMELDING")
        }

        test("masks an inactive relation before authorization lookup") {
            val effects = mutableListOf<String>()
            query(lookup = lookup().copy(isActive = false), effects = effects).execute(id, caller) shouldBe
                GetNarmestelederrelasjonResult.NotFound(GetNarmestelederrelasjonResult.NotFoundReason.RELATION_INACTIVE)
            effects shouldBe listOf("lookup")
            notFoundEvent("RELATION_INACTIVE")
        }

        data class IncompleteNameCase(
            val description: String,
            val lookup: NarmestelederrelasjonLookup,
            val expectedFirstName: String?,
            val expectedLastName: String?,
        )

        listOf(
            IncompleteNameCase("missing first name", lookup().copy(employeeFirstName = null), null, "private-last-name"),
            IncompleteNameCase("blank first name", lookup().copy(employeeFirstName = " "), null, "private-last-name"),
            IncompleteNameCase("missing last name", lookup().copy(employeeLastName = null), "private-first-name", null),
            IncompleteNameCase("blank last name", lookup().copy(employeeLastName = ""), "private-first-name", null),
        ).forEach { case ->
            test("returns the relation with a nullable name for ${case.description}") {
                val effects = mutableListOf<String>()
                query(lookup = case.lookup, effects = effects).execute(id, caller) shouldBe
                    GetNarmestelederrelasjonResult.Found(
                        Narmestelederrelasjon(
                            id = id,
                            orgNumber = orgNumber,
                            employee = RelationPerson(employeeIdent, case.expectedFirstName, null, case.expectedLastName),
                        ),
                        "Organization",
                    )
                effects shouldBe listOf("lookup", "access", "sykmelding", "organization")
            }
        }

        test("returns the relation with a null organization name and logs one identifier-free WARN when the name is missing") {
            val effects = mutableListOf<String>()
            query(organizationName = OrganizationNameResult.Missing, effects = effects).execute(id, caller) shouldBe
                GetNarmestelederrelasjonResult.Found(
                    Narmestelederrelasjon(
                        id = id,
                        orgNumber = orgNumber,
                        employee = RelationPerson(employeeIdent, "private-first-name", null, "private-last-name"),
                    ),
                    organizationName = null,
                )
            effects shouldBe listOf("lookup", "access", "sykmelding", "organization")

            contract.assertValid(capture.records, expectedCount = 1)
            val record = mapper.readTree(capture.records.single())
            record["event_type"].asText() shouldBe "narmestelederrelasjon_organization_name_missing"
            record["level"].asText() shouldBe "WARN"
            record["operation"].asText() shouldBe "get_narmestelederrelasjon"
            record["message"].asText() shouldBe "Organization name was not found in Ereg"
            record.has("outcome_code") shouldBe false
            record.has("denial_reason") shouldBe false
        }

        test("returns UpstreamUnavailable with the unchanged failure after organization lookup and leaves logging to the edge") {
            val effects = mutableListOf<String>()
            val failure = UpstreamFailure(
                UpstreamName("ereg"),
                UpstreamFailureStage.RESPONSE,
                503,
                IllegalStateException("private upstream body ${orgNumber.value}"),
            )

            query(organizationName = OrganizationNameResult.Unavailable(failure), effects = effects)
                .execute(id, caller).shouldBeInstanceOf<GetNarmestelederrelasjonResult.UpstreamUnavailable>()
                .failure shouldBeSameInstanceAs failure
            effects shouldBe listOf("lookup", "access", "sykmelding", "organization")
            capture.records.shouldBeEmpty()
        }

        test("does not perform authorization lookup when no relation exists") {
            val effects = mutableListOf<String>()
            query(lookup = null, effects = effects).execute(id, caller) shouldBe
                GetNarmestelederrelasjonResult.NotFound(GetNarmestelederrelasjonResult.NotFoundReason.RELATION_NOT_FOUND)
            effects shouldBe listOf("lookup")
            notFoundEvent("RELATION_NOT_FOUND").has("denial_reason") shouldBe false
        }
    })

private class FakeGetActiveSykmeldingLookup(
    private val active: Boolean,
    private val effects: MutableList<String>,
) : ActiveSykmeldingLookup {
    override suspend fun hasActiveSykmelding(personIdent: PersonalIdentificationNumber, organizationNumber: OrganizationNumber) = active.also { effects += "sykmelding" }
}
