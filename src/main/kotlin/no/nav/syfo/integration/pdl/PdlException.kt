package no.nav.syfo.integration.pdl

sealed class PdlException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)
