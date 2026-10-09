package no.nav.syfo.narmestelederbehov.infrastructure

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import no.nav.syfo.TestDB
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.narmesteleder.domain.BehovStatus
import java.time.Instant

class ExposedOpenNarmestelederbehovRepositoryTest :
    FunSpec({
        val repository = ExposedOpenNarmestelederbehovRepository(TestDB.exposedDatabase)

        beforeTest {
            TestDB.clearAllData()
        }

        test("open queries filter status and organization with a strict created boundary, ordering, limit and count") {
            val organizationNumber = OrganizationNumber("910000001")
            val boundary = Instant.parse("2025-01-01T00:00:00Z")
            val later = insertNarmestelederbehov(
                orgnummer = organizationNumber.value,
                behovStatus = BehovStatus.DIALOGPORTEN_STATUS_SET_REQUIRES_ATTENTION,
                created = boundary.plusSeconds(2),
            )
            val earlier = insertNarmestelederbehov(
                orgnummer = organizationNumber.value,
                behovStatus = BehovStatus.BEHOV_CREATED,
                created = boundary.plusSeconds(1),
            )
            BehovStatus.entries
                .filterNot { it == BehovStatus.BEHOV_CREATED || it == BehovStatus.DIALOGPORTEN_STATUS_SET_REQUIRES_ATTENTION }
                .forEach { insertNarmestelederbehov(orgnummer = organizationNumber.value, behovStatus = it, created = boundary.plusSeconds(1)) }
            insertNarmestelederbehov(orgnummer = "910000002", behovStatus = BehovStatus.BEHOV_CREATED, created = boundary.plusSeconds(1))
            insertNarmestelederbehov(orgnummer = organizationNumber.value, behovStatus = BehovStatus.BEHOV_CREATED, created = boundary)
            insertNarmestelederbehov(orgnummer = organizationNumber.value, behovStatus = BehovStatus.BEHOV_CREATED, created = boundary.minusSeconds(1))

            repository.findOpen(organizationNumber, boundary, limit = 50).map { it.id.value } shouldBe listOf(earlier.id, later.id)
            repository.findOpen(organizationNumber, boundary, limit = 1).map { it.id.value } shouldBe listOf(earlier.id)
            repository.countOpen(organizationNumber, boundary) shouldBe 2L
            repository.countOpen(organizationNumber, boundary.plusSeconds(2)) shouldBe 0L
            repository.findOpen(organizationNumber, boundary.plusSeconds(2), limit = 50) shouldBe emptyList()
        }
    })
