package no.nav.syfo.application.exceptions

class UnauthorizedException(
    message: String = "Unauthorized",
) : RuntimeException(message)
