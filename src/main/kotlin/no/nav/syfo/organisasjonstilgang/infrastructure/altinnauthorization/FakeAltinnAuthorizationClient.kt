package no.nav.syfo.organisasjonstilgang.infrastructure.altinnauthorization

class FakeAltinnAuthorizationClient : AltinnAuthorizationClient {
    override suspend fun authorize(
        user: User,
        orgNumberSet: Set<String>,
        resource: String
    ): AltinnAuthorizationResponse = AltinnAuthorizationResponse(
        response = listOf(
            DecisionResult(Decision.Permit)
        )
    )
}
