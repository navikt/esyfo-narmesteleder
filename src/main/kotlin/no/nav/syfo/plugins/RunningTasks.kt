package no.nav.syfo.plugins

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopping
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import no.nav.syfo.application.ApplicationState
import no.nav.syfo.application.Environment
import no.nav.syfo.application.kafka.KafkaListener
import no.nav.syfo.application.kafka.consumerProperties
import no.nav.syfo.application.kafka.jacksonMapper
import no.nav.syfo.narmesteleder.kafka.LeesahNLKafkaConsumer
import no.nav.syfo.narmesteleder.service.NarmesteLederLeesahService
import no.nav.syfo.util.logger
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.StringDeserializer
import org.koin.ktor.ext.inject

private val logger = logger("io.ktor.server.application.Application.RunningTasks")

fun Application.configureRunningTasks(applicationState: ApplicationState) {
    val nlLeesahService by inject<NarmesteLederLeesahService>()
    val environment by inject<Environment>()

    val leesahKafkaJob = launch(Dispatchers.IO) {
        val properties = consumerProperties(
            env = environment.kafka,
            valueSerializer = StringDeserializer::class
        )
        val kafkaConsumer = KafkaConsumer(
            properties,
            StringDeserializer(),
            StringDeserializer(),
        )
        val leesahConsumer = LeesahNLKafkaConsumer(
            nlLeesahService = nlLeesahService,
            jacksonMapper = jacksonMapper(),
            kafkaConsumer = kafkaConsumer,
        )
        leesahConsumer.listen(applicationState)
    }
    monitor.subscribe(ApplicationStopping) {
        logger.info("Application is stopping.")
        leesahKafkaJob.cancel()
    }
}
