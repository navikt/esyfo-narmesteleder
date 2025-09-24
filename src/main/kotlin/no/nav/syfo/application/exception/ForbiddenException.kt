package no.nav.syfo.application.exception

class ForbiddenException(
    message: String = "Forbidden",
) : RuntimeException(message)
