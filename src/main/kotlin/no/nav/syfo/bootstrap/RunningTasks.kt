package no.nav.syfo.bootstrap

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopPreparing
import io.ktor.server.application.ServerReady
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import no.nav.person.pdl.leesah.Personhendelse
import no.nav.syfo.narmesteleder.kafka.LeesahNLKafkaConsumer
import no.nav.syfo.narmesteleder.kafka.NlBehovLeesahHandler
import no.nav.syfo.narmesteleder.service.LeaderControlledKafkaConsumer
import no.nav.syfo.narmestelederbehov.application.CreateNarmestelederbehov
import no.nav.syfo.narmestelederbehov.application.FulfillNarmestelederbehovFromLeesahUseCase
import no.nav.syfo.narmestelederrelasjon.application.PersistNarmestelederrelasjonerFromLeesahUseCase
import no.nav.syfo.narmestelederrelasjon.application.UpdateRelationPersonNamesUseCase
import no.nav.syfo.narmestelederrelasjon.infrastructure.kafka.NarmestelederLeesahProducer
import no.nav.syfo.narmestelederrelasjon.infrastructure.kafka.PdlLeesahConsumer
import no.nav.syfo.narmestelederrelasjon.infrastructure.kafka.PersistNarmestelederRegisterFromLeesahConsumer
import no.nav.syfo.platform.kafka.consumerProperties
import no.nav.syfo.platform.kafka.jacksonMapper
import no.nav.syfo.platform.observability.logger
import no.nav.syfo.platform.scheduling.LeaderChangeSSEListener
import no.nav.syfo.sykmelding.application.SendtSykmeldingHandler
import no.nav.syfo.sykmelding.infrastructure.kafka.PersistSendtSykmeldingConsumer
import no.nav.syfo.sykmelding.infrastructure.kafka.SendtSykmeldingKafkaConsumer
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.StringDeserializer
import org.koin.ktor.ext.inject

internal fun Application.configureKafkaConsumers(environment: Environment) {
    val createNarmestelederbehov by inject<CreateNarmestelederbehov>()
    val fulfillBehovFromLeesah by inject<FulfillNarmestelederbehovFromLeesahUseCase>()
    val sendtSykmeldingHandler by inject<SendtSykmeldingHandler>()
    val persistNarmestelederrelasjonerFromLeesah by inject<PersistNarmestelederrelasjonerFromLeesahUseCase>()
    val narmestelederLeesahProducer by inject<NarmestelederLeesahProducer>()
    val updateRelationPersonNames by inject<UpdateRelationPersonNamesUseCase>()
    val leaderChangeSSEListener by inject<LeaderChangeSSEListener>()
    val logger = logger()

    if (!environment.kafka.shouldConsumeTopics) {
        logger.info("Kafka consumers is not enabled, skipping configuration of consumers")
        return
    }

    logger.info("Configuring Kafka consumers")

    val leesahConsumer = LeesahNLKafkaConsumer(
        handler = NlBehovLeesahHandler(createNarmestelederbehov = createNarmestelederbehov),
        fulfillBehovFromLeesah = fulfillBehovFromLeesah,
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
        enabled = environment.otherProperties.persistSendtSykmelding,
    )
    val leesahNarmestelederReplayConsumer = PersistNarmestelederRegisterFromLeesahConsumer(
        persistFromLeesah = persistNarmestelederrelasjonerFromLeesah,
        narmestelederLeesahProducer = narmestelederLeesahProducer,
        jacksonMapper = jacksonMapper(),
        kafkaConsumerFactory = {
            KafkaConsumer(
                PersistNarmestelederRegisterFromLeesahConsumer.kafkaConsumerProperties(environment.kafka),
                StringDeserializer(),
                StringDeserializer(),
            )
        },
        scope = this,
        enabled = environment.otherProperties.persistNarmestelederRegister,
    ).apply {
        commitOnAllErrors = environment.kafka.commitOnAllErrors
    }

    val pdlLeesahConsumer = if (environment.otherProperties.pdlLeesahConsumerEnabled) {
        PdlLeesahConsumer(
            kafkaConsumer = KafkaConsumer<String, Personhendelse>(
                PdlLeesahConsumer.kafkaConsumerProperties(environment.kafka)
            ),
            scope = this,
            enabled = environment.otherProperties.pdlLeesahConsumerEnabled,
            updateRelationPersonNames = updateRelationPersonNames,
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
