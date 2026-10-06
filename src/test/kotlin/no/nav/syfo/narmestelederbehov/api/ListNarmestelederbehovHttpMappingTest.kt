package no.nav.syfo.narmestelederbehov.api

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import no.nav.syfo.application.exception.ApiErrorException
import no.nav.syfo.narmestelederbehov.application.ListNarmestelederbehovResult

class ListNarmestelederbehovHttpMappingTest :
    FunSpec({
        test("missing person maps to a generic 500 that StatusPages logs as a request failure") {
            val error = shouldThrow<ApiErrorException.InternalServerErrorException> {
                ListNarmestelederbehovResult.PersonNotFound.toLinemanagerRequirementCollection(pageSize = 10)
            }
            error.errorMessage shouldBe "Internal server error"
            error.isAlreadyLogged shouldBe false
        }
    })
