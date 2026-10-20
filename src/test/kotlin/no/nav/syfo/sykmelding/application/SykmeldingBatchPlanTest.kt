package no.nav.syfo.sykmelding.application

import defaultSendtSykmeldingMessage
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.time.LocalDate
import java.util.UUID

class SykmeldingBatchPlanTest :
    FunSpec({
        val today = LocalDate.parse("2026-03-01")

        fun sendtRecord(
            offset: Long,
            sykmeldingId: UUID = UUID.randomUUID(),
            fnr: String = "12345678901",
            orgnummer: String = "123456789",
            tom: LocalDate = today.minusDays(1),
        ) = SykmeldingRecord(
            offset = offset,
            sykmeldingId = sykmeldingId,
            message = defaultSendtSykmeldingMessage(
                sykmeldingId = sykmeldingId.toString(),
                fnr = fnr,
                orgnummer = orgnummer,
                sykmeldingsperioder = listOf(SykmeldingsperiodeAGDTO(fom = tom.minusDays(10), tom = tom)),
            ),
        )

        test("uses the record with the highest offset as final state per sykmeldingId") {
            val sykmeldingId = UUID.randomUUID()

            val plan = SykmeldingBatchPlan.from(
                listOf(
                    SykmeldingRecord(offset = 2, sykmeldingId = sykmeldingId, message = null),
                    sendtRecord(offset = 1, sykmeldingId = sykmeldingId),
                ),
                today,
            )

            plan.sykmeldingerToSave.shouldBeEmpty()
            plan.sykmeldingIdsToRevoke shouldContainExactly listOf(sykmeldingId)
            plan.uniqueKeyCount shouldBe 1
        }

        test("keeps only the sykmelding with the latest tom per arbeidsforhold") {
            val older = sendtRecord(offset = 1, tom = today.minusDays(10))
            val newer = sendtRecord(offset = 2, tom = today.minusDays(2))
            val otherOrg = sendtRecord(offset = 3, orgnummer = "999999999")

            val plan = SykmeldingBatchPlan.from(listOf(older, newer, otherOrg), today)

            plan.sykmeldingerToSave.map { it.sykmeldingId } shouldContainExactly
                listOf(newer.sykmeldingId, otherOrg.sykmeldingId)
            plan.uniqueKeyCount shouldBe 3
        }

        test("skips sykmeldinger without arbeidsgiver or outside the retention period") {
            val withoutArbeidsgiver = sendtRecord(offset = 1).let { record ->
                val message = requireNotNull(record.message)
                record.copy(message = message.copy(event = message.event.copy(arbeidsgiver = null)))
            }
            val tooOld = sendtRecord(offset = 2, orgnummer = "999999999", tom = today.minusYears(1).minusDays(1))

            val plan = SykmeldingBatchPlan.from(listOf(withoutArbeidsgiver, tooOld), today)

            plan.sykmeldingerToSave.shouldBeEmpty()
            plan.sykmeldingIdsToRevoke.shouldBeEmpty()
        }

        test("keeps a sykmelding whose latest period ended exactly one year ago") {
            val record = sendtRecord(offset = 1, tom = today.minusYears(1))

            val plan = SykmeldingBatchPlan.from(listOf(record), today)

            plan.sykmeldingerToSave.map { it.sykmeldingId } shouldContainExactly listOf(record.sykmeldingId)
        }

        test("saves when a sendt sykmelding follows a tombstone for the same sykmeldingId") {
            val sykmeldingId = UUID.randomUUID()

            val plan = SykmeldingBatchPlan.from(
                listOf(
                    SykmeldingRecord(offset = 1, sykmeldingId = sykmeldingId, message = null),
                    sendtRecord(offset = 2, sykmeldingId = sykmeldingId),
                ),
                today,
            )

            plan.sykmeldingerToSave.map { it.sykmeldingId } shouldContainExactly listOf(sykmeldingId)
            plan.sykmeldingIdsToRevoke.shouldBeEmpty()
        }

        test("maps the latest period and the message fields to the sykmelding") {
            val sykmeldingId = UUID.randomUUID()
            val earlierPeriod = SykmeldingsperiodeAGDTO(fom = today.minusDays(30), tom = today.minusDays(20))
            val latestPeriod = SykmeldingsperiodeAGDTO(fom = today.minusDays(5), tom = today.plusDays(5))
            val message = defaultSendtSykmeldingMessage(
                sykmeldingId = sykmeldingId.toString(),
                fnr = "10987654321",
                orgnummer = "555555555",
                sykmeldingsperioder = listOf(latestPeriod, earlierPeriod),
            )

            val plan = SykmeldingBatchPlan.from(
                listOf(SykmeldingRecord(offset = 1, sykmeldingId = sykmeldingId, message = message)),
                today,
            )

            plan.sykmeldingerToSave shouldContainExactly listOf(
                SendtSykmelding(
                    sykmeldingId = sykmeldingId,
                    fnr = "10987654321",
                    orgnummer = "555555555",
                    fom = latestPeriod.fom,
                    tom = latestPeriod.tom,
                    syketilfelleStartDato = message.sykmelding.syketilfelleStartDato,
                )
            )
        }
    })
