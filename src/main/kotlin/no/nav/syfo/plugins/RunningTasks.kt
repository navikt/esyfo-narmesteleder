package no.nav.syfo.plugins

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopPreparing
import io.ktor.server.application.ServerReady
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import no.nav.syfo.application.environment.Environment
import no.nav.syfo.application.kafka.consumerProperties
import no.nav.syfo.application.kafka.jacksonMapper
import no.nav.syfo.application.leaderelection.LeaderChangeSSEListener
import no.nav.syfo.narmesteleder.kafka.LeesahNLKafkaConsumer
import no.nav.syfo.narmesteleder.kafka.LeesahNarmestelederReplayKafkaConsumer
import no.nav.syfo.narmesteleder.kafka.NlBehovLeesahHandler
import no.nav.syfo.narmesteleder.kafka.leesahNarmestelederReplayConsumerProperties
import no.nav.syfo.narmesteleder.service.LeaderControlledKafkaConsumer
import no.nav.syfo.narmesteleder.service.NarmestelederRegisterService
import no.nav.syfo.sykmelding.kafka.PersistSendtSykmeldingConsumer
import no.nav.syfo.sykmelding.kafka.SendtSykmeldingHandler
import no.nav.syfo.sykmelding.kafka.SendtSykmeldingKafkaConsumer
import no.nav.syfo.util.logger
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.StringDeserializer
import org.koin.ktor.ext.inject
import kotlin.time.Duration.Companion.minutes

fun Application.configureKafkaConsumers() {
    val nlLeesahHandler by inject<NlBehovLeesahHandler>()
    val sendtSykmeldingHandler by inject<SendtSykmeldingHandler>()
    val narmestelederRegisterService by inject<NarmestelederRegisterService>()
    val environment by inject<Environment>()
    val leaderChangeSSEListener by inject<LeaderChangeSSEListener>()
    val logger = logger()

    if (!environment.kafka.shouldConsumeTopics) {
        logger.info("Kafka consumers is not enabled, skipping configuration of consumers")
        return
    }

    logger.info("Configuring Kafka consumers")

    val leesahConsumer = LeesahNLKafkaConsumer(
        handler = nlLeesahHandler,
        jacksonMapper = jacksonMapper(),
        kafkaConsumer = KafkaConsumer(
            consumerProperties(
                env = environment.kafka,
                valueDeserializer = StringDeserializer::class,
                groupId = "esyfo-narmesteleder-les-behov"
            ),
            StringDeserializer(),
            StringDeserializer(),
        ),
        scope = this,
    ).apply {
        commitOnAllErrors = environment.kafka.commitOnAllErrors
    }

    val sendtSykmeldingConsumer = SendtSykmeldingKafkaConsumer(
        handler = sendtSykmeldingHandler,
        jacksonMapper = jacksonMapper(),
        kafkaConsumer = KafkaConsumer(
            consumerProperties(
                env = environment.kafka,
                valueDeserializer = StringDeserializer::class,
                groupId = "esyfo-narmesteleder-sendt-sykmelding-consumer"
            ),
            StringDeserializer(),
            StringDeserializer(),
        ),
        scope = this
    )

    val persistSendtSykmeldingConsumer = PersistSendtSykmeldingConsumer(
        handler = sendtSykmeldingHandler,
        jacksonMapper = jacksonMapper(),
        kafkaConsumer = KafkaConsumer(
            consumerProperties(
                env = environment.kafka,
                valueDeserializer = StringDeserializer::class,
                groupId = "esyfo-narmesteleder-persist-sendt-sykmelding-consumer"
            ).apply {
                put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
                put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "500")
                put(
                    ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG,
                    5.minutes
                        .inWholeMilliseconds
                        .toString()
                )
                put(ConsumerConfig.FETCH_MIN_BYTES_CONFIG, "1048576")
                put(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG, "500")
            },
            StringDeserializer(),
            StringDeserializer(),
        ),
        scope = this,
        env = environment.otherProperties,
    )
    val leesahNarmestelederReplayConsumer = LeesahNarmestelederReplayKafkaConsumer(
        handler = narmestelederRegisterService,
        jacksonMapper = jacksonMapper(),
        kafkaConsumer = KafkaConsumer(
            leesahNarmestelederReplayConsumerProperties(environment.kafka),
            StringDeserializer(),
            StringDeserializer(),
        ),
        scope = this,
    ).apply {
        commitOnAllErrors = environment.kafka.commitOnAllErrors
    }

    val leaderControlledConsumers = listOf(
        LeaderControlledKafkaConsumer(
            consumerName = "persistSendtSykmeldingConsumer",
            consumer = persistSendtSykmeldingConsumer,
            enabled = environment.otherProperties.persistSendtSykmelding,
            closeable = persistSendtSykmeldingConsumer,
        ),
        LeaderControlledKafkaConsumer(
            consumerName = "leesahNarmestelederReplayConsumer",
            consumer = leesahNarmestelederReplayConsumer,
            closeable = leesahNarmestelederReplayConsumer,
        ),
    )

    monitor.subscribe(ServerReady) {
        val sseListenerJob = launch { leaderChangeSSEListener.listenForLeaderChanges() }
        val leaderControlledConsumersJob = launch(Dispatchers.IO) {
            leaderChangeSSEListener.isLeader.collect { isLeader ->
                leaderControlledConsumers.forEach { consumer ->
                    consumer.onLeaderChange(isLeader)
                }
            }
        }
        leesahConsumer.listen()
        sendtSykmeldingConsumer.listen()

        monitor.subscribe(ApplicationStopPreparing) {
            runBlocking {
                leaderControlledConsumersJob.cancelAndJoin()
                sseListenerJob.cancelAndJoin()
                leaderControlledConsumers.forEach { consumer ->
                    consumer.stop()
                    consumer.close()
                }
                sendtSykmeldingConsumer.stop()
                leesahConsumer.stop()
            }
        }
    }
}
