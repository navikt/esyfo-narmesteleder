package no.nav.syfo.narmestelederrelasjon.api

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import no.nav.syfo.narmestelederrelasjon.application.revoke.RevokeNarmestelederrelasjonResult
import no.nav.syfo.platform.api.ErrorType

class RevokeNotFoundExceptionTest :
    FunSpec({
        RevokeNarmestelederrelasjonResult.Reason.entries.forEach { reason ->
            test("revoke not found maps $reason to the NOT_FOUND error") {
                val exception = RevokeNarmestelederrelasjonResult.NotFound(reason).toRevokeNotFoundException()
                exception.isAlreadyLogged shouldBe (reason == RevokeNarmestelederrelasjonResult.Reason.ACCESS_DENIED)
                exception.errorMessage shouldBe "Linemanager relation not found"
                exception.type shouldBe ErrorType.NOT_FOUND
            }
        }
    })
