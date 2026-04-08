package no.nav.syfo.narmesteleder.service

import defaultLeesahKafkaMessage
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import no.nav.syfo.TestDB
import no.nav.syfo.narmesteleder.exposed.NarmestelederEntity
import no.nav.syfo.narmesteleder.exposed.NarmestelederTable
import no.nav.syfo.narmesteleder.kafka.LeesahNarmestelederRecord
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.LocalDate
import java.util.UUID

class NarmestelederRegisterServiceTest :
    DescribeSpec({
        val service = NarmestelederRegisterService(TestDB.exposedDatabase)

        beforeTest {
            TestDB.clearNarmestelederData()
        }

        describe("upsertBatch") {
            it("should upsert replayed records idempotently") {
                val narmesteLederId = UUID.randomUUID()
                val originalMessage = defaultLeesahKafkaMessage().copy(
                    narmesteLederId = narmesteLederId,
                    narmesteLederTelefonnummer = "11111111",
                )
                val replayedMessage = originalMessage.copy(
                    narmesteLederTelefonnummer = "22222222",
                    narmesteLederEpost = "updated@example.com",
                    aktivTom = LocalDate.of(2025, 1, 31),
                )

                service.upsertBatch(listOf(LeesahNarmestelederRecord(offset = 1L, message = originalMessage)))
                service.upsertBatch(listOf(LeesahNarmestelederRecord(offset = 2L, message = replayedMessage)))

                transaction(TestDB.exposedDatabase) {
                    val results = NarmestelederEntity.find {
                        NarmestelederTable.narmestelederId eq narmesteLederId
                    }

                    results.count() shouldBe 1
                    val entity = results.first()
                    entity.narmestelederTelefonnummer shouldBe replayedMessage.narmesteLederTelefonnummer
                    entity.narmestelederEpost shouldBe replayedMessage.narmesteLederEpost
                    entity.aktivTom?.toLocalDate() shouldBe replayedMessage.aktivTom
                }
            }

            it("should skip invalid records without blocking valid records in the same batch") {
                val validMessage = defaultLeesahKafkaMessage()
                val invalidMessage = defaultLeesahKafkaMessage().copy(fnr = "123")

                service.upsertBatch(
                    listOf(
                        LeesahNarmestelederRecord(offset = 1L, message = validMessage),
                        LeesahNarmestelederRecord(offset = 2L, message = invalidMessage),
                    ),
                )

                transaction(TestDB.exposedDatabase) {
                    NarmestelederEntity.all().count() shouldBe 1
                    NarmestelederEntity.find {
                        NarmestelederTable.narmestelederId eq validMessage.narmesteLederId
                    }.count() shouldBe 1
                }
            }
        }
    })
