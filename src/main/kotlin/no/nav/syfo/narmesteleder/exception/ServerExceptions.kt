package no.nav.syfo.narmesteleder.exception

sealed class LinemanagerException(message: String) : RuntimeException(message) {
    sealed class NotFoundException(message: String) : LinemanagerException(message)
    sealed class ServerErrorException(message: String) : LinemanagerException(message)
}

class MissingIDException(message: String) : LinemanagerException.ServerErrorException(message)
