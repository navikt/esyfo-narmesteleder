package no.nav.syfo.plugins

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopPreparing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import no.nav.syfo.application.environment.Environment
import no.nav.syfo.application.events.LeaderChange
import no.nav.syfo.application.events.LeaderChangeEvent
import no.nav.syfo.application.kafka.consumerProperties
import no.nav.syfo.application.kafka.jacksonMapper
import no.nav.syfo.narmesteleder.kafka.LeesahNLKafkaConsumer
import no.nav.syfo.narmesteleder.kafka.NlBehovLeesahHandler
import no.nav.syfo.sykmelding.kafka.PersistSendtSykmeldingConsumer
import no.nav.syfo.sykmelding.kafka.SendtSykmeldingHandler
import no.nav.syfo.sykmelding.kafka.SendtSykmeldingKafkaConsumer
import no.nav.syfo.util.logger
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.StringDeserializer
import java.util.Collections
import kotlin.time.Duration.Companion.minutes

fun Application.configureKafkaConsumers(
    nlLeesahHandler: NlBehovLeesahHandler,
    sendtSykmeldingHandler: SendtSykmeldingHandler,
    environment: Environment,
) {
    val logger = logger()

    if (!environment.kafka.shouldConsumeTopics) {
        logger.info("Kafka consumers is not enabled, skipping configuration of consumers")
        return
    }

    logger.info("Configuring Kafka consumers")

    val kafkaConsumerJobs: MutableList<Job> = Collections.synchronizedList(mutableListOf())

    monitor.subscribe(LeaderChangeEvent) { event ->
        when (event) {
            is LeaderChange.Unaffected -> {
                logger.debug("Unaffected by leader election. Skipping Kafka consumers")
            }

            is LeaderChange.Promoted -> {
                logger.info("Elected leader — starting Kafka consumers")

                synchronized(kafkaConsumerJobs) {
                    kafkaConsumerJobs.forEach { it.cancel() }
                    kafkaConsumerJobs.clear()
                }

                kafkaConsumerJobs += launch(Dispatchers.IO) {
                    LeesahNLKafkaConsumer(
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
                        commitOnAllErrors = environment.kafka.commitOnAllErrors,
                    ).runTask()
                }

                kafkaConsumerJobs += launch(Dispatchers.IO) {
                    SendtSykmeldingKafkaConsumer(
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
                    ).runTask()
                }

                if (environment.otherProperties.persistSendtSykmelding) {
                    kafkaConsumerJobs += launch(Dispatchers.IO) {
                        PersistSendtSykmeldingConsumer(
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
                                        5.minutes.inWholeMilliseconds.toString()
                                    )
                                    put(ConsumerConfig.FETCH_MIN_BYTES_CONFIG, "1048576")
                                    put(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG, "500")
                                },
                                StringDeserializer(),
                                StringDeserializer(),
                            ),
                            commitOnAllErrors = environment.kafka.commitOnAllErrors,
                        ).runTask()
                    }
                }
            }

            is LeaderChange.Demoted -> {
                logger.info("No longer leader — stopping Kafka consumers")
                synchronized(kafkaConsumerJobs) {
                    kafkaConsumerJobs.forEach { it.cancel() }
                    kafkaConsumerJobs.clear()
                }
            }
        }
    }

    monitor.subscribe(ApplicationStopPreparing) {
        synchronized(kafkaConsumerJobs) {
            kafkaConsumerJobs.forEach { it.cancel() }
            kafkaConsumerJobs.clear()
        }
    }
}
