package no.nav.syfo.pdl.exception

class PdlResourceNotFoundException(
    message: String,
    cause: Throwable? = null
) : PdlException(message, cause)
