package no.nav.syfo.platform.auth

class UnauthorizedException(
    message: String = "Unauthorized",
) : RuntimeException(message)
