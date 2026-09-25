package no.nav.syfo.narmestelederrelasjon.api

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import no.nav.syfo.application.api.ErrorType
import no.nav.syfo.narmestelederrelasjon.application.RevokeNarmestelederrelasjonResult

class RevokeNotFoundExceptionTest :
    FunSpec({
        RevokeNarmestelederrelasjonResult.Reason.entries.forEach { reason ->
            test("revoke not found maps $reason to the legacy error") {
                val exception = RevokeNarmestelederrelasjonResult.NotFound(reason).toRevokeNotFoundException()
                exception.isAlreadyLogged shouldBe (reason == RevokeNarmestelederrelasjonResult.Reason.ACCESS_DENIED)
                exception.errorMessage shouldBe "Linemanager relation not found"
                exception.type shouldBe ErrorType.NOT_FOUND
            }
        }
    })
