package no.nav.syfo.dialogporten.client

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.inspectors.forAtLeastOne
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.ResponseException
import io.ktor.client.statement.HttpResponsePipeline
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.mockk.coEvery
import io.mockk.mockk
import no.nav.esyfo.observability.causeChain
import no.nav.syfo.altinn.dialogporten.client.DialogPatchResult
import no.nav.syfo.altinn.dialogporten.client.HttpDialogportenClient
import no.nav.syfo.altinn.dialogporten.domain.Content
import no.nav.syfo.altinn.dialogporten.domain.ContentValue
import no.nav.syfo.altinn.dialogporten.domain.ContentValueItem
import no.nav.syfo.altinn.dialogporten.domain.Dialog
import no.nav.syfo.altinn.dialogporten.domain.DialogStatus
import no.nav.syfo.altinn.dialogporten.domain.ExtendedDialog
import no.nav.syfo.integration.respondJson
import no.nav.syfo.integration.respondWithSystemToken
import no.nav.syfo.integration.upstreamHttpClient
import no.nav.syfo.logging.applicationEvent
import no.nav.syfo.logging.captureProductionLogs
import no.nav.syfo.logging.logEvent
import no.nav.syfo.logging.withProductionLogger
import no.nav.syfo.platform.upstream.UpstreamError
import no.nav.syfo.platform.upstream.UpstreamFailure
import no.nav.syfo.platform.upstream.UpstreamFailureStage
import no.nav.syfo.platform.upstream.UpstreamName
import no.nav.syfo.platform.upstream.UpstreamResult
import no.nav.syfo.texas.AltinnTokenProvider
import no.nav.syfo.util.JSON_PATCH_CONTENT_TYPE
import org.slf4j.event.Level
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration

class DialogportenClientTest :
    FunSpec({
        val mapper = jacksonObjectMapper()
        val id = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val revision = UUID.fromString("00000000-0000-0000-0000-000000000002")
        val dialog = Dialog(
            party = "urn:altinn:organization:identifier-no:910000001",
            serviceResource = "service:resource",
            externalReference = id.toString(),
            content = Content(
                title = ContentValue(value = listOf(ContentValueItem("Title"))),
                summary = ContentValue(value = listOf(ContentValueItem("Summary"))),
            ),
        )
        val extended = ExtendedDialog(revision, id, dialog.party, dialog.serviceResource, dialog.externalReference, DialogStatus.RequiresAttention, dialog.content)
        val patch = HttpDialogportenClient.DialogportenPatch(
            HttpDialogportenClient.DialogportenPatch.OPERATION.REPLACE,
            HttpDialogportenClient.DialogportenPatch.PATH.STATUS,
            DialogStatus.Completed.name,
        )
        val provider = mockk<AltinnTokenProvider>()
        beforeTest {
            coEvery { provider.token(AltinnTokenProvider.DIALOGPORTEN_TARGET_SCOPE) } returns
                UpstreamResult.Success(AltinnTokenProvider.AltinnToken("synthetic-token", Duration.ZERO))
        }

        val operations: Map<String, suspend (HttpDialogportenClient) -> UpstreamResult<*>> = mapOf(
            "POST" to { it.createDialog(dialog) },
            "GET" to { it.getDialogById(id) },
            "PATCH" to { it.patchDialog(id, revision, patch) },
        )

        operations.forEach { (operation, invoke) ->
            test("$operation sends the expected method headers and body and returns success") {
                var attempts = 0
                upstreamHttpClient(respondWithSystemToken) { request ->
                    attempts++
                    request.method shouldBe HttpMethod.parse(operation)
                    request.url.encodedPath shouldBe "/dialogporten/api/v1/serviceowner/dialogs" + if (operation == "POST") "" else "/$id"
                    request.headers[HttpHeaders.Authorization] shouldBe "Bearer synthetic-token"
                    request.headers[HttpHeaders.Accept] shouldBe ContentType.Application.Json.toString()
                    when (operation) {
                        "POST" -> {
                            request.body.contentType shouldBe ContentType.Application.Json
                            mapper.readValue<Dialog>(request.body.toByteArray()) shouldBe dialog
                            respondJson("\"$id\"", HttpStatusCode.Created)
                        }
                        "GET" -> {
                            request.headers[HttpHeaders.ContentType] shouldBe ContentType.Application.Json.toString()
                            respondJson(mapper.writeValueAsString(extended))
                        }
                        else -> {
                            request.headers[HttpHeaders.IfMatch] shouldBe revision.toString()
                            request.body.contentType shouldBe JSON_PATCH_CONTENT_TYPE
                            mapper.readTree(request.body.toByteArray()) shouldBe mapper.readTree("""[{"operation":"Replace","path":"/status","value":"Completed"}]""")
                            respondJson("", HttpStatusCode.NoContent)
                        }
                    }
                }.use { http ->
                    invoke(HttpDialogportenClient("https://dialogporten.test", http, provider)) shouldBe when (operation) {
                        "POST" -> UpstreamResult.Success(id)
                        "GET" -> UpstreamResult.Success(extended)
                        else -> UpstreamResult.Success(DialogPatchResult.Patched)
                    }
                }
                attempts shouldBe 1
            }

            listOf("texas", "altinn-authentication").forEach { upstream ->
                test("$operation forwards $upstream token failure without an HTTP request") {
                    val failure = UpstreamResult.Failure(UpstreamFailure(UpstreamName(upstream), UpstreamFailureStage.TOKEN_EXCHANGE, 401, errors = listOf(UpstreamError("invalid_token"))))
                    coEvery { provider.token(any()) } returns failure
                    upstreamHttpClient(respondWithSystemToken) { error("Unexpected upstream request") }.use { http ->
                        invoke(HttpDialogportenClient("https://dialogporten.test", http, provider)) shouldBeSameInstanceAs failure
                    }
                }
            }

            listOf(400, 401, 403, 404, 409, 410, 412, 500, 503).forEach { status ->
                test("$operation maps HTTP $status after the configured attempts") {
                    var attempts = 0
                    upstreamHttpClient(respondWithSystemToken) {
                        attempts++
                        respondJson("private-body-canary", HttpStatusCode.fromValue(status))
                    }.use { http ->
                        val result = invoke(HttpDialogportenClient("https://dialogporten.test", http, provider))
                        if (operation == "PATCH" && status == 412) {
                            result shouldBe UpstreamResult.Success(DialogPatchResult.RevisionConflict)
                        } else {
                            val failure = result.shouldBeInstanceOf<UpstreamResult.Failure>().failure
                            failure.upstream shouldBe UpstreamName("dialogporten")
                            failure.stage shouldBe UpstreamFailureStage.RESPONSE
                            failure.status shouldBe status
                            failure.cause.shouldBeInstanceOf<ResponseException>()
                        }
                    }
                    attempts shouldBe if (status >= 500) 3 else 1
                }
            }

            listOf(SocketTimeoutException("private-request-canary"), IOException("private-request-canary")).forEach { original ->
                test("$operation maps ${original.javaClass.simpleName} to REQUEST after three attempts") {
                    var attempts = 0
                    upstreamHttpClient(respondWithSystemToken) {
                        attempts++
                        throw original
                    }.use { http ->
                        val failure = invoke(HttpDialogportenClient("https://dialogporten.test", http, provider))
                            .shouldBeInstanceOf<UpstreamResult.Failure>().failure
                        failure.upstream shouldBe UpstreamName("dialogporten")
                        failure.stage shouldBe UpstreamFailureStage.REQUEST
                        failure.status shouldBe null
                        requireNotNull(failure.cause).causeChain().forAtLeastOne { it shouldBeSameInstanceAs original }
                    }
                    attempts shouldBe 3
                }
            }

            test("$operation propagates token cancellation") {
                val cancelled = CancellationException("cancelled")
                coEvery { provider.token(any()) } throws cancelled
                upstreamHttpClient(respondWithSystemToken) { error("Unexpected upstream request") }.use { http ->
                    shouldThrow<CancellationException> {
                        invoke(HttpDialogportenClient("https://dialogporten.test", http, provider))
                    } shouldBeSameInstanceAs cancelled
                }
            }

            test("$operation propagates request cancellation") {
                val cancelled = CancellationException("cancelled")
                upstreamHttpClient(respondWithSystemToken) { throw cancelled }.use { http ->
                    val thrown = shouldThrow<CancellationException> {
                        invoke(HttpDialogportenClient("https://dialogporten.test", http, provider))
                    }
                    thrown.causeChain().forAtLeastOne { it shouldBeSameInstanceAs cancelled }
                }
            }
        }

        listOf("POST", "GET").forEach { operation ->
            test("$operation maps an invalid successful body to RESPONSE with status") {
                upstreamHttpClient(respondWithSystemToken) { respondJson("\"invalid-body-canary\"", HttpStatusCode.Created) }.use { http ->
                    val failure = operations.getValue(operation)(HttpDialogportenClient("https://dialogporten.test", http, provider))
                        .shouldBeInstanceOf<UpstreamResult.Failure>().failure
                    failure.upstream shouldBe UpstreamName("dialogporten")
                    failure.stage shouldBe UpstreamFailureStage.RESPONSE
                    failure.status shouldBe 201
                }
            }

            test("$operation propagates cancellation while decoding") {
                val cancelled = CancellationException("cancelled")
                var decoding = false
                upstreamHttpClient(respondWithSystemToken) { respondJson(if (operation == "POST") "\"$id\"" else mapper.writeValueAsString(extended)) }.use { http ->
                    http.responsePipeline.intercept(HttpResponsePipeline.Receive) {
                        if (subject.expectedType.type == if (operation == "POST") String::class else ExtendedDialog::class) {
                            decoding = true
                            throw cancelled
                        }
                    }
                    shouldThrow<CancellationException> {
                        operations.getValue(operation)(HttpDialogportenClient("https://dialogporten.test", http, provider))
                    } shouldBeSameInstanceAs cancelled
                }
                decoding shouldBe true
            }
        }
        test("diagnostics log status and upstream metadata without token response body or exception messages") {
            withProductionLogger("dialogporten-client-diagnostics-test") { logger ->
                val event = applicationEvent<Unit>("dialogporten_diagnostic_test_failed", Level.ERROR, "Dialogporten test failure")
                listOf(400, 503, 200).forEach { status ->
                    upstreamHttpClient(respondWithSystemToken) { respondJson("\"private-body-canary\"", HttpStatusCode.fromValue(status)) }.use { http ->
                        val failure = HttpDialogportenClient("https://dialogporten.test", http, provider).getDialogById(id)
                            .shouldBeInstanceOf<UpstreamResult.Failure>().failure
                        logger.captureProductionLogs().use { capture ->
                            logger.logEvent(event, Unit, upstreamFailure = failure)
                            val output = capture.records.single()
                            val record = mapper.readTree(output)
                            record["upstream"].asText() shouldBe "dialogporten"
                            record["failure_stage"].asText() shouldBe "response"
                            record["upstream_status"].asInt() shouldBe status
                            output shouldNotContain "private-body-canary"
                            output shouldNotContain "synthetic-token"
                            output shouldNotContain dialog.party
                        }
                    }
                }
            }
        }
    })
