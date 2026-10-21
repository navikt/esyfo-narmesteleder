package no.nav.syfo.plugins

import no.nav.syfo.narmestelederrelasjon.infrastructure.kafka.KafkaSykmeldingNarmestelederProducer
import no.nav.syfo.narmestelederrelasjon.infrastructure.kafka.NarmestelederLeesahProducer
import no.nav.syfo.narmestelederrelasjon.infrastructure.kafka.NarmestelederResponseKafkaMessage
import no.nav.syfo.narmestelederrelasjon.infrastructure.kafka.SykmeldingNarmestelederProducer
import no.nav.syfo.platform.kafka.JacksonKafkaSerializer
import no.nav.syfo.platform.kafka.producerProperties
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
