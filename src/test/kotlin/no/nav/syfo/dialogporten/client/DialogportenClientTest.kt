package no.nav.syfo.dialogporten.client

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import createMockToken
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
import no.nav.syfo.altinn.dialogporten.client.DialogportenClient
import no.nav.syfo.util.JSON_PATCH_CONTENT_TYPE
import no.nav.syfo.altinn.dialogporten.domain.DialogStatus
import no.nav.syfo.texas.client.TexasHttpClient
import no.nav.syfo.texas.client.TexasResponse
import no.nav.syfo.util.httpClientDefault
import java.util.UUID


class DialogportenClientTest : DescribeSpec({
    describe("Test PATCH status in dialogporten") {
        context("Should follow RFC specs when sending the PATCH request") {
            val httpClientWithAssertions = httpClientDefault(HttpClient(engine = MockEngine { request ->
                when (request.method) {
                    HttpMethod.Patch -> {
                        // Noe quirk med MockEngine gjør at headeren tydeligvis havner her
                        request.body.contentType shouldBe JSON_PATCH_CONTENT_TYPE
                        val patchValues: List<DialogportenClient.DialogportenPatch> =
                            jacksonObjectMapper().readValue(request.body.toByteArray())

                        patchValues.first().path shouldBe "/status"
                        patchValues.first().value shouldBe DialogStatus.Completed.name
                        patchValues.first().op shouldBe DialogportenClient.DialogportenPatch.OPERATION.REPLACE
                        patchValues.first().op.jsonValue shouldBe "Replace"

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
            }))
            val mockTexasHttpClient = mockk<TexasHttpClient>(relaxed = true)
            val dialogportenClient = spyk(
                DialogportenClient(
                    baseUrl = "http://localhost:8080",
                    httpClient = httpClientWithAssertions,
                    texasHttpClient = mockTexasHttpClient
                )
            )
            it("Should send a patch to Dialogporten with correct headers and body") {
                val dialogId = UUID.randomUUID()
                coEvery {
                    mockTexasHttpClient.systemToken(any(), any())
                } returns TexasResponse(
                    "token", 111, "tokenType"
                )
                dialogportenClient.updateDialogStatus(
                    dialogId = dialogId,
                    revisionNumber = UUID.randomUUID(),
                    dialogStatus = DialogStatus.Completed,
                )

                coVerify {
                    dialogportenClient.updateDialogStatus(
                        eq(dialogId),
                        any(),
                        DialogStatus.Completed
                    )
                }
            }
        }
    }
})
