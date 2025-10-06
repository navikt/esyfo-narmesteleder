package no.nav.syfo.narmesteleder.service

import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import no.nav.syfo.narmesteleder.db.NarmesteLederAvbruttEntity
import no.nav.syfo.narmesteleder.db.NarmesteLederDb

interface INarmestelederService {
    suspend fun saveAvbrytNarmestelederRelation(nlAvbrutt: NarmesteLederAvbruttEntity): UUID
}

class NarmestelederService(private val nlDb: NarmesteLederDb) : INarmestelederService {
    override suspend fun saveAvbrytNarmestelederRelation(nlAvbrutt: NarmesteLederAvbruttEntity): UUID =
        withContext(Dispatchers.IO) {
            nlDb.insertNlAvbrudd(nlAvbrutt)
        }
}
