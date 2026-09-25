package no.nav.syfo.plugins

import no.nav.syfo.application.kafka.JacksonKafkaSerializer
import no.nav.syfo.application.kafka.producerProperties
import no.nav.syfo.narmesteleder.kafka.KafkaSykmeldingNarmestelederProducer
import no.nav.syfo.narmesteleder.kafka.NarmestelederLeesahProducer
import no.nav.syfo.narmesteleder.kafka.SykmeldingNarmestelederProducer
import no.nav.syfo.narmesteleder.kafka.model.NarmestelederResponseKafkaMessage
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.common.serialization.StringSerializer
import org.koin.dsl.module

internal fun kafkaProducersModule() = module {
    single<SykmeldingNarmestelederProducer> {
        KafkaSykmeldingNarmestelederProducer(
            KafkaProducer<String, NarmestelederResponseKafkaMessage>(
                producerProperties(env().kafka, JacksonKafkaSerializer::class, StringSerializer::class)
            )
        )
    }
    single {
        NarmestelederLeesahProducer(
            KafkaProducer<String, String?>(
                producerProperties(env().kafka, StringSerializer::class, StringSerializer::class)
            )
        )
    }
}
