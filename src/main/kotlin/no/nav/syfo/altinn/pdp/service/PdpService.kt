package no.nav.syfo.altinn.pdp.service

import no.nav.syfo.altinn.pdp.client.Bruker
import no.nav.syfo.altinn.pdp.client.IPdpClient
import no.nav.syfo.altinn.pdp.client.harTilgang

class PdpService(
    private val pdpClient: IPdpClient,
) {

    suspend fun hasAccessToResource(
        bruker: Bruker,
        orgnrSet: Set<String>,
        ressurs: String
    ): Boolean {
        val pdpResponse = pdpClient.authorize(bruker, orgnrSet, ressurs)
        return pdpResponse.harTilgang()
    }
}
