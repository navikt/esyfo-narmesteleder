package no.nav.syfo.altinn.pdp.client

class FakePdpClient: IPdpClient {
    override suspend fun authorize(
        user: User,
        orgNumberSet: Set<String>,
        resource: String
    ): PdpResponse {
        return PdpResponse(
            response = listOf(
                DecisionResult(Decision.Permit)
            )
        )
    }
}
