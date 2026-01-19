package no.nav.syfo.pdl.exception

class PdlRequestException(
    message: String,
    cause: Throwable? = null
) : PdlException(message, cause)
