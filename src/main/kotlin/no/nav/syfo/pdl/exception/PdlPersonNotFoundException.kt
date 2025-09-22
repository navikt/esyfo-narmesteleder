package no.nav.syfo.pdl.exception

class PdlPersonNotFoundException(
    message: String, cause: Throwable? = null
) : PdlException(message, cause)
