package no.nav.syfo.sykmelding.retention.domain

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.LocalDate

class SykmeldingRetentionPolicyTest :
    FunSpec({
        val today = LocalDate.parse("2026-03-01")

        context("SykmeldingRetentionPolicy") {
            test("retains tom on the one-calendar-year boundary") {
                SykmeldingRetentionPolicy.shouldRetain(today.minusYears(1), today) shouldBe true
            }

            test("does not retain tom before the one-calendar-year boundary") {
                SykmeldingRetentionPolicy.shouldRetain(today.minusYears(1).minusDays(1), today) shouldBe false
            }

            test("uses calendar-year semantics") {
                SykmeldingRetentionPolicy.cutoff(LocalDate.parse("2024-02-29")) shouldBe LocalDate.parse("2023-02-28")
            }
        }
    })
