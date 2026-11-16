package no.nav.syfo.sykmelding.application

import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonalIdentificationNumber
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

data class SykmeldingsregisterPosition(val partition: Int, val offset: Long)

/** A validated sykmeldingsregister record, keyed by `sykmeldingId`. The latest message per key is the truth. */
sealed interface SykmeldingsregisterMessage {
    val sykmeldingId: UUID
    val position: SykmeldingsregisterPosition

    data class Sent(
        override val sykmeldingId: UUID,
        override val position: SykmeldingsregisterPosition,
        val employee: PersonalIdentificationNumber,
        val organizationNumber: OrganizationNumber,
        val sentAt: OffsetDateTime,
        val maxTom: LocalDate,
        val narmestelederConfirmed: Boolean,
    ) : SykmeldingsregisterMessage

    data class Tombstone(
        override val sykmeldingId: UUID,
        override val position: SykmeldingsregisterPosition,
    ) : SykmeldingsregisterMessage
}
