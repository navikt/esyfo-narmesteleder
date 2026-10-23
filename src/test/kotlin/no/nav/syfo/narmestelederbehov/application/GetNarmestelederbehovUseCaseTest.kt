package no.nav.syfo.narmestelederbehov.application

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonalIdentificationNumber
import no.nav.syfo.narmestelederbehov.domain.NarmestelederbehovId
import no.nav.syfo.organisasjonstilgang.application.AccessToken
import no.nav.syfo.organisasjonstilgang.application.DenialReason
import no.nav.syfo.organisasjonstilgang.application.FakeOrganizationAccess
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccessResult
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccessSubject
import no.nav.syfo.platform.observability.withProductionLogs
import no.nav.syfo.platform.upstream.UpstreamError
import no.nav.syfo.platform.upstream.UpstreamFailure
import no.nav.syfo.platform.upstream.UpstreamFailureStage
import no.nav.syfo.platform.upstream.UpstreamName
import java.util.UUID

class GetNarmestelederbehovUseCaseTest :
    FunSpec({
        test("unavailable access stops without later lookups or writes") {
            val failure = UpstreamFailure(UpstreamName("arbeidsgiver-altinn-tilganger"), UpstreamFailureStage.RESPONSE, 503, IllegalStateException())
            val fixture = ReadFixture(access = OrganizationAccessResult.Unavailable(failure))
            withProductionLogs(GetNarmestelederbehovUseCase::class.java) { capture ->
                val result = fixture.execute()
                result shouldBe GetNarmestelederbehovResult.UpstreamUnavailable(failure)
                result.shouldBeInstanceOf<GetNarmestelederbehovResult.UpstreamUnavailable>().failure shouldBeSameInstanceAs failure
                capture.records shouldBe emptyList()
            }
            fixture.effects shouldBe listOf("read", "access")
            fixture.savedNames shouldBe emptyList()
        }

        test("stored names are read without person lookup or writes and carry Altinn name") {
            val fixture = ReadFixture()
            val result = fixture.execute()
            result shouldBe GetNarmestelederbehovResult.Found(
                behov = requireNotNull(fixture.row),
                name = BehovPersonName(firstName = "Stored", middleName = "Middle", lastName = "Name"),
                organizationName = "Org",
            )
            fixture.effects shouldBe listOf("read", "access")
        }

        test("missing name is looked up and persisted before access is checked") {
            val fixture = ReadFixture(row = narmestelederbehovDetails(firstName = null, lastName = null))
            fixture.person = BehovPersonName(firstName = "Looked", lastName = "Up", middleName = "Middle")
            fixture.execute() shouldBe GetNarmestelederbehovResult.Found(
                behov = requireNotNull(fixture.row),
                name = BehovPersonName(firstName = "Looked", middleName = "Middle", lastName = "Up"),
                organizationName = "Org",
            )
            fixture.effects shouldBe listOf("read", "person", "save-name", "access")
            fixture.savedNames shouldBe listOf(BehovPersonName(firstName = "Looked", middleName = "Middle", lastName = "Up"))
        }

        test("missing id is reported before access") {
            val fixture = ReadFixture(row = null)
            fixture.execute() shouldBe GetNarmestelederbehovResult.NotFound
            fixture.effects shouldBe listOf("read")
        }

        DenialReason.entries.forEach { reason ->
            test("denial $reason is evaluated after the name is resolved") {
                val fixture = ReadFixture(row = narmestelederbehovDetails(firstName = null), access = OrganizationAccessResult.Denied(reason))
                fixture.person = BehovPersonName(firstName = "Looked", lastName = "Up", middleName = null)
                fixture.execute() shouldBe GetNarmestelederbehovResult.AccessDenied(
                    reason = reason,
                    organizationNumber = requireNotNull(fixture.row).organizationNumber,
                )
                fixture.effects shouldBe listOf("read", "person", "save-name", "access")
            }
        }

        test("system user has no organization name") {
            val fixture = ReadFixture(access = OrganizationAccessResult.Granted(organizationName = null))
            val subject = OrganizationAccessSubject.LpsSystemUser(
                systemUserId = "system",
                systemUserOrganizationNumber = OrganizationNumber("999777555"),
            )
            val result = fixture.execute(subject = subject)
            (result as GetNarmestelederbehovResult.Found).organizationName shouldBe null
            fixture.organizationAccess.calls shouldBe listOf(subject to requireNotNull(fixture.row).organizationNumber)
        }

        test("Unavailable propagates before access without persisting a name") {
            val failure = UpstreamFailure(UpstreamName("pdl"), UpstreamFailureStage.RESPONSE, 200, errors = listOf(UpstreamError("server_error")))
            val fixture = ReadFixture(row = narmestelederbehovDetails(firstName = null), unavailable = failure)
            fixture.execute() shouldBe GetNarmestelederbehovResult.UpstreamUnavailable(failure)
            fixture.effects shouldBe listOf("read", "person")
            fixture.savedNames shouldBe emptyList()
        }

        test("unresolved missing name is unavailable") {
            val fixture = ReadFixture(row = narmestelederbehovDetails(firstName = null))
            fixture.execute() shouldBe GetNarmestelederbehovResult.PersonNotFound
            fixture.effects shouldBe listOf("read", "person")
        }
    })

private class ReadFixture(
    val row: NarmestelederbehovDetails? = narmestelederbehovDetails(),
    val access: OrganizationAccessResult = OrganizationAccessResult.Granted(organizationName = "Org"),
    unavailable: UpstreamFailure? = null,
) {
    val effects = mutableListOf<String>()
    val organizationAccess = FakeOrganizationAccess(row?.organizationNumber, access, effects)
    var person: BehovPersonName? = null
    val savedNames = mutableListOf<BehovPersonName>()
    private val repository = GetNarmestelederbehovRepository { _ ->
        row.also { effects += "read" }
    }
    private val employeeNameRepository = NarmestelederbehovEmployeeNameRepository { _, name ->
        effects += "save-name"
        savedNames += name
    }
    private val useCase = GetNarmestelederbehovUseCase(
        repository = repository,
        organizationAccess = organizationAccess,
        employeeName = NarmestelederbehovEmployeeName(
            employeeNameRepository
        ) {
            effects += "person"
            unavailable?.let { EmployeeNameLookupResult.Unavailable(it) }
                ?: person?.let { EmployeeNameLookupResult.Found(it) } ?: EmployeeNameLookupResult.NotFound
        },
    )

    suspend fun execute(
        subject: OrganizationAccessSubject = OrganizationAccessSubject.PersonnelManager(
            personIdent = PersonalIdentificationNumber("12345678901"),
            accessToken = AccessToken("token"),
        ),
    ) = useCase.execute(id = NarmestelederbehovId(UUID.randomUUID()), subject = subject)
}
