package no.nav.syfo.pdl.exception

sealed class PdlException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)
