package no.nav.syfo.narmestelederrelasjon.infrastructure.kafka

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.OffsetDateTime

class NarmestelederLeesahKafkaMessageTest :
    FunSpec({
        val timestamp = OffsetDateTime.parse("2026-06-15T14:00:00+02:00")

        (LeesahStatus.entries + null).forEach { status ->
            test("only an employment-ended revocation carries the source's revocation time (status $status)") {
                val relasjon = narmestelederLeesahKafkaMessage().copy(status = status, timestamp = timestamp).toLeesahNarmestelederrelasjon()

                relasjon.sourceEmploymentRevocationAt shouldBe
                    if (status == LeesahStatus.DEAKTIVERT_ARBEIDSFORHOLD) timestamp.toInstant() else null
            }
        }
    })
