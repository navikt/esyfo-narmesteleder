package no.nav.syfo.narmesteleder.exposed

import no.nav.syfo.narmesteleder.kafka.model.NarmestelederLeesahKafkaMessage
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass
import java.time.ZoneOffset

class NarmestelederEntity(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<NarmestelederEntity>(NarmestelederTable) {

        /**
         * Creates a new [NarmestelederEntity] from a [NarmestelederLeesahKafkaMessage].
         *
         * Must be called within an Exposed `transaction` block.
         */
        fun fromLeesahKafkaMessage(
            kafkaMessage: NarmestelederLeesahKafkaMessage,
        ): NarmestelederEntity = NarmestelederEntity.new {
            narmesteLederId = kafkaMessage.narmesteLederId
            orgnummer = kafkaMessage.orgnummer
            sykmeldtFnr = kafkaMessage.fnr
            narmestelederFnr = kafkaMessage.narmesteLederFnr
            narmestelederTelefonnummer = kafkaMessage.narmesteLederTelefonnummer
            narmestelederEpost = kafkaMessage.narmesteLederEpost
            arbeidsgiverForskutterer = kafkaMessage.arbeidsgiverForskutterer
            aktivFom = kafkaMessage.aktivFom.atStartOfDay().atOffset(ZoneOffset.UTC)
            aktivTom = kafkaMessage.aktivTom?.atStartOfDay()?.atOffset(ZoneOffset.UTC)
        }
    }

    var narmesteLederId by NarmestelederTable.narmestelederId
    var orgnummer by NarmestelederTable.orgnummer
    var sykmeldtFnr by NarmestelederTable.sykmeldtFnr
    var narmestelederFnr by NarmestelederTable.narmestelederFnr
    var narmestelederTelefonnummer by NarmestelederTable.narmestelederTelefonnummer
    var narmestelederEpost by NarmestelederTable.narmestelederEpost
    var arbeidsgiverForskutterer by NarmestelederTable.arbeidsgiverForskutterer
    var aktivFom by NarmestelederTable.aktivFom
    var aktivTom by NarmestelederTable.aktivTom
    var created by NarmestelederTable.created
    var updated by NarmestelederTable.updated
}
