package no.nav.syfo.narmestelederbehov.application

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonalIdentificationNumber
import no.nav.syfo.logging.withProductionLogs
import no.nav.syfo.narmestelederbehov.domain.Employee
import no.nav.syfo.narmestelederbehov.domain.NarmestelederbehovId
import no.nav.syfo.organisasjonstilgang.application.AccessToken
import no.nav.syfo.organisasjonstilgang.application.DenialReason
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccess
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccessResult
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccessSubject
import no.nav.syfo.platform.upstream.UpstreamError
import no.nav.syfo.platform.upstream.UpstreamFailure
import no.nav.syfo.platform.upstream.UpstreamFailureStage
import no.nav.syfo.platform.upstream.UpstreamName
import java.time.Instant
import java.util.UUID

class ListNarmestelederbehovUseCaseTest :
    FunSpec({
        test("unavailable access stops before reads name lookups writes and count") {
            val failure = UpstreamFailure(UpstreamName("arbeidsgiver-altinn-tilganger"), UpstreamFailureStage.RESPONSE, 503, IllegalStateException())
            val fixture = ListFixture(access = OrganizationAccessResult.Unavailable(failure))
            withProductionLogs(ListNarmestelederbehovUseCase::class.java) { capture ->
                val result = fixture.execute()
                result shouldBe ListNarmestelederbehovResult.UpstreamUnavailable(failure)
                result.shouldBeInstanceOf<ListNarmestelederbehovResult.UpstreamUnavailable>().failure shouldBeSameInstanceAs failure
                capture.records shouldBe emptyList()
            }
            fixture.effects shouldBe listOf("access")
            fixture.savedNames shouldBe emptyList()
            fixture.lookedUp shouldBe emptyList()
        }

        DenialReason.entries.forEach { reason ->
            test("access denial $reason stops before any read") {
                val fixture = ListFixture(access = OrganizationAccessResult.Denied(reason))
                fixture.execute() shouldBe ListNarmestelederbehovResult.AccessDenied(reason, ORGANIZATION_NUMBER)
                fixture.effects shouldBe listOf("access")
            }
        }

        test("stored names need no lookup or writes and no count when the page fits") {
            val rows = listOf(narmestelederbehovDetails(), narmestelederbehovDetails())
            val fixture = ListFixture(rows)
            fixture.execute(pageSize = 2) shouldBe ListNarmestelederbehovResult.Listed(
                rows.map { ListedNarmestelederbehov(it, BehovPersonName("Stored", "Middle", "Name")) },
                hasMore = false,
                total = 2L,
                organizationName = "Org",
            )
            fixture.effects shouldBe listOf("access", "find")
            fixture.requestedLimit shouldBe 3
            fixture.requestedOrganization shouldBe ORGANIZATION_NUMBER
            fixture.requestedCreatedAfter shouldBe Instant.EPOCH
        }

        test("missing names are resolved and saved sequentially after access including the overflow row") {
            val rows = listOf(
                narmestelederbehovDetails(firstName = null),
                narmestelederbehovDetails(lastName = null, employeeIdent = PersonalIdentificationNumber("12345678902")),
            )
            val fixture = ListFixture(rows)
            val result = fixture.execute(pageSize = 1) as ListNarmestelederbehovResult.Listed
            result.behov shouldBe listOf(ListedNarmestelederbehov(rows.first(), BehovPersonName("Looked", null, "Up")))
            result.hasMore shouldBe true
            result.total shouldBe 42L
            fixture.requestedLimit shouldBe 2
            fixture.effects shouldBe listOf("access", "find", "person", "save", "person", "save", "count")
            fixture.lookedUp shouldBe rows.map { it.employeeIdent }
            fixture.savedNames shouldBe rows.map { it.id to BehovPersonName("Looked", null, "Up") }
        }

        test("an unresolved overflow person stops before count") {
            val fixture = ListFixture(listOf(narmestelederbehovDetails(), narmestelederbehovDetails(firstName = null)), personFound = false)
            fixture.execute(pageSize = 1) shouldBe ListNarmestelederbehovResult.PersonNotFound
            fixture.effects shouldBe listOf("access", "find", "person")
            fixture.savedNames shouldBe emptyList()
        }

        test("a missing first person stops before subsequent lookup or writes") {
            val fixture = ListFixture(List(2) { narmestelederbehovDetails(firstName = null) }, personFound = false)
            fixture.execute(pageSize = 1) shouldBe ListNarmestelederbehovResult.PersonNotFound
            fixture.effects shouldBe listOf("access", "find", "person")
        }

        test("Unavailable stops before subsequent lookups writes and count") {
            val failure = UpstreamFailure(UpstreamName("pdl"), UpstreamFailureStage.RESPONSE, 200, errors = listOf(UpstreamError("server_error")))
            val fixture = ListFixture(List(2) { narmestelederbehovDetails(firstName = null) }, unavailable = failure)
            fixture.execute(pageSize = 1) shouldBe ListNarmestelederbehovResult.UpstreamUnavailable(failure)
            fixture.effects shouldBe listOf("access", "find", "person")
            fixture.savedNames shouldBe emptyList()
        }

        test("an empty page has total zero and never counts") {
            val fixture = ListFixture(emptyList())
            fixture.execute() shouldBe ListNarmestelederbehovResult.Listed(emptyList(), false, 0L, "Org")
            fixture.effects shouldBe listOf("access", "find")
        }

        test("a system user has a null organization name") {
            val fixture = ListFixture(access = OrganizationAccessResult.Granted(null))
            val subject = OrganizationAccessSubject.LpsSystemUser("system", ORGANIZATION_NUMBER)
            val result = fixture.execute(subject = subject) as ListNarmestelederbehovResult.Listed
            result.organizationName shouldBe null
            fixture.subject shouldBe subject
        }
    })

private val ORGANIZATION_NUMBER = OrganizationNumber("910000001")

private class ListFixture(
    private val rows: List<NarmestelederbehovDetails> = listOf(narmestelederbehovDetails()),
    private val access: OrganizationAccessResult = OrganizationAccessResult.Granted("Org"),
    private val personFound: Boolean = true,
    private val unavailable: UpstreamFailure? = null,
) {
    val effects = mutableListOf<String>()
    val lookedUp = mutableListOf<PersonalIdentificationNumber>()
    val savedNames = mutableListOf<Pair<NarmestelederbehovId, BehovPersonName>>()
    var requestedLimit: Int? = null
    var requestedOrganization: OrganizationNumber? = null
    var requestedCreatedAfter: Instant? = null
    var subject: OrganizationAccessSubject? = null
    private val repository = object : NarmestelederbehovRepository {
        override suspend fun findPendingDialogs(createdBefore: Instant, limit: Int) = error("Unexpected findPendingDialogs")
        override suspend fun findFulfilledDialogs(createdBefore: Instant) = error("Unexpected findFulfilledDialogs")
        override suspend fun findDialogsToExpire() = error("Unexpected findDialogsToExpire")
        override suspend fun markDialogExpired(id: NarmestelederbehovId, expirationTime: Instant) = error("Unexpected markDialogExpired")
        override suspend fun create(behov: NewNarmestelederbehov) = error("Unexpected create")

        override suspend fun findDetails(id: NarmestelederbehovId) = error("List must not find by id")
        override suspend fun saveEmployeeName(id: NarmestelederbehovId, name: BehovPersonName) {
            effects += "save"
            savedNames += id to name
        }
        override suspend fun findForFulfillment(id: NarmestelederbehovId) = error("List must not read for fulfillment")
        override suspend fun findOpenFor(employee: Employee): List<NarmestelederbehovId> = error("List must not look up open behov by employee")
        override suspend fun markFulfilled(id: NarmestelederbehovId): MarkFulfilledResult = error("List must not fulfill")
        override suspend fun markDialogCompleted(id: NarmestelederbehovId): MarkDialogCompletedResult = error("List must not complete dialogs")

        override suspend fun markDialogCreated(
            id: NarmestelederbehovId,
            dialogId: UUID,
            name: BehovPersonName?,
        ): MarkDialogCreatedResult = error("Unexpected markDialogCreated")
    }
    private val openRepository = object : OpenNarmestelederbehovRepository {
        override suspend fun findOpen(organizationNumber: OrganizationNumber, createdAfter: Instant, limit: Int): List<NarmestelederbehovDetails> {
            effects += "find"
            requestedLimit = limit
            requestedOrganization = organizationNumber
            requestedCreatedAfter = createdAfter
            return rows.take(limit)
        }
        override suspend fun countOpen(organizationNumber: OrganizationNumber, createdAfter: Instant): Long {
            organizationNumber shouldBe requestedOrganization
            createdAfter shouldBe requestedCreatedAfter
            effects += "count"
            return 42L
        }
    }
    private val useCase = ListNarmestelederbehovUseCase(
        repository = openRepository,
        organizationAccess = OrganizationAccess { caller, organizationNumber ->
            organizationNumber shouldBe ORGANIZATION_NUMBER
            subject = caller
            effects += "access"
            access
        },
        employeeName = NarmestelederbehovEmployeeName(
            repository,
            EmployeeNameLookup {
                effects += "person"
                lookedUp += it
                unavailable?.let { EmployeeNameLookupResult.Unavailable(it) }
                    ?: if (personFound) EmployeeNameLookupResult.Found(BehovPersonName("Looked", null, "Up")) else EmployeeNameLookupResult.NotFound
            },
        ),
    )

    suspend fun execute(
        pageSize: Int = 50,
        subject: OrganizationAccessSubject = OrganizationAccessSubject.PersonnelManager(PersonalIdentificationNumber("12345678901"), AccessToken("token")),
    ) = useCase.execute(ListNarmestelederbehovQuery(ORGANIZATION_NUMBER, Instant.EPOCH, pageSize, subject))
}
