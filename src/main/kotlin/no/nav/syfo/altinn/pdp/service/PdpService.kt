package no.nav.syfo.altinn.pdp.service

import no.nav.syfo.altinn.pdp.client.Decision
import no.nav.syfo.altinn.pdp.client.PdpClient
import no.nav.syfo.altinn.pdp.client.User
import no.nav.syfo.altinn.pdp.client.result

class PdpService(
    private val pdpClient: PdpClient,
) {

    suspend fun accessDecisionForResource(
        user: User,
        orgNumberSet: Set<String>,
        resource: String
    ): Decision {
        val pdpResponse = pdpClient.authorize(user, orgNumberSet, resource)
        return pdpResponse.result()
    }
}
