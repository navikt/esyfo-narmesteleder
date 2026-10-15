package no.nav.syfo.organisasjonstilgang.infrastructure.altinnauthorization

import no.nav.syfo.platform.upstream.UpstreamResult

class FakeAltinnAuthorizationClient : AltinnAuthorizationClient {
    override suspend fun authorize(
        user: User,
        orgNumberSet: Set<String>,
        resource: String
    ): UpstreamResult<Decision> = UpstreamResult.Success(Decision.Permit)
}
