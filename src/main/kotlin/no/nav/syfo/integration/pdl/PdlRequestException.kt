package no.nav.syfo.integration.pdl

open class PdlRequestException(
    message: String,
    cause: Throwable? = null
) : PdlException(message, cause)
