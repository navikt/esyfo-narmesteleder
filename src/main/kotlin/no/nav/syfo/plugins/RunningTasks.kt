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
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.StringDeserializer
import org.koin.ktor.ext.inject

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
        launchKafkaListener(
            applicationState = applicationState,
            kafkaListener = LeesahNLKafkaConsumer(
                nlLeesahService = nlLeesahService,
                jacksonMapper = jacksonMapper(),
                kafkaConsumer = kafkaConsumer,
            )
        )
    }
    monitor.subscribe(ApplicationStopping) {
        leesahKafkaJob.cancel()
    }
}

private suspend fun launchKafkaListener(applicationState: ApplicationState, kafkaListener: KafkaListener) {
    try {
        kafkaListener.listen(applicationState)
    } finally {
        applicationState.ready = false
    }
}
