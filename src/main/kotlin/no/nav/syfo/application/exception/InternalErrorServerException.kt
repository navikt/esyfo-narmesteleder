package no.nav.syfo.application.exception

class InternalServerErrorException(
    message: String = "Internal Server Error",
) : RuntimeException(message)
