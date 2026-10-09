package no.nav.syfo.narmestelederbehov.application

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonIdent
import no.nav.syfo.narmesteleder.domain.BehovReason
import no.nav.syfo.narmesteleder.domain.BehovStatus
import no.nav.syfo.narmestelederbehov.domain.Employee
import no.nav.syfo.narmestelederbehov.domain.NarmestelederbehovId
import no.nav.syfo.organisasjonstilgang.application.AccessToken
import no.nav.syfo.organisasjonstilgang.application.DenialReason
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccess
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccessResult
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccessSubject
import java.time.Instant
import java.util.UUID

class ListNarmestelederbehovUseCaseTest :
    FunSpec({
        DenialReason.entries.forEach { reason ->
            test("access denial $reason stops before any read") {
                val fixture = ListFixture(access = OrganizationAccessResult.Denied(reason))
                fixture.execute() shouldBe ListNarmestelederbehovResult.AccessDenied(reason, ORGANIZATION_NUMBER)
                fixture.effects shouldBe listOf("access")
            }
        }

        test("stored names need no lookup or writes and no count when the page fits") {
            val rows = listOf(listDetails(), listDetails())
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
                listDetails().copy(firstName = null),
                listDetails().copy(lastName = null, employeeIdent = PersonIdent("12345678902")),
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
            val fixture = ListFixture(listOf(listDetails(), listDetails().copy(firstName = null)), personFound = false)
            fixture.execute(pageSize = 1) shouldBe ListNarmestelederbehovResult.PersonNotFound
            fixture.effects shouldBe listOf("access", "find", "person")
            fixture.savedNames shouldBe emptyList()
        }

        test("a missing first person stops before subsequent lookup or writes") {
            val fixture = ListFixture(List(2) { listDetails().copy(firstName = null) }, personFound = false)
            fixture.execute(pageSize = 1) shouldBe ListNarmestelederbehovResult.PersonNotFound
            fixture.effects shouldBe listOf("access", "find", "person")
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
    private val rows: List<NarmestelederbehovDetails> = listOf(listDetails()),
    private val access: OrganizationAccessResult = OrganizationAccessResult.Granted("Org"),
    private val personFound: Boolean = true,
) {
    val effects = mutableListOf<String>()
    val lookedUp = mutableListOf<PersonIdent>()
    val savedNames = mutableListOf<Pair<NarmestelederbehovId, BehovPersonName>>()
    var requestedLimit: Int? = null
    var requestedOrganization: OrganizationNumber? = null
    var requestedCreatedAfter: Instant? = null
    var subject: OrganizationAccessSubject? = null
    private val repository = object : NarmestelederbehovRepository {
        override suspend fun findDetails(id: NarmestelederbehovId) = error("List must not find by id")
        override suspend fun saveEmployeeName(id: NarmestelederbehovId, name: BehovPersonName) {
            effects += "save"
            savedNames += id to name
        }
        override suspend fun findForFulfillment(id: NarmestelederbehovId) = error("List must not read for fulfillment")
        override suspend fun findOpenFor(employee: Employee): List<NarmestelederbehovId> = error("List must not look up open behov by employee")
        override suspend fun markFulfilled(id: NarmestelederbehovId): MarkFulfilledResult = error("List must not fulfill")
        override suspend fun markDialogCompleted(id: NarmestelederbehovId): MarkDialogCompletedResult = error("List must not complete dialogs")
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
                if (personFound) BehovPersonName("Looked", null, "Up") else null
            },
        ),
    )

    suspend fun execute(
        pageSize: Int = 50,
        subject: OrganizationAccessSubject = OrganizationAccessSubject.PersonnelManager(PersonIdent("12345678901"), AccessToken("token")),
    ) = useCase.execute(ListNarmestelederbehovQuery(ORGANIZATION_NUMBER, Instant.EPOCH, pageSize, subject))
}

private fun listDetails() = NarmestelederbehovDetails(
    id = NarmestelederbehovId(UUID.randomUUID()),
    employeeIdent = PersonIdent("12345678901"),
    organizationNumber = ORGANIZATION_NUMBER,
    mainOrganizationNumber = "910000002",
    managerIdent = null,
    firstName = "Stored",
    middleName = "Middle",
    lastName = "Name",
    created = Instant.EPOCH,
    updated = Instant.EPOCH,
    status = BehovStatus.BEHOV_CREATED,
    reason = BehovReason.NY_LEDER,
)
