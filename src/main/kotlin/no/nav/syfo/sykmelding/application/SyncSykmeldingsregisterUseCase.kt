package no.nav.syfo.sykmelding.application

fun interface SyncSykmeldingsregister {
    suspend fun execute(messages: List<SykmeldingsregisterMessage>)
}

/** Does nothing yet; storing the messages belongs to #809. */
class SyncSykmeldingsregisterUseCase : SyncSykmeldingsregister {
    override suspend fun execute(messages: List<SykmeldingsregisterMessage>) = Unit
}
