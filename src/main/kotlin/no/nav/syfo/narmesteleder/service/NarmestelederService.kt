package no.nav.syfo.narmesteleder.service

import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import no.nav.syfo.narmesteleder.db.NarmesteLederBehovEntity
import no.nav.syfo.narmesteleder.db.NarmestelederDb

interface INarmestelederService {
    suspend fun saveAvbrytNarmestelederRelation(nlAvbrutt: NarmesteLederBehovEntity): UUID
}

class NarmestelederService(private val nlDb: NarmestelederDb) : INarmestelederService {
    override suspend fun saveAvbrytNarmestelederRelation(nlBehov: NarmesteLederBehovEntity): UUID =
        withContext(Dispatchers.IO) {
            nlDb.insertNlBehov(nlBehov)
        }
}
