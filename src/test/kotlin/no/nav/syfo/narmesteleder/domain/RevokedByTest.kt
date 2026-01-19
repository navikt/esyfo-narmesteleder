package no.nav.syfo.narmesteleder.domain
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class RevokedByTest : DescribeSpec({
    describe("RevokedBy.from") {
        it("should return LINEMANAGER for DEAKTIVERT_LEDER") {
            RevokedBy.from(BehovReason.DEAKTIVERT_LEDER) shouldBe RevokedBy.LINEMANAGER
        }
        it("should return EMPLOYEE for DEAKTIVERT_ARBEIDSTAKER_INNSENDT_SYKMELDING") {
            RevokedBy.from(BehovReason.DEAKTIVERT_ARBEIDSTAKER_INNSENDT_SYKMELDING) shouldBe RevokedBy.EMPLOYEE
        }
        it("should return EMPLOYEE for DEAKTIVERT_ARBEIDSTAKER") {
            RevokedBy.from(BehovReason.DEAKTIVERT_ARBEIDSTAKER) shouldBe RevokedBy.EMPLOYEE
        }
        it("should return null for unknown reason") {
            RevokedBy.from(BehovReason.UKJENT).shouldBeNull()
        }
    }
})
