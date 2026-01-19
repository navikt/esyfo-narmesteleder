package no.nav.syfo.application.exception

import io.ktor.client.plugins.ResponseException

class UpstreamRequestException(
    message: String,
    cause: ResponseException? = null
) : RuntimeException(message, cause)
