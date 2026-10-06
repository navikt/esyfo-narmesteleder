package no.nav.syfo.integration.pdl

class PdlResourceNotFoundException(
    message: String,
    cause: Throwable? = null
) : PdlException(message, cause)
