package no.nav.syfo.narmestelederbehov.infrastructure

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import nlBehovEntity
import no.nav.syfo.TestDB
import no.nav.syfo.TestDB.Companion.updateCreated
import no.nav.syfo.narmesteleder.db.PostgresNarmestelederDb
import no.nav.syfo.narmesteleder.domain.BehovStatus
import no.nav.syfo.sykmelding.exposed.SendtSykmeldingTable
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class ExposedNarmestelederbehovExpiryRepositoryTest :
    FunSpec({
        val repository = ExposedNarmestelederbehovExpiryRepository(TestDB.exposedDatabase)
        val setupDb = PostgresNarmestelederDb(TestDB.database)

        beforeTest {
            TestDB.clearAllData()
            TestDB.clearSendtSykmeldingData()
        }

        test("expires only open behov whose sendt sykmelding for the same employee and organization has tom strictly before the cutoff") {
            val cutoff = LocalDate.parse("2026-03-01")
            val openStatuses = listOf(BehovStatus.BEHOV_CREATED, BehovStatus.DIALOGPORTEN_STATUS_SET_REQUIRES_ATTENTION)
            val organization = "910000001"
            val byStatus = BehovStatus.entries.mapIndexed { index, status ->
                val employee = "200000000%02d".format(index)
                insertSendtSykmelding(employee, organization, tom = cutoff.minusDays(1))
                status to requireNotNull(setupDb.insertNlBehov(nlBehovEntity().copy(sykmeldtFnr = employee, orgnummer = organization, behovStatus = status)).id)
            }.toMap()
            insertSendtSykmelding("30000000001", organization, tom = cutoff)
            val tomAtCutoff = setupDb.insertNlBehov(nlBehovEntity().copy(sykmeldtFnr = "30000000001", orgnummer = organization, behovStatus = BehovStatus.BEHOV_CREATED))
            insertSendtSykmelding("30000000002", organization, tom = cutoff.minusDays(1))
            val otherOrganization = setupDb.insertNlBehov(nlBehovEntity().copy(sykmeldtFnr = "30000000002", orgnummer = "910000002", behovStatus = BehovStatus.BEHOV_CREATED))
            val withoutSykmelding = setupDb.insertNlBehov(nlBehovEntity().copy(sykmeldtFnr = "30000000003", orgnummer = organization, behovStatus = BehovStatus.BEHOV_CREATED))

            repository.expireOpenWithSykmeldingTomBefore(cutoff, limit = 500) shouldBe openStatuses.size

            byStatus.forEach { (status, id) ->
                val expected = if (status in openStatuses) BehovStatus.BEHOV_EXPIRED else status
                requireNotNull(setupDb.findBehovById(id)).behovStatus shouldBe expected
            }
            listOf(tomAtCutoff, otherOrganization, withoutSykmelding).forEach {
                requireNotNull(setupDb.findBehovById(requireNotNull(it.id))).behovStatus shouldBe BehovStatus.BEHOV_CREATED
            }
            repository.expireOpenWithSykmeldingTomBefore(cutoff, limit = 500) shouldBe 0
        }

        test("expires the oldest open behov first in bounded batches") {
            val cutoff = LocalDate.parse("2026-03-01")
            val created = Instant.parse("2025-01-01T00:00:00Z")
            val ids = (1..3).map { suffix ->
                val employee = "1234567890$suffix"
                insertSendtSykmelding(employee, "910000001", tom = cutoff.minusDays(10))
                val id = requireNotNull(setupDb.insertNlBehov(nlBehovEntity().copy(sykmeldtFnr = employee, orgnummer = "910000001", behovStatus = BehovStatus.BEHOV_CREATED)).id)
                updateCreated(id, created.minusSeconds(suffix.toLong()))
                id
            }

            repository.expireOpenWithSykmeldingTomBefore(cutoff, limit = 2) shouldBe 2

            ids.map { requireNotNull(setupDb.findBehovById(it)).behovStatus } shouldBe
                listOf(BehovStatus.BEHOV_CREATED, BehovStatus.BEHOV_EXPIRED, BehovStatus.BEHOV_EXPIRED)
            repository.expireOpenWithSykmeldingTomBefore(cutoff, limit = 2) shouldBe 1
            repository.expireOpenWithSykmeldingTomBefore(cutoff, limit = 2) shouldBe 0
        }
    })

private fun insertSendtSykmelding(employee: String, organization: String, tom: LocalDate) {
    transaction(TestDB.exposedDatabase) {
        SendtSykmeldingTable.insert {
            it[sykmeldingId] = UUID.randomUUID()
            it[orgnummer] = organization
            it[fnr] = employee
            it[fom] = tom.minusDays(14)
            it[SendtSykmeldingTable.tom] = tom
        }
    }
}
