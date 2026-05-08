package no.nav.syfo.sykmelding.exposed

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import no.nav.syfo.TestDB
import no.nav.syfo.sykmelding.db.SendtSykmeldingEntity
import no.nav.syfo.sykmelding.db.SykmeldingDb
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

class SendtSykmeldingRepositoryTest :
    DescribeSpec({
        val osloZone = ZoneId.of("Europe/Oslo")
        val clock = Clock.fixed(Instant.parse("2025-01-20T10:00:00Z"), osloZone)
        val today = LocalDate.now(clock)
        val sykmeldingDb = SykmeldingDb(TestDB.database)
        val repository = SendtSykmeldingRepository(
            database = TestDB.exposedDatabase,
            clock = clock,
        )

        suspend fun upsertSykmelding(
            fnr: String,
            orgnummer: String,
            tom: LocalDate,
            revokedDate: LocalDate? = null,
        ) {
            val sykmeldingId = UUID.randomUUID()
            sykmeldingDb.transaction {
                batchUpsertSykmeldingerIfMoreRecentTom(
                    listOf(
                        SendtSykmeldingEntity(
                            sykmeldingId = sykmeldingId,
                            fnr = fnr,
                            orgnummer = orgnummer,
                            fom = tom.minusDays(10),
                            tom = tom,
                            revokedDate = revokedDate,
                            syketilfelleStartDato = tom.minusDays(10),
                            created = Instant.now(clock),
                            updated = Instant.now(clock),
                        )
                    )
                )
                if (revokedDate != null) {
                    batchRevokeSykmelding(listOf(sykmeldingId), revokedDate)
                }
            }
        }

        beforeTest {
            TestDB.clearSendtSykmeldingData()
        }

        describe("hasActiveSykmelding") {
            it("returns true when sykmelding tom is within grace period") {
                val fnr = "12345678901"
                val orgnummer = "123456789"

                upsertSykmelding(
                    fnr = fnr,
                    orgnummer = orgnummer,
                    tom = today.minusDays(16),
                )

                repository.hasActiveSykmelding(fnr, orgnummer) shouldBe true
            }

            it("returns false when sykmelding tom is outside grace period") {
                val fnr = "12345678901"
                val orgnummer = "123456789"

                upsertSykmelding(
                    fnr = fnr,
                    orgnummer = orgnummer,
                    tom = today.minusDays(17),
                )

                repository.hasActiveSykmelding(fnr, orgnummer) shouldBe false
            }

            it("returns false when sykmelding is revoked") {
                val fnr = "12345678901"
                val orgnummer = "123456789"

                upsertSykmelding(
                    fnr = fnr,
                    orgnummer = orgnummer,
                    tom = today,
                    revokedDate = today,
                )

                repository.hasActiveSykmelding(fnr, orgnummer) shouldBe false
            }
        }
    })
