package no.nav.syfo.plugins

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.application.ApplicationStopping
import kotlinx.coroutines.runBlocking
import no.nav.syfo.application.ApplicationState
import no.nav.syfo.application.Environment
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

    val properties = consumerProperties(
        env = environment.kafka,
        valueSerializer = StringDeserializer::class,
        groupId = "esyfo-narmesteleder-les-behov"
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
        scope = this,
    )
    monitor.subscribe(ApplicationStarted) {
        leesahConsumer.listen(applicationState)
    }

    monitor.subscribe(ApplicationStopping) {
        runBlocking {
            leesahConsumer.stop()
        }
    }
}
