package no.nav.syfo.narmesteleder.exception

sealed class NLException(message: String) : RuntimeException(message) {
    sealed class NotFoundException(message: String) : NLException(message)
    sealed class ServerErrorException(message: String) : NLException(message)
}

class MissingIDException(message: String) : NLException.ServerErrorException(message)
class HovedenhetNotFoundException(message: String) : NLException.NotFoundException(message)
class BehovNotFoundException(message: String) : NLException.NotFoundException(message)
