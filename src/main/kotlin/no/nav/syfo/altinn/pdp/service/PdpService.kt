package no.nav.syfo.altinn.pdp.service

import no.nav.syfo.altinn.pdp.client.User
import no.nav.syfo.altinn.pdp.client.IPdpClient
import no.nav.syfo.altinn.pdp.client.hasAccess

class PdpService(
    private val pdpClient: IPdpClient,
) {

    suspend fun hasAccessToResource(
        user: User,
        orgNumberSet: Set<String>,
        resource: String
    ): Boolean {
        val pdpResponse = pdpClient.authorize(user, orgNumberSet, resource)
        return pdpResponse.hasAccess()
    }
}
