package no.nav.syfo.bootstrap

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopPreparing
import io.ktor.server.application.ServerReady
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import no.nav.person.pdl.leesah.Personhendelse
import no.nav.syfo.narmestelederbehov.application.CreateNarmestelederbehov
import no.nav.syfo.narmestelederbehov.application.FulfillNarmestelederbehovFromLeesahUseCase
import no.nav.syfo.narmestelederbehov.infrastructure.kafka.LeesahNLKafkaConsumer
import no.nav.syfo.narmestelederbehov.infrastructure.kafka.NlBehovLeesahHandler
import no.nav.syfo.narmestelederrelasjon.application.leesah.PersistNarmestelederrelasjonerFromLeesahUseCase
import no.nav.syfo.narmestelederrelasjon.application.relationperson.UpdateRelationPersonNamesUseCase
import no.nav.syfo.narmestelederrelasjon.infrastructure.kafka.NarmestelederLeesahProducer
import no.nav.syfo.narmestelederrelasjon.infrastructure.kafka.NarmestelederrelasjonKafkaSettings
import no.nav.syfo.narmestelederrelasjon.infrastructure.kafka.PdlLeesahConsumer
import no.nav.syfo.narmestelederrelasjon.infrastructure.kafka.PersistNarmestelederRegisterFromLeesahConsumer
import no.nav.syfo.platform.kafka.KafkaEnvironment
import no.nav.syfo.platform.kafka.LeaderControlledKafkaConsumer
import no.nav.syfo.platform.kafka.consumerProperties
import no.nav.syfo.platform.kafka.jacksonMapper
import no.nav.syfo.platform.observability.logger
import no.nav.syfo.platform.scheduling.LeaderChangeListener
import no.nav.syfo.sykmelding.application.SendtSykmeldingHandler
import no.nav.syfo.sykmelding.infrastructure.kafka.PersistSendtSykmeldingConsumer
import no.nav.syfo.sykmelding.infrastructure.kafka.SendtSykmeldingKafkaConsumer
import no.nav.syfo.sykmelding.infrastructure.kafka.SykmeldingKafkaSettings
import no.nav.syfo.sykmelding.infrastructure.kafka.SykmeldingsregisterKafkaConsumer
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.StringDeserializer
import org.koin.ktor.ext.inject

internal fun Application.configureKafkaConsumers(
    kafkaSettings: KafkaEnvironment,
    narmestelederrelasjonKafkaSettings: NarmestelederrelasjonKafkaSettings,
    sykmeldingKafkaSettings: SykmeldingKafkaSettings,
) {
    val createNarmestelederbehov by inject<CreateNarmestelederbehov>()
    val fulfillBehovFromLeesah by inject<FulfillNarmestelederbehovFromLeesahUseCase>()
    val sendtSykmeldingHandler by inject<SendtSykmeldingHandler>()
    val persistNarmestelederrelasjonerFromLeesah by inject<PersistNarmestelederrelasjonerFromLeesahUseCase>()
    val narmestelederLeesahProducer by inject<NarmestelederLeesahProducer>()
    val updateRelationPersonNames by inject<UpdateRelationPersonNamesUseCase>()
    val leaderChangeListener by inject<LeaderChangeListener>()
    val logger = logger()

    if (!kafkaSettings.shouldConsumeTopics) {
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
                env = kafkaSettings,
                valueDeserializer = StringDeserializer::class,
                groupId = "esyfo-narmesteleder-les-behov"
            ),
            StringDeserializer(),
            StringDeserializer(),
        ),
        scope = this,
    ).apply {
        commitOnAllErrors = kafkaSettings.commitOnAllErrors
    }

    val sendtSykmeldingConsumer = SendtSykmeldingKafkaConsumer(
        handler = sendtSykmeldingHandler,
        jacksonMapper = jacksonMapper(),
        kafkaConsumer = KafkaConsumer(
            consumerProperties(
                env = kafkaSettings,
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
                PersistSendtSykmeldingConsumer.kafkaConsumerProperties(kafkaSettings),
                StringDeserializer(),
                StringDeserializer(),
            )
        },
        scope = this,
        enabled = sykmeldingKafkaSettings.persistSendtSykmelding,
    )
    val sykmeldingsregisterKafkaConsumer = if (sykmeldingKafkaSettings.consumeSykmeldingsregister) {
        SykmeldingsregisterKafkaConsumer(
            consumerFactory = {
                KafkaConsumer(
                    SykmeldingsregisterKafkaConsumer.kafkaConsumerProperties(kafkaSettings),
                    StringDeserializer(),
                    StringDeserializer(),
                )
            },
        )
    } else {
        logger.info("Sykmeldingsregister consumer is disabled, skipping configuration")
        null
    }
    val leesahNarmestelederReplayConsumer = PersistNarmestelederRegisterFromLeesahConsumer(
        persistFromLeesah = persistNarmestelederrelasjonerFromLeesah,
        narmestelederLeesahProducer = narmestelederLeesahProducer,
        jacksonMapper = jacksonMapper(),
        kafkaConsumerFactory = {
            KafkaConsumer(
                PersistNarmestelederRegisterFromLeesahConsumer.kafkaConsumerProperties(kafkaSettings),
                StringDeserializer(),
                StringDeserializer(),
            )
        },
        scope = this,
        enabled = narmestelederrelasjonKafkaSettings.persistRegister,
    ).apply {
        commitOnAllErrors = kafkaSettings.commitOnAllErrors
    }

    val pdlLeesahConsumer = if (narmestelederrelasjonKafkaSettings.pdlConsumerEnabled) {
        PdlLeesahConsumer(
            kafkaConsumer = KafkaConsumer<String, Personhendelse>(
                PdlLeesahConsumer.kafkaConsumerProperties(kafkaSettings)
            ),
            scope = this,
            enabled = narmestelederrelasjonKafkaSettings.pdlConsumerEnabled,
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
                enabled = sykmeldingKafkaSettings.persistSendtSykmelding,
            )
        )
        add(
            LeaderControlledKafkaConsumer(
                consumer = leesahNarmestelederReplayConsumer,
                enabled = narmestelederrelasjonKafkaSettings.persistRegister,
            )
        )
        pdlLeesahConsumer?.let {
            add(
                LeaderControlledKafkaConsumer(
                    consumer = it,
                    enabled = narmestelederrelasjonKafkaSettings.pdlConsumerEnabled,
                )
            )
        }
    }

    monitor.subscribe(ServerReady) {
        val leaderControlledConsumersJob = launch(Dispatchers.IO) {
            leaderChangeListener.isLeader.collect { isLeader ->
                leaderControlledConsumers.forEach { consumer ->
                    consumer.onLeaderChange(isLeader)
                }
            }
        }
        leesahConsumer.listen()
        sendtSykmeldingConsumer.listen()
        sykmeldingsregisterKafkaConsumer?.listen()
        monitor.subscribe(ApplicationStopPreparing) {
            runBlocking {
                leaderControlledConsumersJob.cancelAndJoin()
                leaderControlledConsumers.forEach { consumer ->
                    consumer.stop()
                    consumer.close()
                }
                sendtSykmeldingConsumer.stop()
                leesahConsumer.stop()
                sykmeldingsregisterKafkaConsumer?.stop()
            }
        }
    }
}
