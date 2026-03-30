package no.nav.syfo.narmesteleder.service.validators

import no.nav.syfo.application.api.ErrorType
import no.nav.syfo.application.exception.ApiErrorException

fun nlrequire(value: Boolean, type: ErrorType, lazyMessage: () -> String) {
    if (!value) {
        throw ApiErrorException.BadRequestException(type = type, errorMessage = lazyMessage())
    }
}
