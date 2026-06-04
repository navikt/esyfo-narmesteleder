package no.nav.syfo.plugins

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopPreparing
import io.ktor.server.application.ServerReady
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import no.nav.person.pdl.leesah.Personhendelse
import no.nav.syfo.application.environment.Environment
import no.nav.syfo.application.kafka.avroConsumerProperties
import no.nav.syfo.application.kafka.consumerProperties
import no.nav.syfo.application.kafka.jacksonMapper
import no.nav.syfo.application.leaderelection.LeaderChangeSSEListener
import no.nav.syfo.narmesteleder.kafka.LeesahNLKafkaConsumer
import no.nav.syfo.narmesteleder.kafka.NlBehovLeesahHandler
import no.nav.syfo.narmesteleder.kafka.PersistNarmestelederRegisterFromLeesahConsumer
import no.nav.syfo.narmesteleder.service.LeaderControlledKafkaConsumer
import no.nav.syfo.narmesteleder.service.NarmestelederRegisterService
import no.nav.syfo.pdl.leesah.PdlLeesahConsumer
import no.nav.syfo.pdl.leesah.PdlLeesahNameUpdateService
import no.nav.syfo.sykmelding.kafka.PersistSendtSykmeldingConsumer
import no.nav.syfo.sykmelding.kafka.SendtSykmeldingHandler
import no.nav.syfo.sykmelding.kafka.SendtSykmeldingKafkaConsumer
import no.nav.syfo.util.logger
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.StringDeserializer
import org.koin.ktor.ext.inject

fun Application.configureKafkaConsumers() {
    val nlLeesahHandler by inject<NlBehovLeesahHandler>()
    val sendtSykmeldingHandler by inject<SendtSykmeldingHandler>()
    val narmestelederRegisterService by inject<NarmestelederRegisterService>()
    val pdlLeesahNameUpdateService by inject<PdlLeesahNameUpdateService>()
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
        kafkaConsumerFactory = {
            KafkaConsumer(
                PersistSendtSykmeldingConsumer.kafkaConsumerProperties(environment.kafka),
                StringDeserializer(),
                StringDeserializer(),
            )
        },
        scope = this,
        env = environment.otherProperties,
    )
    val leesahNarmestelederReplayConsumer = PersistNarmestelederRegisterFromLeesahConsumer(
        handler = narmestelederRegisterService,
        jacksonMapper = jacksonMapper(),
        kafkaConsumerFactory = {
            KafkaConsumer(
                PersistNarmestelederRegisterFromLeesahConsumer.kafkaConsumerProperties(environment.kafka),
                StringDeserializer(),
                StringDeserializer(),
            )
        },
        scope = this,
        env = environment.otherProperties,
    ).apply {
        commitOnAllErrors = environment.kafka.commitOnAllErrors
    }

    val pdlLeesahConsumer = if (environment.otherProperties.pdlLeesahConsumerEnabled) {
        PdlLeesahConsumer(
            kafkaConsumer = KafkaConsumer<String, Personhendelse>(
                avroConsumerProperties(
                    groupId = PdlLeesahConsumer.PDL_LEESAH_CONSUMER_GROUP,
                    env = environment.kafka,
                )
            ),
            scope = this,
            env = environment.otherProperties,
            pdlLeesahNameUpdateService = pdlLeesahNameUpdateService,
        )
    } else {
        logger.info("PDL Leesah consumer is disabled, skipping configuration for {}", PdlLeesahConsumer.PDL_LEESAH_TOPIC)
        null
    }

    val leaderControlledConsumers = buildList {
        add(
            LeaderControlledKafkaConsumer(
                consumer = persistSendtSykmeldingConsumer,
                enabled = environment.otherProperties.persistSendtSykmelding,
            )
        )
        add(
            LeaderControlledKafkaConsumer(
                consumer = leesahNarmestelederReplayConsumer,
                enabled = environment.otherProperties.persistNarmestelederRegister,
            )
        )
        pdlLeesahConsumer?.let {
            add(
                LeaderControlledKafkaConsumer(
                    consumer = it,
                    enabled = environment.otherProperties.pdlLeesahConsumerEnabled,
                )
            )
        }
    }

    monitor.subscribe(ServerReady) {
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
