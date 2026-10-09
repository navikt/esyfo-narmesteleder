package no.nav.syfo.narmestelederbehov.infrastructure

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import no.nav.syfo.TestDB
import no.nav.syfo.narmesteleder.domain.BehovStatus
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.date
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class ExposedNarmestelederbehovExpiryRepositoryTest :
    FunSpec({
        val repository = ExposedNarmestelederbehovExpiryRepository(TestDB.exposedDatabase)

        beforeTest {
            TestDB.clearAllData()
            TestDB.clearSendtSykmeldingData()
        }

        fun storedStatus(id: UUID) = requireNotNull(findStoredNarmestelederbehov(id)).behovStatus

        test("expires only open behov whose sendt sykmelding for the same employee and organization has tom strictly before the cutoff") {
            val cutoff = LocalDate.parse("2026-03-01")
            val openStatuses = listOf(BehovStatus.BEHOV_CREATED, BehovStatus.DIALOGPORTEN_STATUS_SET_REQUIRES_ATTENTION)
            val organization = "910000001"
            val byStatus = BehovStatus.entries.mapIndexed { index, status ->
                val employee = "200000000%02d".format(index)
                insertSendtSykmelding(employee, organization, tom = cutoff.minusDays(1))
                status to insertNarmestelederbehov(sykmeldtFnr = employee, orgnummer = organization, behovStatus = status).id
            }.toMap()
            insertSendtSykmelding("30000000001", organization, tom = cutoff)
            val tomAtCutoff = insertNarmestelederbehov(sykmeldtFnr = "30000000001", orgnummer = organization)
            insertSendtSykmelding("30000000002", organization, tom = cutoff.minusDays(1))
            val otherOrganization = insertNarmestelederbehov(sykmeldtFnr = "30000000002", orgnummer = "910000002")
            val withoutSykmelding = insertNarmestelederbehov(sykmeldtFnr = "30000000003", orgnummer = organization)

            repository.expireBehov(cutoff, limit = 500) shouldBe openStatuses.size

            byStatus.forEach { (status, id) ->
                val expected = if (status in openStatuses) BehovStatus.BEHOV_EXPIRED else status
                storedStatus(id) shouldBe expected
            }
            listOf(tomAtCutoff, otherOrganization, withoutSykmelding).forEach {
                storedStatus(it.id) shouldBe BehovStatus.BEHOV_CREATED
            }
            repository.expireBehov(cutoff, limit = 500) shouldBe 0
        }

        test("expires the oldest open behov first in bounded batches") {
            val cutoff = LocalDate.parse("2026-03-01")
            val created = Instant.parse("2025-01-01T00:00:00Z")
            val ids = (1..3).map { suffix ->
                val employee = "1234567890$suffix"
                insertSendtSykmelding(employee, "910000001", tom = cutoff.minusDays(10))
                insertNarmestelederbehov(
                    sykmeldtFnr = employee,
                    orgnummer = "910000001",
                    created = created.minusSeconds(suffix.toLong()),
                ).id
            }

            repository.expireBehov(cutoff, limit = 2) shouldBe 2

            ids.map(::storedStatus) shouldBe listOf(BehovStatus.BEHOV_CREATED, BehovStatus.BEHOV_EXPIRED, BehovStatus.BEHOV_EXPIRED)
            repository.expireBehov(cutoff, limit = 2) shouldBe 1
            repository.expireBehov(cutoff, limit = 2) shouldBe 0
        }
    })

private object SendtSykmeldingSeedTable : Table("sendt_sykmelding") {
    val sykmeldingId = javaUUID("sykmelding_id")
    val orgnummer = varchar("orgnummer", 9)
    val fnr = text("fnr")
    val fom = date("fom")
    val tom = date("tom")
}

private fun insertSendtSykmelding(employee: String, organization: String, tom: LocalDate) {
    transaction(TestDB.exposedDatabase) {
        SendtSykmeldingSeedTable.insert {
            it[sykmeldingId] = UUID.randomUUID()
            it[orgnummer] = organization
            it[fnr] = employee
            it[fom] = tom.minusDays(14)
            it[SendtSykmeldingSeedTable.tom] = tom
        }
    }
}
