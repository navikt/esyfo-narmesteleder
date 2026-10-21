package no.nav.syfo.bootstrap

import no.nav.syfo.narmestelederrelasjon.infrastructure.kafka.KafkaSykmeldingNarmestelederProducer
import no.nav.syfo.narmestelederrelasjon.infrastructure.kafka.NarmestelederLeesahProducer
import no.nav.syfo.narmestelederrelasjon.infrastructure.kafka.SykmeldingNarmestelederProducer
import no.nav.syfo.platform.kafka.JacksonKafkaSerializer
import no.nav.syfo.platform.kafka.KafkaEnvironment
import no.nav.syfo.platform.kafka.producerProperties
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.common.serialization.StringSerializer
import org.koin.dsl.module

internal fun kafkaProducersModule(kafka: KafkaEnvironment) = module {
    single<SykmeldingNarmestelederProducer> {
        KafkaSykmeldingNarmestelederProducer(
            KafkaProducer(
                producerProperties(kafka, JacksonKafkaSerializer::class, StringSerializer::class)
            )
        )
    }
    single {
        NarmestelederLeesahProducer(
            KafkaProducer(
                producerProperties(kafka, StringSerializer::class, StringSerializer::class)
            )
        )
    }
}
