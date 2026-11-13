package no.nav.syfo.sykmelding.infrastructure.kafka

import ch.qos.logback.classic.Level
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.inspectors.forNone
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import no.nav.syfo.platform.kafka.jacksonMapper
import no.nav.syfo.platform.observability.withProductionLogs
import no.nav.syfo.sykmelding.application.SENDT_SYKMELDING_TOPIC
import no.nav.syfo.sykmelding.application.SykmeldingsregisterDeadLetter
import no.nav.syfo.sykmelding.application.SykmeldingsregisterDeadLetterRepository
import no.nav.syfo.sykmelding.application.SykmeldingsregisterMessage
import no.nav.syfo.sykmelding.application.SyncSykmeldingsregister
import org.apache.kafka.clients.consumer.ConsumerRecord
import java.util.UUID

class SykmeldingsregisterBatchHandlerTest :
    FunSpec({
        test("valid messages are synced and invalid records are stored as dead letters") {
            val fixture = Fixture()

            fixture.handler.handleBatch(
                listOf(
                    record(offset = 1, value = validJson()),
                    record(offset = 2, key = null, value = validJson()),
                    record(offset = 3, value = null),
                ),
            )

            assertSoftly {
                fixture.synced.map { it.position.offset } shouldBe listOf(1L, 3L)
                fixture.deadLetters.map { it.position.offset } shouldBe listOf(2L)
            }
        }

        test("a stored dead letter is logged without its payload and counted") {
            val fixture = Fixture()

            withProductionLogs(SykmeldingsregisterBatchHandler::class.java, Level.WARN) { logs ->
                fixture.handler.handleBatch(listOf(record(offset = 1, key = "not-a-uuid", value = validJson())))

                assertSoftly {
                    fixture.counter(SykmeldingsregisterMetrics.DEAD_LETTERS) shouldBe 1.0
                    logs.logs.single().fields["event_type"] shouldBe "sykmeldingsregister_record_dead_lettered"
                    logs.logs.forNone { it.serialized shouldContain FNR }
                }
            }
        }

        test("synced tombstones are counted") {
            val fixture = Fixture()

            fixture.handler.handleBatch(listOf(record(offset = 1, value = null), record(offset = 2, value = validJson())))

            fixture.counter(SykmeldingsregisterMetrics.TOMBSTONES) shouldBe 1.0
        }

        test("a failing port propagates so the batch is not committed, and only the stored dead letters are counted") {
            val failure = IllegalStateException("database unavailable")
            val fixture = Fixture(sync = { throw failure })

            val thrown = shouldThrow<IllegalStateException> {
                fixture.handler.handleBatch(
                    listOf(record(offset = 1, value = null), record(offset = 2, key = "not-a-uuid", value = validJson())),
                )
            }

            assertSoftly {
                thrown shouldBe failure
                fixture.counter(SykmeldingsregisterMetrics.TOMBSTONES) shouldBe 0.0
                fixture.counter(SykmeldingsregisterMetrics.DEAD_LETTERS) shouldBe 1.0
            }
        }

        test("a redelivered dead letter is logged and counted once") {
            val fixture = Fixture()
            val batch = listOf(record(offset = 1, key = "not-a-uuid", value = validJson()))

            withProductionLogs(SykmeldingsregisterBatchHandler::class.java, Level.WARN) { logs ->
                fixture.handler.handleBatch(batch)
                fixture.handler.handleBatch(batch)

                assertSoftly {
                    fixture.counter(SykmeldingsregisterMetrics.DEAD_LETTERS) shouldBe 1.0
                    logs.logs.size shouldBe 1
                }
            }
        }

        test("dead letters are saved before messages are synced, so a later tombstone can delete them") {
            val calls = mutableListOf<String>()
            val fixture = Fixture(
                sync = { calls += "sync" },
                saveDeadLetters = { calls += "dead letters" },
            )

            fixture.handler.handleBatch(
                listOf(record(offset = 1, value = "{}"), record(offset = 2, value = null)),
            )

            calls shouldBe listOf("dead letters", "sync")
        }
    })

private val SYKMELDING_ID: UUID = UUID.fromString("5f0c1e2a-3b4c-4d5e-8f60-718293a4b5c6")
private const val FNR = "01019012345"

private class Fixture(
    sync: (List<SykmeldingsregisterMessage>) -> Unit = {},
    saveDeadLetters: (List<SykmeldingsregisterDeadLetter>) -> Unit = {},
) {
    val synced = mutableListOf<SykmeldingsregisterMessage>()
    val deadLetters = mutableListOf<SykmeldingsregisterDeadLetter>()
    private val meterRegistry = SimpleMeterRegistry()

    val handler = SykmeldingsregisterBatchHandler(
        syncSykmeldingsregister = SyncSykmeldingsregister { messages ->
            sync(messages)
            synced += messages
        },
        deadLetterRepository = object : SykmeldingsregisterDeadLetterRepository {
            override suspend fun saveAll(deadLetters: List<SykmeldingsregisterDeadLetter>): List<SykmeldingsregisterDeadLetter> {
                saveDeadLetters(deadLetters)
                val stored = deadLetters.filter { new -> this@Fixture.deadLetters.none { it.position == new.position } }
                this@Fixture.deadLetters += stored
                return stored
            }
        },
        parser = SykmeldingsregisterRecordParser(jacksonMapper()),
        metrics = SykmeldingsregisterMetrics(meterRegistry),
    )

    fun counter(name: String): Double = meterRegistry.find(name).counters().sumOf { it.count() }
}

private fun validJson(): String = sykmeldingsregisterRecordDto(fnr = FNR).toJson()

private fun record(
    partition: Int = 0,
    offset: Long,
    key: String? = SYKMELDING_ID.toString(),
    value: String?,
) = ConsumerRecord<String, String?>(SENDT_SYKMELDING_TOPIC, partition, offset, key, value)
