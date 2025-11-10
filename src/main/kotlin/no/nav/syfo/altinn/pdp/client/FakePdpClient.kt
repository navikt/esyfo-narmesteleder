package no.nav.syfo.altinn.pdp.client

class FakePdpClient: IPdpClient {
    override suspend fun authorize(
        bruker: Bruker,
        orgnrSet: Set<String>,
        ressurs: String
    ): PdpResponse {
        return PdpResponse(
            response = listOf(
                DecisionResult(Decision.Permit)
            )
        )
    }
}
