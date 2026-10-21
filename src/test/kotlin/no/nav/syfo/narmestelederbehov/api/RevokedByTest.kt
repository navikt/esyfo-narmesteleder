package no.nav.syfo.narmestelederbehov.api

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import no.nav.syfo.narmestelederbehov.domain.BehovReason

class RevokedByTest :
    FunSpec({
        context("RevokedBy.from") {
            test("should return LINEMANAGER for DEAKTIVERT_LEDER") {
                RevokedBy.from(BehovReason.DEAKTIVERT_LEDER) shouldBe RevokedBy.LINEMANAGER
            }
            test("should return EMPLOYEE for DEAKTIVERT_ARBEIDSTAKER_INNSENDT_SYKMELDING") {
                RevokedBy.from(BehovReason.DEAKTIVERT_ARBEIDSTAKER_INNSENDT_SYKMELDING) shouldBe RevokedBy.EMPLOYEE
            }
            test("should return EMPLOYEE for DEAKTIVERT_ARBEIDSTAKER") {
                RevokedBy.from(BehovReason.DEAKTIVERT_ARBEIDSTAKER) shouldBe RevokedBy.EMPLOYEE
            }
            test("should return null for unknown reason") {
                RevokedBy.from(BehovReason.UKJENT).shouldBeNull()
            }
        }
    })
