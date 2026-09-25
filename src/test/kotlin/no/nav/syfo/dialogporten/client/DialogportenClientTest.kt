package no.nav.syfo.dialogporten.client

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import createMockToken
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.fullPath
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.spyk
import no.nav.syfo.altinn.dialogporten.client.DialogportenClientException
import no.nav.syfo.altinn.dialogporten.client.HttpDialogportenClient
import no.nav.syfo.altinn.dialogporten.domain.DialogStatus
import no.nav.syfo.logging.failureDiagnostics
import no.nav.syfo.texas.AltinnTokenProvider
import no.nav.syfo.util.JSON_PATCH_CONTENT_TYPE
import no.nav.syfo.util.httpClientDefault
import java.util.*
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration

class DialogportenClientTest :
    DescribeSpec({
        describe("failure ownership") {
            it("preserves the HTTP cause for the service that handles the failed operation") {
                val provider = mockk<AltinnTokenProvider>()
                coEvery { provider.token(any()) } returns AltinnTokenProvider.AltinnToken("secret-token", Duration.ZERO, "scope")
                val client = HttpDialogportenClient(
                    "https://dialogporten.test",
                    httpClientDefault(HttpClient(MockEngine { respond("private-response-body", HttpStatusCode.ServiceUnavailable) })),
                    provider,
                )
                val failure = shouldThrow<DialogportenClientException> {
                    client.getDialogById(UUID.randomUUID())
                }
                failure.failureDiagnostics().upstreamStatus shouldBe 503
                failure.failureDiagnostics().failureKind shouldBe "http"
                failure.failureDiagnostics().causeType shouldBe "ServerResponseException"
                failure.message shouldBe "Error in request to Dialogporten"
            }

            it("propagates cancellation without converting it into a client failure") {
                val cancelled = CancellationException("cancelled")
                val provider = mockk<AltinnTokenProvider>()
                coEvery { provider.token(any()) } throws cancelled
                val client = HttpDialogportenClient(
                    "https://dialogporten.test",
                    HttpClient(MockEngine { error("No upstream request should be made") }),
                    provider,
                )
                shouldThrow<CancellationException> { client.getDialogById(UUID.randomUUID()) } shouldBe cancelled
            }
        }

        describe("Test PATCH status in dialogporten") {
            context("Should follow RFC specs when sending the PATCH request") {
                val httpClientWithAssertions = httpClientDefault(
                    HttpClient(
                        engine = MockEngine { request ->
                            when (request.method) {
                                HttpMethod.Patch -> {
                                    // Noe quirk med MockEngine gjør at headeren tydeligvis havner her
                                    request.body.contentType shouldBe JSON_PATCH_CONTENT_TYPE
                                    val patchValues: List<HttpDialogportenClient.DialogportenPatch> =
                                        jacksonObjectMapper().readValue(request.body.toByteArray())

                                    patchValues.first().path shouldBe HttpDialogportenClient.DialogportenPatch.PATH.STATUS
                                    patchValues.first().value shouldBe DialogStatus.Completed.name
                                    patchValues.first().operation shouldBe HttpDialogportenClient.DialogportenPatch.OPERATION.REPLACE
                                    patchValues.first().operation.jsonValue shouldBe "Replace"

                                    respond(
                                        content = "",
                                        status = HttpStatusCode.NoContent,
                                    )
                                }

                                HttpMethod.Get if request.url.fullPath.contains("exchange") -> {
                                    respond(
                                        content = createMockToken("12345678901")
                                    )
                                }

                                else -> error("Unhandled request ${request.url.fullPath}")
                            }
                        }
                    )
                )
                val mockAltinnTokenProvider = mockk<AltinnTokenProvider>(relaxed = true)
                val dialogportenClient = spyk(
                    HttpDialogportenClient(
                        baseUrl = "http://localhost:8080",
                        httpClient = httpClientWithAssertions,
                        altinnTokenProvider = mockAltinnTokenProvider,
                    )
                )
                it("Should send a patch to Dialogporten with correct headers and body") {
                    val dialogId = UUID.randomUUID()
                    coEvery {
                        mockAltinnTokenProvider.token(AltinnTokenProvider.DIALOGPORTEN_TARGET_SCOPE)
                    } returns AltinnTokenProvider.AltinnToken(
                        "token",
                        Duration.ZERO,
                        "scope"
                    )

                    val patch = HttpDialogportenClient.DialogportenPatch(
                        path = HttpDialogportenClient.DialogportenPatch.PATH.STATUS,
                        operation = HttpDialogportenClient.DialogportenPatch.OPERATION.REPLACE,
                        value = DialogStatus.Completed.name,
                    )
                    dialogportenClient.patchDialog(
                        dialogId = dialogId,
                        revisionNumber = UUID.randomUUID(),
                        patch = patch,
                    )

                    coVerify {
                        dialogportenClient.patchDialog(
                            dialogId = dialogId,
                            revisionNumber = any<UUID>(),
                            patch = patch
                        )
                    }
                }
            }
        }
    })
