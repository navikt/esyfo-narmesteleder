package no.nav.syfo.texas

import com.auth0.jwt.JWT
import createMockToken
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.fullPath
import io.ktor.http.headersOf
import io.ktor.serialization.jackson.jackson
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.time.Instant
import no.nav.syfo.texas.client.TexasHttpClient
import no.nav.syfo.texas.client.TexasResponse

class AltinnTokenProviderTest : DescribeSpec({

    val mockTexasClient = mockk<TexasHttpClient>()
    val altinnBaseUrl = "https://altinn.test"

    beforeTest {
        clearAllMocks()
    }

    describe("AltinnTokenProvider") {
        describe("token") {
            it("should fetch new token when cache is empty") {
                val targetScope = "digdir:dialogporten.serviceprovider"
                val maskinportenToken = createMockToken("12345678901", scope = targetScope)
                val altinnToken = createMockToken("12345678901", scope = targetScope)

                val mockEngine = MockEngine { request ->
                    when (request.url.fullPath) {
                        "/authentication/api/v1/exchange/maskinporten" -> {
                            respond(
                                content = "\"$altinnToken\"",
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                            )
                        }

                        else -> error("Unhandled ${request.url.fullPath}")
                    }
                }

                val httpClient = HttpClient(mockEngine) {
                    install(ContentNegotiation) {
                        jackson()
                    }
                }

                coEvery { mockTexasClient.systemToken("maskinporten", targetScope) } returns TexasResponse(
                    accessToken = maskinportenToken,
                    expiresIn = 3600,
                    tokenType = "Bearer"
                )

                val tokenProvider = AltinnTokenProvider(
                    texasHttpClient = mockTexasClient,
                    httpClient = httpClient,
                    altinnBaseUrl = altinnBaseUrl
                )

                val result = tokenProvider.token(targetScope)

                result shouldNotBe null
                result.accessToken shouldBe altinnToken
                result.scope shouldBe targetScope

                coVerify(exactly = 1) { mockTexasClient.systemToken("maskinporten", targetScope) }
            }

            it("should return cached token when valid and not expiring soon") {
                val targetScope = "digdir:dialogporten.serviceprovider"
                val maskinportenToken = createMockToken(
                    ident = "123456789",
                    scope = targetScope,
                    expiresAt = Instant.now().plusSeconds(7200)
                )
                val altinnToken = createMockToken(
                    ident = "123456789",
                    scope = targetScope,
                    expiresAt = Instant.now().plusSeconds(7200)
                )

                val mockEngine = MockEngine { request ->
                    when (request.url.fullPath) {
                        "/authentication/api/v1/exchange/maskinporten" -> {
                            respond(
                                content = "\"$altinnToken\"",
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                            )
                        }

                        else -> error("Unhandled ${request.url.fullPath}")
                    }
                }

                val httpClient = HttpClient(mockEngine) {
                    install(ContentNegotiation) {
                        jackson()
                    }
                }

                coEvery { mockTexasClient.systemToken("maskinporten", targetScope) } returns TexasResponse(
                    accessToken = maskinportenToken,
                    expiresIn = 3600,
                    tokenType = "Bearer"
                )

                val tokenProvider = AltinnTokenProvider(
                    texasHttpClient = mockTexasClient,
                    httpClient = httpClient,
                    altinnBaseUrl = altinnBaseUrl
                )

                // First call - should fetch new token
                val firstResult = tokenProvider.token(targetScope)

                // Second call - should return cached token
                val secondResult = tokenProvider.token(targetScope)

                firstResult.accessToken shouldBe altinnToken
                secondResult.accessToken shouldBe altinnToken

                // Should only call Texas once
                coVerify(exactly = 1) { mockTexasClient.systemToken("maskinporten", targetScope) }
            }

            it("should refresh token when it expires soon (between 120 and 300 seconds)") {
                val targetScope = "digdir:dialogporten.serviceprovider"
                val maskinportenToken = createMockToken(
                    ident = "123456789",
                    scope = targetScope,
                    expiresAt = Instant.now().plusSeconds(7200)
                )
                val oldAltinnToken =
                    createMockToken(
                        ident = "123456789",
                        scope = targetScope,
                        expiresAt = Instant.now().plusSeconds(200)
                    ) // 200 seconds - should refresh
                val newAltinnToken = createMockToken(
                    ident = "123456789",
                    scope = targetScope,
                    expiresAt = Instant.now().plusSeconds(3600)
                )

                var callCount = 0
                val mockEngine = MockEngine { request ->
                    when (request.url.fullPath) {
                        "/authentication/api/v1/exchange/maskinporten" -> {
                            callCount++
                            val tokenToReturn = if (callCount == 1) oldAltinnToken else newAltinnToken
                            respond(
                                content = "\"$tokenToReturn\"",
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                            )
                        }

                        "/authentication/api/v1/refresh" -> {
                            respond(
                                content = "\"$newAltinnToken\"",
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                            )
                        }

                        else -> error("Unhandled ${request.url.fullPath}")
                    }
                }

                val httpClient = HttpClient(mockEngine) {
                    install(ContentNegotiation) {
                        jackson()
                    }
                }

                coEvery { mockTexasClient.systemToken("maskinporten", targetScope) } returns TexasResponse(
                    accessToken = maskinportenToken,
                    expiresIn = 3600,
                    tokenType = "Bearer"
                )

                val tokenProvider = AltinnTokenProvider(
                    texasHttpClient = mockTexasClient,
                    httpClient = httpClient,
                    altinnBaseUrl = altinnBaseUrl
                )

                // First call - get initial token
                val firstResult = tokenProvider.token(targetScope)
                firstResult.accessToken shouldBe oldAltinnToken

                // Second call - should refresh token because it expires soon
                val secondResult = tokenProvider.token(targetScope)
                secondResult.accessToken shouldBe newAltinnToken
            }

            it("should handle refresh token failure and fetch new token from maskinporten") {
                val targetScope = "digdir:dialogporten.serviceprovider"
                val maskinportenToken = createMockToken(
                    ident = "123456789",
                    scope = targetScope,
                    expiresAt = Instant.now().plusSeconds(7200)
                )
                val oldAltinnToken = createMockToken(
                    ident = "123456789",
                    scope = targetScope,
                    expiresAt = Instant.now().plusSeconds(200)
                )
                val newAltinnToken = createMockToken(
                    ident = "123456789",
                    scope = targetScope,
                    expiresAt = Instant.now().plusSeconds(3600)
                )

                var callCount = 0
                val mockEngine = MockEngine { request ->
                    when (request.url.fullPath) {
                        "/authentication/api/v1/exchange/maskinporten" -> {
                            callCount++
                            val tokenToReturn = if (callCount == 1) oldAltinnToken else newAltinnToken
                            respond(
                                content = "\"$tokenToReturn\"",
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                            )
                        }

                        "/authentication/api/v1/refresh" -> {
                            respond(
                                content = "",
                                status = HttpStatusCode.Unauthorized,
                                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                            )
                        }

                        else -> error("Unhandled ${request.url.fullPath}")
                    }
                }

                val httpClient = HttpClient(mockEngine) {
                    install(ContentNegotiation) {
                        jackson()
                    }
                }

                coEvery { mockTexasClient.systemToken("maskinporten", targetScope) } returns TexasResponse(
                    accessToken = maskinportenToken,
                    expiresIn = 3600,
                    tokenType = "Bearer"
                )

                val tokenProvider = AltinnTokenProvider(
                    texasHttpClient = mockTexasClient,
                    httpClient = httpClient,
                    altinnBaseUrl = altinnBaseUrl
                )

                // First call - get initial token
                val firstResult = tokenProvider.token(targetScope)
                firstResult.accessToken shouldBe oldAltinnToken

                // Second call - refresh fails, should get new token from maskinporten
                val secondResult = tokenProvider.token(targetScope)
                secondResult.accessToken shouldBe newAltinnToken

                // Should have called systemToken twice (initial + after refresh failure)
                coVerify(exactly = 2) { mockTexasClient.systemToken("maskinporten", targetScope) }
            }

            it("should fetch new token when existing token has expired") {
                val targetScope = "digdir:dialogporten.serviceprovider"
                val maskinportenToken = createMockToken(
                    ident = "123456789",
                    scope = targetScope,
                    expiresAt = Instant.now().plusSeconds(7200)
                )
                val expiredAltinnToken = createMockToken(
                    ident = "123456789",
                    scope = targetScope,
                    expiresAt = Instant.now().minusSeconds(100)
                )
                val newAltinnToken = createMockToken(
                    ident = "123456789",
                    scope = targetScope,
                    expiresAt = Instant.now().plusSeconds(3600)
                )

                var callCount = 0
                val mockEngine = MockEngine { request ->
                    when (request.url.fullPath) {
                        "/authentication/api/v1/exchange/maskinporten" -> {
                            callCount++
                            val tokenToReturn = if (callCount == 1) expiredAltinnToken else newAltinnToken
                            respond(
                                content = "\"$tokenToReturn\"",
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                            )
                        }

                        else -> error("Unhandled ${request.url.fullPath}")
                    }
                }

                val httpClient = HttpClient(mockEngine) {
                    install(ContentNegotiation) {
                        jackson()
                    }
                }

                coEvery { mockTexasClient.systemToken("maskinporten", targetScope) } returns TexasResponse(
                    accessToken = maskinportenToken,
                    expiresIn = 3600,
                    tokenType = "Bearer"
                )

                val tokenProvider = AltinnTokenProvider(
                    texasHttpClient = mockTexasClient,
                    httpClient = httpClient,
                    altinnBaseUrl = altinnBaseUrl
                )

                // First call - get expired token
                val firstResult = tokenProvider.token(targetScope)
                firstResult.accessToken shouldBe expiredAltinnToken

                // Second call - should fetch new token because first one is expired
                val secondResult = tokenProvider.token(targetScope)
                secondResult.accessToken shouldBe newAltinnToken

                // Should have called systemToken twice
                coVerify(exactly = 2) { mockTexasClient.systemToken("maskinporten", targetScope) }
            }

            it("should handle multiple different target scopes independently") {
                val targetScope1 = "digdir:dialogporten.serviceprovider"
                val targetScope2 = "nav:another.scope"
                val maskinportenToken = createMockToken(
                    ident = "123456789",
                    scope = targetScope1,
                    expiresAt = Instant.now().plusSeconds(7200)
                )
                val altinnToken1 = createMockToken(
                    ident = "123456789",
                    scope = targetScope1,
                    expiresAt = Instant.now().plusSeconds(7200)
                )

                val mockEngine = MockEngine { request ->
                    when (request.url.fullPath) {
                        "/authentication/api/v1/exchange/maskinporten" -> {
                            respond(
                                content = "\"${altinnToken1}\"",
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                            )
                        }

                        else -> error("Unhandled ${request.url.fullPath}")
                    }
                }

                val httpClient = HttpClient(mockEngine) {
                    install(ContentNegotiation) {
                        jackson()
                    }
                }

                coEvery { mockTexasClient.systemToken("maskinporten", targetScope1) } returns TexasResponse(
                    accessToken = maskinportenToken,
                    expiresIn = 3600,
                    tokenType = "Bearer"
                )

                coEvery { mockTexasClient.systemToken("maskinporten", targetScope2) } returns TexasResponse(
                    accessToken = maskinportenToken,
                    expiresIn = 3600,
                    tokenType = "Bearer"
                )

                val tokenProvider = AltinnTokenProvider(
                    texasHttpClient = mockTexasClient,
                    httpClient = httpClient,
                    altinnBaseUrl = altinnBaseUrl
                )

                tokenProvider.token(targetScope1)
                tokenProvider.token(targetScope2)

                coVerify(exactly = 1) { mockTexasClient.systemToken("maskinporten", targetScope1) }
                coVerify(exactly = 1) { mockTexasClient.systemToken("maskinporten", targetScope2) }
            }
        }

        describe("toAltinnToken") {
            it("should correctly parse Altinn token with scope claim") {
                val targetScope = "digdir:dialogporten.serviceprovider"
                val altinnTokenString = createMockToken(
                    ident = "123456789",
                    scope = targetScope,
                    expiresAt = Instant.now().plusSeconds(7200)
                )

                coEvery { mockTexasClient.systemToken("maskinporten", targetScope) } returns TexasResponse(
                    accessToken = createMockToken(
                        ident = "123456789",
                        scope = targetScope,
                        expiresAt = Instant.now().plusSeconds(7200)
                    ),
                    expiresIn = 3600,
                    tokenType = "Bearer"
                )

                val mockEngineWithResponse = MockEngine { request ->
                    when (request.url.fullPath) {
                        "/authentication/api/v1/exchange/maskinporten" -> {
                            respond(
                                content = "\"$altinnTokenString\"",
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                            )
                        }

                        else -> error("Unhandled ${request.url.fullPath}")
                    }
                }

                val httpClientWithResponse = HttpClient(mockEngineWithResponse) {
                    install(ContentNegotiation) {
                        jackson()
                    }
                }

                val tokenProviderWithResponse = AltinnTokenProvider(
                    texasHttpClient = mockTexasClient,
                    httpClient = httpClientWithResponse,
                    altinnBaseUrl = altinnBaseUrl
                )

                val result = tokenProviderWithResponse.token(targetScope)

                result.scope shouldBe targetScope
                result.accessToken shouldBe altinnTokenString
                result.altinnExpiryTime.inWholeSeconds shouldBe JWT.decode(altinnTokenString).expiresAt.time / 1000
            }
        }
    }
})
