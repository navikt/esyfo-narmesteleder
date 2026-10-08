package no.nav.syfo.narmestelederbehov.application

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonIdent
import no.nav.syfo.narmesteleder.domain.BehovReason
import no.nav.syfo.narmesteleder.domain.BehovStatus
import no.nav.syfo.narmestelederbehov.domain.NarmestelederbehovId
import no.nav.syfo.organisasjonstilgang.application.AccessToken
import no.nav.syfo.organisasjonstilgang.application.DenialReason
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccess
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccessResult
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccessSubject
import java.time.Instant
import java.util.UUID

class GetNarmestelederbehovUseCaseTest :
    FunSpec({
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
            val fixture = ReadFixture(row = behovDetails().copy(firstName = null, lastName = null))
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
                val fixture = ReadFixture(row = behovDetails().copy(firstName = null), access = OrganizationAccessResult.Denied(reason))
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
            val result = fixture.execute(
                subject = OrganizationAccessSubject.LpsSystemUser(
                    systemUserId = "system",
                    systemUserOrganizationNumber = OrganizationNumber("910000001"),
                ),
            )
            (result as GetNarmestelederbehovResult.Found).organizationName shouldBe null
        }

        test("unresolved missing name is unavailable") {
            val fixture = ReadFixture(row = behovDetails().copy(firstName = null))
            fixture.execute() shouldBe GetNarmestelederbehovResult.PersonNotFound
            fixture.effects shouldBe listOf("read", "person")
        }
    })

private class ReadFixture(
    val row: NarmestelederbehovDetails? = behovDetails(),
    val access: OrganizationAccessResult = OrganizationAccessResult.Granted(organizationName = "Org"),
) {
    val effects = mutableListOf<String>()
    var person: BehovPersonName? = null
    val savedNames = mutableListOf<BehovPersonName>()
    private val repository = object : NarmestelederbehovRepository {
        override suspend fun findDetails(id: NarmestelederbehovId) = row.also { effects += "read" }
        override suspend fun saveEmployeeName(id: NarmestelederbehovId, name: BehovPersonName) {
            effects += "save-name"
            savedNames += name
        }
        override suspend fun findForFulfillment(id: NarmestelederbehovId) = error("GET must not use fulfillment lookup")
        override suspend fun markFulfilled(id: NarmestelederbehovId): MarkFulfilledResult = error("GET must not write")
    }
    private val useCase = GetNarmestelederbehovUseCase(
        repository = repository,
        organizationAccess = OrganizationAccess { _, _ -> access.also { effects += "access" } },
        employeeName = NarmestelederbehovEmployeeName(repository, EmployeeNameLookup { person.also { effects += "person" } }),
    )

    suspend fun execute(
        subject: OrganizationAccessSubject = OrganizationAccessSubject.PersonnelManager(
            personIdent = PersonIdent("12345678901"),
            accessToken = AccessToken("token"),
        ),
    ) = useCase.execute(id = NarmestelederbehovId(UUID.randomUUID()), subject = subject)
}

private fun behovDetails() = NarmestelederbehovDetails(
    id = NarmestelederbehovId(UUID.randomUUID()),
    employeeIdent = PersonIdent("12345678901"),
    organizationNumber = OrganizationNumber("910000001"),
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
