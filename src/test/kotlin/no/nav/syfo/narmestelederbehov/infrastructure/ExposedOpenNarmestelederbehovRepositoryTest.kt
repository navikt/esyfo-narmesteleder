package no.nav.syfo.narmestelederbehov.infrastructure

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import nlBehovEntity
import no.nav.syfo.TestDB
import no.nav.syfo.TestDB.Companion.updateCreated
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonIdent
import no.nav.syfo.narmesteleder.db.PostgresNarmestelederDb
import no.nav.syfo.narmesteleder.domain.BehovStatus
import no.nav.syfo.narmestelederbehov.domain.Employee
import java.time.Instant

class ExposedOpenNarmestelederbehovRepositoryTest :
    FunSpec({
        val repository = ExposedOpenNarmestelederbehovRepository(TestDB.exposedDatabase)
        val setupDb = PostgresNarmestelederDb(TestDB.database)

        beforeTest {
            TestDB.clearAllData()
        }

        test("open queries filter status and organization with a strict created boundary, ordering, limit and count") {
            val organizationNumber = OrganizationNumber("910000001")
            val boundary = Instant.parse("2025-01-01T00:00:00Z")
            val later = setupDb.insertNlBehov(
                nlBehovEntity().copy(orgnummer = organizationNumber.value, behovStatus = BehovStatus.DIALOGPORTEN_STATUS_SET_REQUIRES_ATTENTION),
            )
            val earlier = setupDb.insertNlBehov(nlBehovEntity().copy(orgnummer = organizationNumber.value, behovStatus = BehovStatus.BEHOV_CREATED))
            updateCreated(requireNotNull(later.id), boundary.plusSeconds(2))
            updateCreated(requireNotNull(earlier.id), boundary.plusSeconds(1))
            for (status in BehovStatus.entries) {
                if (status !in listOf(BehovStatus.BEHOV_CREATED, BehovStatus.DIALOGPORTEN_STATUS_SET_REQUIRES_ATTENTION)) {
                    val closed = setupDb.insertNlBehov(nlBehovEntity().copy(orgnummer = organizationNumber.value, behovStatus = status))
                    updateCreated(requireNotNull(closed.id), boundary.plusSeconds(1))
                }
            }
            val otherOrganization = setupDb.insertNlBehov(nlBehovEntity().copy(orgnummer = "910000002", behovStatus = BehovStatus.BEHOV_CREATED))
            val atBoundary = setupDb.insertNlBehov(nlBehovEntity().copy(orgnummer = organizationNumber.value, behovStatus = BehovStatus.BEHOV_CREATED))
            val beforeBoundary = setupDb.insertNlBehov(nlBehovEntity().copy(orgnummer = organizationNumber.value, behovStatus = BehovStatus.BEHOV_CREATED))
            updateCreated(requireNotNull(otherOrganization.id), boundary.plusSeconds(1))
            updateCreated(requireNotNull(atBoundary.id), boundary)
            updateCreated(requireNotNull(beforeBoundary.id), boundary.minusSeconds(1))

            repository.findOpen(organizationNumber, boundary, limit = 50).map { it.id.value } shouldBe listOf(earlier.id, later.id)
            repository.findOpen(organizationNumber, boundary, limit = 1).map { it.id.value } shouldBe listOf(earlier.id)
            repository.countOpen(organizationNumber, boundary) shouldBe 2L
            repository.countOpen(organizationNumber, boundary.plusSeconds(2)) shouldBe 0L
            repository.findOpen(organizationNumber, boundary.plusSeconds(2), limit = 50) shouldBe emptyList()
        }

        // An employee can have at most one open behov per organization (uq_nl_behov_active_employee_org).
        val openStatuses = listOf(BehovStatus.BEHOV_CREATED, BehovStatus.DIALOGPORTEN_STATUS_SET_REQUIRES_ATTENTION)
        openStatuses.forEach { openStatus ->
            test("open behov for an employee match person, organization and status $openStatus") {
                val employee = Employee(PersonIdent("12345678901"), OrganizationNumber("910000001"))
                suspend fun insert(
                    status: BehovStatus,
                    personIdent: String = employee.personIdent.value,
                    organizationNumber: String = employee.organizationNumber.value,
                ) = requireNotNull(
                    setupDb.insertNlBehov(
                        nlBehovEntity().copy(sykmeldtFnr = personIdent, orgnummer = organizationNumber, behovStatus = status),
                    ).id,
                )
                val open = insert(openStatus)
                BehovStatus.entries
                    .filterNot { it in openStatuses }
                    .forEach { insert(it) }
                insert(BehovStatus.BEHOV_CREATED, personIdent = "10987654321")
                insert(BehovStatus.BEHOV_CREATED, organizationNumber = "910000002")

                repository.find(employee).map { it.value } shouldBe listOf(open)
            }
        }
    })
