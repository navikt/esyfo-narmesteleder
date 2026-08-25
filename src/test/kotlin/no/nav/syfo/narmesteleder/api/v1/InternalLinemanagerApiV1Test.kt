package no.nav.syfo.narmesteleder.api.v1

import DefaultOrganization
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.JsonNodeType
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import createMockToken
import defaultMocks
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.shouldBeExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.mockk.Called
import io.mockk.coEvery
import io.mockk.coVerify
import no.nav.syfo.API_V1_PATH
import no.nav.syfo.application.api.ApiError
import no.nav.syfo.application.api.ErrorType
import no.nav.syfo.application.metric.METRICS_REGISTRY
import no.nav.syfo.narmesteleder.api.internal.INTERNAL_API_V1_PATH
import no.nav.syfo.narmesteleder.api.internal.v1.EMPLOYEE_LINEMANAGER_API_PATH
import no.nav.syfo.narmesteleder.api.internal.v1.EMPLOYEE_LINEMANAGER_TOTAL
import no.nav.syfo.narmesteleder.domain.EmployeeLinemanagerRead
import no.nav.syfo.narmesteleder.domain.LinemanagerReadCollection
import no.nav.syfo.narmesteleder.domain.LinemanagerRequirementCollection
import no.nav.syfo.narmesteleder.domain.LinemanagerSearchCursor
import no.nav.syfo.narmesteleder.domain.LinemanagerSearchRequest
import no.nav.syfo.narmesteleder.domain.LinemanagerStatistics
import no.nav.syfo.narmesteleder.domain.Name
import no.nav.syfo.narmesteleder.domain.OrganizationNumber
import no.nav.syfo.narmesteleder.domain.PersonalIdentificationNumber
import no.nav.syfo.texas.MASKINPORTEN_NL_SCOPE
import java.time.Instant
import java.util.UUID

private val responseObjectMapper = jacksonObjectMapper()

private fun String.toJsonNode(): JsonNode = responseObjectMapper.readTree(this)

private fun JsonNode.scalarValues(): List<String> = when {
    isValueNode -> listOf(asText())
    isObject -> fieldNames().asSequence().toList() + flatMap { it.scalarValues() }
    else -> flatMap { it.scalarValues() }
}

class InternalLinemanagerApiV1Test :
    LinemanagerApiV1TestBase({
        it("round-trips v2 pageTokens with nullable, empty, Unicode, and colon-delimited names") {
            listOf(
                LinemanagerSearchCursor(
                    firstName = "ø:ystein",
                    lastName = "",
                    id = 42,
                ),
                LinemanagerSearchCursor(
                    firstName = null,
                    lastName = null,
                    id = 1,
                ),
            ).forEach { cursor ->
                cursor.toOpaqueCursor().toLinemanagerSearchCursor() shouldBe cursor
            }
        }

        describe("GET /internal/api/v1/linemanager/statistics") {
            it("returns statistics for an authorized organization") {
                withTestApplication {
                    val expectedStatistics = LinemanagerStatistics(
                        employeesOnSickLeaveWithoutLinemanager = 1,
                        employeesOnSickLeaveWithLinemanager = 2,
                        employeesNotOnSickLeaveWithLinemanager = 3,
                    )
                    coEvery {
                        linemanagerStatisticsRepository.getStatistics(narmesteLederRelasjon.orgNumber)
                    } returns expectedStatistics
                    texasHttpClientMock.defaultMocks(
                        systemBrukerOrganisasjon = DefaultOrganization.copy(ID = "0192:${narmesteLederRelasjon.orgNumber.value}"),
                        scope = MASKINPORTEN_NL_SCOPE,
                    )

                    val response = client.get(
                        "$INTERNAL_API_V1_PATH$LINEMANAGER_STATISTICS_API_PATH?orgNumber=${narmesteLederRelasjon.orgNumber.value}",
                    ) {
                        bearerAuth(createMockToken(narmesteLederRelasjon.orgNumber.value))
                    }

                    response.status shouldBe HttpStatusCode.OK
                    response.body<LinemanagerStatistics>() shouldBe expectedStatistics
                    coVerify(exactly = 1) {
                        linemanagerStatisticsRepository.getStatistics(narmesteLederRelasjon.orgNumber)
                    }
                }
            }
        }

        describe("GET /internal/api/v1/employee/linemanager") {
            // Texas active=true validates both audience and expiry before the application handles the request.
            fun employeeLinemanager(
                id: UUID,
                orgNumber: OrganizationNumber,
                emailAddresses: List<String> = listOf("leder@example.com"),
            ) = EmployeeLinemanagerRead(
                id = id,
                orgNumber = orgNumber,
                activeFrom = Instant.parse("2026-01-01T00:00:00Z"),
                name = Name(
                    firstName = "Kari",
                    middleName = null,
                    lastName = "Nordmann",
                ),
                emailAddresses = emailAddresses,
                mobile = "99999999",
            )

            it("returns active linemanagers without exposing employee details") {
                withTestApplication {
                    val callerPid = "11223344556"
                    coEvery { employeeLinemanagerRepository.findActiveForEmployee(any()) } returns listOf(
                        employeeLinemanager(UUID(0, 1), OrganizationNumber("123456789")),
                        employeeLinemanager(UUID(0, 2), OrganizationNumber("987654321")),
                    )
                    texasHttpClientMock.defaultMocks(acr = "Level4", pid = callerPid)

                    val response = client.get("$INTERNAL_API_V1_PATH$EMPLOYEE_LINEMANAGER_API_PATH") {
                        bearerAuth(createMockToken(callerPid, issuer = tokenXIssuer))
                    }
                    val responseBody = response.bodyAsText()
                    val responseJson = responseBody.toJsonNode()
                    val linemanagers = responseJson.path("linemanagers")

                    response.status shouldBe HttpStatusCode.OK
                    responseJson.findValues("meta").shouldBeEmpty()
                    responseJson.findValues("manager").shouldBeEmpty()
                    responseJson.findValues("nationalIdentificationNumber").shouldBeEmpty()
                    responseJson.scalarValues().filter { it.contains(callerPid) }.shouldBeEmpty()
                    // Raw text check guards free-text fields where a PID can be embedded in a longer string.
                    responseBody shouldNotContain callerPid
                    linemanagers.nodeType shouldBe JsonNodeType.ARRAY
                    linemanagers.toList().shouldHaveSize(2)
                    linemanagers[0].path("orgNumber").nodeType shouldBe JsonNodeType.STRING
                    linemanagers[0].path("orgNumber").textValue() shouldBe "123456789"
                    linemanagers.forEach { linemanager ->
                        linemanager.path("name").nodeType shouldBe JsonNodeType.OBJECT
                        linemanager.path("emailAddresses").nodeType shouldBe JsonNodeType.ARRAY
                        linemanager.path("mobile").nodeType shouldBe JsonNodeType.STRING
                        linemanager.fieldNames().asSequence().toList() shouldContainExactlyInAnyOrder listOf(
                            "id",
                            "orgNumber",
                            "activeFrom",
                            "name",
                            "emailAddresses",
                            "mobile",
                        )
                    }
                }
            }

            it("serializes one email address as an array") {
                withTestApplication {
                    val callerPid = "11223344556"
                    coEvery { employeeLinemanagerRepository.findActiveForEmployee(any()) } returns listOf(
                        employeeLinemanager(
                            id = UUID(0, 1),
                            orgNumber = OrganizationNumber("123456789"),
                            emailAddresses = listOf("single@example.com"),
                        ),
                    )
                    texasHttpClientMock.defaultMocks(acr = "Level4", pid = callerPid)

                    val response = client.get("$INTERNAL_API_V1_PATH$EMPLOYEE_LINEMANAGER_API_PATH") {
                        bearerAuth(createMockToken(callerPid, issuer = tokenXIssuer))
                    }
                    val emailAddresses = response.bodyAsText()
                        .toJsonNode()
                        .path("linemanagers")
                        .path(0)
                        .path("emailAddresses")

                    response.status shouldBe HttpStatusCode.OK
                    emailAddresses.nodeType shouldBe JsonNodeType.ARRAY
                    emailAddresses.toList().shouldHaveSize(1)
                    emailAddresses[0].nodeType shouldBe JsonNodeType.STRING
                    emailAddresses[0].textValue() shouldBe "single@example.com"
                }
            }

            it("uses the authenticated employee pid without an organization filter") {
                withTestApplication {
                    val callerPid = "11223344556"
                    coEvery { employeeLinemanagerRepository.findActiveForEmployee(any()) } returns emptyList()
                    texasHttpClientMock.defaultMocks(acr = "Level4", pid = callerPid)

                    val response = client.get("$INTERNAL_API_V1_PATH$EMPLOYEE_LINEMANAGER_API_PATH") {
                        bearerAuth(createMockToken(callerPid, issuer = tokenXIssuer))
                    }

                    response.status shouldBe HttpStatusCode.OK
                    coVerify(exactly = 1) {
                        employeeLinemanagerRepository.findActiveForEmployee(
                            match {
                                it.employeeNationalIdentificationNumber.value == callerPid && it.orgNumber == null
                            },
                        )
                    }
                }
            }

            it("uses orgNumber as an optional filter") {
                withTestApplication {
                    val callerPid = "11223344556"
                    val orgNumber = OrganizationNumber("123456789")
                    coEvery { employeeLinemanagerRepository.findActiveForEmployee(any()) } returns emptyList()
                    texasHttpClientMock.defaultMocks(acr = "Level4", pid = callerPid)

                    val response = client.get(
                        "$INTERNAL_API_V1_PATH$EMPLOYEE_LINEMANAGER_API_PATH?orgNumber=${orgNumber.value}",
                    ) {
                        bearerAuth(createMockToken(callerPid, issuer = tokenXIssuer))
                    }

                    response.status shouldBe HttpStatusCode.OK
                    coVerify(exactly = 1) {
                        employeeLinemanagerRepository.findActiveForEmployee(
                            match {
                                it.employeeNationalIdentificationNumber.value == callerPid && it.orgNumber == orgNumber
                            },
                        )
                    }
                }
            }

            it("returns 400 for an orgNumber with invalid length") {
                withTestApplication {
                    val callerPid = "11223344556"
                    texasHttpClientMock.defaultMocks(acr = "Level4", pid = callerPid)

                    val response = client.get(
                        "$INTERNAL_API_V1_PATH$EMPLOYEE_LINEMANAGER_API_PATH?orgNumber=12345678",
                    ) {
                        bearerAuth(createMockToken(callerPid, issuer = tokenXIssuer))
                    }

                    response.status shouldBe HttpStatusCode.BadRequest
                    response.body<ApiError>().type shouldBe ErrorType.INVALID_FORMAT
                    coVerify(exactly = 0) { employeeLinemanagerRepository.findActiveForEmployee(any()) }
                }
            }

            it("returns 400 for an empty orgNumber") {
                withTestApplication {
                    val callerPid = "11223344556"
                    texasHttpClientMock.defaultMocks(acr = "Level4", pid = callerPid)

                    val response = client.get("$INTERNAL_API_V1_PATH$EMPLOYEE_LINEMANAGER_API_PATH?orgNumber=") {
                        bearerAuth(createMockToken(callerPid, issuer = tokenXIssuer))
                    }

                    response.status shouldBe HttpStatusCode.BadRequest
                    response.body<ApiError>().type shouldBe ErrorType.INVALID_FORMAT
                    coVerify(exactly = 0) { employeeLinemanagerRepository.findActiveForEmployee(any()) }
                }
            }

            it("returns 400 for an orgNumber without a value") {
                withTestApplication {
                    val callerPid = "11223344556"
                    texasHttpClientMock.defaultMocks(acr = "Level4", pid = callerPid)

                    val response = client.get("$INTERNAL_API_V1_PATH$EMPLOYEE_LINEMANAGER_API_PATH?orgNumber") {
                        bearerAuth(createMockToken(callerPid, issuer = tokenXIssuer))
                    }

                    response.status shouldBe HttpStatusCode.BadRequest
                    response.body<ApiError>().type shouldBe ErrorType.BAD_REQUEST
                    coVerify(exactly = 0) { employeeLinemanagerRepository.findActiveForEmployee(any()) }
                }
            }

            it("returns 400 for duplicate orgNumber parameters") {
                withTestApplication {
                    val callerPid = "11223344556"
                    texasHttpClientMock.defaultMocks(acr = "Level4", pid = callerPid)

                    val response = client.get(
                        "$INTERNAL_API_V1_PATH$EMPLOYEE_LINEMANAGER_API_PATH?orgNumber=A&orgNumber=B",
                    ) {
                        bearerAuth(createMockToken(callerPid, issuer = tokenXIssuer))
                    }

                    response.status shouldBe HttpStatusCode.BadRequest
                    response.body<ApiError>().type shouldBe ErrorType.BAD_REQUEST
                    coVerify(exactly = 0) { employeeLinemanagerRepository.findActiveForEmployee(any()) }
                }
            }

            it("returns 401 without an authorization header") {
                withTestApplication {
                    val response = client.get("$INTERNAL_API_V1_PATH$EMPLOYEE_LINEMANAGER_API_PATH")

                    response.status shouldBe HttpStatusCode.Unauthorized
                    coVerify(exactly = 0) { employeeLinemanagerRepository.findActiveForEmployee(any()) }
                }
            }

            it("returns 401 for an inactive TokenX token") {
                withTestApplication {
                    val callerPid = "11223344556"
                    coEvery { texasHttpClientMock.introspectToken("tokenx", any()) } returns
                        no.nav.syfo.texas.client.TexasIntrospectionResponse(active = false)

                    val response = client.get("$INTERNAL_API_V1_PATH$EMPLOYEE_LINEMANAGER_API_PATH") {
                        bearerAuth(createMockToken(callerPid, issuer = tokenXIssuer))
                    }

                    response.status shouldBe HttpStatusCode.Unauthorized
                    coVerify(exactly = 0) { employeeLinemanagerRepository.findActiveForEmployee(any()) }
                }
            }

            it("returns 401 without leaking an invalid pid") {
                withTestApplication {
                    val invalidPid = "abc"
                    texasHttpClientMock.defaultMocks(acr = "Level4", pid = invalidPid)

                    val response = client.get("$INTERNAL_API_V1_PATH$EMPLOYEE_LINEMANAGER_API_PATH") {
                        bearerAuth(createMockToken("11223344556", issuer = tokenXIssuer))
                    }
                    val body = response.bodyAsText()

                    response.status shouldBe HttpStatusCode.Unauthorized
                    // ApiError is checked as raw text to guard every response field against PID leakage.
                    body shouldNotContain invalidPid
                    coVerify(exactly = 0) { employeeLinemanagerRepository.findActiveForEmployee(any()) }
                }
            }

            it("returns 401 when the TokenX token has no pid") {
                withTestApplication {
                    texasHttpClientMock.defaultMocks(acr = "Level4", pid = null)

                    val response = client.get("$INTERNAL_API_V1_PATH$EMPLOYEE_LINEMANAGER_API_PATH") {
                        bearerAuth(createMockToken("11223344556", issuer = tokenXIssuer))
                    }

                    response.status shouldBe HttpStatusCode.Unauthorized
                    coVerify(exactly = 0) { employeeLinemanagerRepository.findActiveForEmployee(any()) }
                }
            }

            it("returns 403 when TokenX authentication is below Level4") {
                withTestApplication {
                    val callerPid = "11223344556"
                    texasHttpClientMock.defaultMocks(acr = "Level3", pid = callerPid)

                    val response = client.get("$INTERNAL_API_V1_PATH$EMPLOYEE_LINEMANAGER_API_PATH") {
                        bearerAuth(createMockToken(callerPid, issuer = tokenXIssuer))
                    }

                    response.status shouldBe HttpStatusCode.Forbidden
                    coVerify(exactly = 0) { employeeLinemanagerRepository.findActiveForEmployee(any()) }
                }
            }

            it("returns 401 for a Maskinporten token") {
                withTestApplication {
                    texasHttpClientMock.defaultMocks(
                        systemBrukerOrganisasjon = DefaultOrganization,
                        scope = MASKINPORTEN_NL_SCOPE,
                    )

                    val response = client.get("$INTERNAL_API_V1_PATH$EMPLOYEE_LINEMANAGER_API_PATH") {
                        bearerAuth(createMockToken(DefaultOrganization.ID))
                    }

                    response.status shouldBe HttpStatusCode.Unauthorized
                    coVerify(exactly = 0) { employeeLinemanagerRepository.findActiveForEmployee(any()) }
                }
            }

            it("returns 401 for an Azure AD token") {
                withTestApplication {
                    val response = client.get("$INTERNAL_API_V1_PATH$EMPLOYEE_LINEMANAGER_API_PATH") {
                        bearerAuth(createMockToken("ignored", issuer = "https://login.microsoftonline.com/tenant/v2.0"))
                    }

                    response.status shouldBe HttpStatusCode.Unauthorized
                    coVerify(exactly = 0) { employeeLinemanagerRepository.findActiveForEmployee(any()) }
                }
            }

            it("returns an empty collection when the organization has no matches") {
                withTestApplication {
                    val callerPid = "11223344556"
                    coEvery { employeeLinemanagerRepository.findActiveForEmployee(any()) } returns emptyList()
                    texasHttpClientMock.defaultMocks(acr = "Level4", pid = callerPid)

                    val response = client.get(
                        "$INTERNAL_API_V1_PATH$EMPLOYEE_LINEMANAGER_API_PATH?orgNumber=123456789",
                    ) {
                        bearerAuth(createMockToken(callerPid, issuer = tokenXIssuer))
                    }

                    response.status shouldBe HttpStatusCode.OK
                    val linemanagers = response.bodyAsText().toJsonNode().path("linemanagers")
                    linemanagers.nodeType shouldBe JsonNodeType.ARRAY
                    linemanagers.toList().shouldBeEmpty()
                }
            }

            it("does not use Altinn access for employee linemanager lookups") {
                withTestApplication {
                    val callerPid = "11223344556"
                    coEvery { employeeLinemanagerRepository.findActiveForEmployee(any()) } returns emptyList()
                    texasHttpClientMock.defaultMocks(acr = "Level4", pid = callerPid)

                    val response = client.get("$INTERNAL_API_V1_PATH$EMPLOYEE_LINEMANAGER_API_PATH") {
                        bearerAuth(createMockToken(callerPid, issuer = tokenXIssuer))
                    }

                    response.status shouldBe HttpStatusCode.OK
                    coVerify { altinnAccessServiceSpy wasNot Called }
                }
            }

            it("is not available through the external API") {
                withTestApplication {
                    val response = client.get("$API_V1_PATH$EMPLOYEE_LINEMANAGER_API_PATH")

                    response.status shouldBe HttpStatusCode.NotFound
                    coVerify(exactly = 0) { employeeLinemanagerRepository.findActiveForEmployee(any()) }
                }
            }

            it("counts filtered and unfiltered successful requests") {
                withTestApplication {
                    val callerPid = "11223344556"
                    val unfilteredCountBefore = employeeLinemanagerMetricCount("false")
                    val filteredCountBefore = employeeLinemanagerMetricCount("true")
                    coEvery { employeeLinemanagerRepository.findActiveForEmployee(any()) } returns emptyList()
                    texasHttpClientMock.defaultMocks(acr = "Level4", pid = callerPid)

                    val unfilteredResponse = client.get("$INTERNAL_API_V1_PATH$EMPLOYEE_LINEMANAGER_API_PATH") {
                        bearerAuth(createMockToken(callerPid, issuer = tokenXIssuer))
                    }
                    val filteredResponse = client.get(
                        "$INTERNAL_API_V1_PATH$EMPLOYEE_LINEMANAGER_API_PATH?orgNumber=123456789",
                    ) {
                        bearerAuth(createMockToken(callerPid, issuer = tokenXIssuer))
                    }

                    unfilteredResponse.status shouldBe HttpStatusCode.OK
                    filteredResponse.status shouldBe HttpStatusCode.OK
                    employeeLinemanagerMetricCount("false") shouldBeExactly unfilteredCountBefore + 1
                    employeeLinemanagerMetricCount("true") shouldBeExactly filteredCountBefore + 1
                }
            }
        }

        describe("POST /internal/api/v1/linemanager/search") {
            it("is not available through the external API") {
                withTestApplication {
                    val response = client.post("$API_V1_PATH$LINEMANAGER_SEARCH_API_PATH") {
                        contentType(ContentType.Application.Json)
                        setBody(
                            LinemanagerSearchRequest(
                                orgNumber = narmesteLederRelasjon.orgNumber,
                            ),
                        )
                        bearerAuth(createMockToken(narmesteLederRelasjon.orgNumber.value))
                    }

                    response.status shouldBe HttpStatusCode.NotFound
                }
            }

            it("returns paginated linemanager results for authorized Maskinporten principals") {
                withTestApplication {
                    coEvery {
                        linemanagerSearchRepository.search(any())
                    } returns listOf(
                        linemanagerSearchResult(cursorId = 1),
                        linemanagerSearchResult(cursorId = 2, employeeFnr = "12345678911"),
                    )
                    texasHttpClientMock.defaultMocks(
                        systemBrukerOrganisasjon = DefaultOrganization.copy(ID = "0192:${narmesteLederRelasjon.orgNumber.value}"),
                        scope = MASKINPORTEN_NL_SCOPE,
                    )

                    val response = client.post("$INTERNAL_API_V1_PATH$LINEMANAGER_SEARCH_API_PATH") {
                        contentType(ContentType.Application.Json)
                        setBody(
                            LinemanagerSearchRequest(
                                orgNumber = narmesteLederRelasjon.orgNumber,
                                pageSize = 1,
                            ),
                        )
                        bearerAuth(createMockToken(narmesteLederRelasjon.orgNumber.value))
                    }

                    response.status shouldBe HttpStatusCode.OK
                    val body = response.body<LinemanagerReadCollection>()
                    body.linemanagers.shouldHaveSize(1)
                    body.linemanagers.single().id shouldBe UUID(0, 1)
                    body.linemanagers.single().manager.email shouldBe "kari@example.com"
                    body.meta.size shouldBe 1
                    body.meta.pageSize shouldBe 1
                    body.meta.hasMore shouldBe true
                    body.meta.nextPageToken shouldBe LinemanagerSearchCursor(
                        firstName = "ola",
                        lastName = "nordmann",
                        id = 1,
                    ).toOpaqueCursor()
                }
            }

            it("uses pageToken from the request when querying the next page") {
                withTestApplication {
                    val cursor = LinemanagerSearchCursor(
                        firstName = "ola",
                        lastName = "nordmann",
                        id = 1,
                    )
                    coEvery {
                        linemanagerSearchRepository.search(any())
                    } returns listOf(linemanagerSearchResult(cursorId = 2))
                    texasHttpClientMock.defaultMocks(
                        systemBrukerOrganisasjon = DefaultOrganization.copy(ID = "0192:${narmesteLederRelasjon.orgNumber.value}"),
                        scope = MASKINPORTEN_NL_SCOPE,
                    )

                    val response = client.post("$INTERNAL_API_V1_PATH$LINEMANAGER_SEARCH_API_PATH") {
                        contentType(ContentType.Application.Json)
                        setBody(
                            LinemanagerSearchRequest(
                                orgNumber = narmesteLederRelasjon.orgNumber,
                                pageToken = cursor.toOpaqueCursor(),
                            ),
                        )
                        bearerAuth(createMockToken(narmesteLederRelasjon.orgNumber.value))
                    }

                    response.status shouldBe HttpStatusCode.OK
                    coVerify(exactly = 1) {
                        linemanagerSearchRepository.search(
                            match {
                                it.orgNumber == narmesteLederRelasjon.orgNumber &&
                                    it.pageSize == LinemanagerRequirementCollection.DEFAULT_PAGE_SIZE &&
                                    it.cursor == cursor
                            },
                        )
                    }
                }
            }

            it("uses employeeNationalIdentificationNumber from the request when querying") {
                withTestApplication {
                    val employeeNationalIdentificationNumber = PersonalIdentificationNumber("12345678910")
                    coEvery {
                        linemanagerSearchRepository.search(any())
                    } returns listOf(linemanagerSearchResult(cursorId = 1))
                    texasHttpClientMock.defaultMocks(
                        systemBrukerOrganisasjon = DefaultOrganization.copy(ID = "0192:${narmesteLederRelasjon.orgNumber.value}"),
                        scope = MASKINPORTEN_NL_SCOPE,
                    )

                    val response = client.post("$INTERNAL_API_V1_PATH$LINEMANAGER_SEARCH_API_PATH") {
                        contentType(ContentType.Application.Json)
                        setBody(
                            LinemanagerSearchRequest(
                                orgNumber = narmesteLederRelasjon.orgNumber,
                                employeeNationalIdentificationNumber = employeeNationalIdentificationNumber,
                            ),
                        )
                        bearerAuth(createMockToken(narmesteLederRelasjon.orgNumber.value))
                    }

                    response.status shouldBe HttpStatusCode.OK
                    coVerify(exactly = 1) {
                        linemanagerSearchRepository.search(
                            match {
                                it.employeeNationalIdentificationNumber == employeeNationalIdentificationNumber
                            },
                        )
                    }
                }
            }

            it("uses text from the request when querying names") {
                withTestApplication {
                    coEvery {
                        linemanagerSearchRepository.search(any())
                    } returns listOf(linemanagerSearchResult(cursorId = 1))
                    texasHttpClientMock.defaultMocks(
                        systemBrukerOrganisasjon = DefaultOrganization.copy(ID = "0192:${narmesteLederRelasjon.orgNumber.value}"),
                        scope = MASKINPORTEN_NL_SCOPE,
                    )

                    val response = client.post("$INTERNAL_API_V1_PATH$LINEMANAGER_SEARCH_API_PATH") {
                        contentType(ContentType.Application.Json)
                        setBody(
                            LinemanagerSearchRequest(
                                orgNumber = narmesteLederRelasjon.orgNumber,
                                text = "Kari Nordmann",
                            ),
                        )
                        bearerAuth(createMockToken(narmesteLederRelasjon.orgNumber.value))
                    }

                    response.status shouldBe HttpStatusCode.OK
                    coVerify(exactly = 1) {
                        linemanagerSearchRepository.search(
                            match {
                                it.text == "Kari Nordmann" && it.nationalIdentificationNumber == null
                            },
                        )
                    }
                }
            }

            it("normalizes blank text to null") {
                withTestApplication {
                    coEvery {
                        linemanagerSearchRepository.search(any())
                    } returns listOf(linemanagerSearchResult(cursorId = 1))
                    texasHttpClientMock.defaultMocks(
                        systemBrukerOrganisasjon = DefaultOrganization.copy(ID = "0192:${narmesteLederRelasjon.orgNumber.value}"),
                        scope = MASKINPORTEN_NL_SCOPE,
                    )

                    val response = client.post("$INTERNAL_API_V1_PATH$LINEMANAGER_SEARCH_API_PATH") {
                        contentType(ContentType.Application.Json)
                        setBody(
                            LinemanagerSearchRequest(
                                orgNumber = narmesteLederRelasjon.orgNumber,
                                text = "   ",
                            ),
                        )
                        bearerAuth(createMockToken(narmesteLederRelasjon.orgNumber.value))
                    }

                    response.status shouldBe HttpStatusCode.OK
                    coVerify(exactly = 1) {
                        linemanagerSearchRepository.search(
                            match {
                                it.text == null && it.nationalIdentificationNumber == null
                            },
                        )
                    }
                }
            }

            it("uses an eleven-digit text value to query either national identification number") {
                withTestApplication {
                    val nationalIdentificationNumber = PersonalIdentificationNumber("12345678910")
                    coEvery {
                        linemanagerSearchRepository.search(any())
                    } returns listOf(linemanagerSearchResult(cursorId = 1))
                    texasHttpClientMock.defaultMocks(
                        systemBrukerOrganisasjon = DefaultOrganization.copy(ID = "0192:${narmesteLederRelasjon.orgNumber.value}"),
                        scope = MASKINPORTEN_NL_SCOPE,
                    )

                    val response = client.post("$INTERNAL_API_V1_PATH$LINEMANAGER_SEARCH_API_PATH") {
                        contentType(ContentType.Application.Json)
                        setBody(
                            LinemanagerSearchRequest(
                                orgNumber = narmesteLederRelasjon.orgNumber,
                                text = nationalIdentificationNumber.value,
                            ),
                        )
                        bearerAuth(createMockToken(narmesteLederRelasjon.orgNumber.value))
                    }

                    response.status shouldBe HttpStatusCode.OK
                    coVerify(exactly = 1) {
                        linemanagerSearchRepository.search(
                            match {
                                it.text == null && it.nationalIdentificationNumber == nationalIdentificationNumber
                            },
                        )
                    }
                }
            }

            it("returns 400 when text exceeds 50 characters") {
                withTestApplication {
                    coEvery {
                        linemanagerSearchRepository.search(any())
                    } returns listOf(linemanagerSearchResult(cursorId = 1))
                    texasHttpClientMock.defaultMocks(
                        systemBrukerOrganisasjon = DefaultOrganization.copy(ID = "0192:${narmesteLederRelasjon.orgNumber.value}"),
                        scope = MASKINPORTEN_NL_SCOPE,
                    )

                    val response = client.post("$INTERNAL_API_V1_PATH$LINEMANAGER_SEARCH_API_PATH") {
                        contentType(ContentType.Application.Json)
                        setBody(
                            LinemanagerSearchRequest(
                                orgNumber = narmesteLederRelasjon.orgNumber,
                                text = "a".repeat(51),
                            ),
                        )
                        bearerAuth(createMockToken(narmesteLederRelasjon.orgNumber.value))
                    }

                    response.status shouldBe HttpStatusCode.BadRequest
                    response.body<ApiError>().type shouldBe ErrorType.BAD_REQUEST
                    response.body<ApiError>().message shouldBe "text must be at most 50 characters"
                    coVerify(exactly = 0) { linemanagerSearchRepository.search(any()) }
                }
            }

            it("uses hasActiveSickLeave from the request when querying") {
                withTestApplication {
                    coEvery {
                        linemanagerSearchRepository.search(any())
                    } returns listOf(linemanagerSearchResult(cursorId = 1))
                    texasHttpClientMock.defaultMocks(
                        systemBrukerOrganisasjon = DefaultOrganization.copy(ID = "0192:${narmesteLederRelasjon.orgNumber.value}"),
                        scope = MASKINPORTEN_NL_SCOPE,
                    )

                    val response = client.post("$INTERNAL_API_V1_PATH$LINEMANAGER_SEARCH_API_PATH") {
                        contentType(ContentType.Application.Json)
                        setBody(
                            LinemanagerSearchRequest(
                                orgNumber = narmesteLederRelasjon.orgNumber,
                                hasActiveSickLeave = true,
                            ),
                        )
                        bearerAuth(createMockToken(narmesteLederRelasjon.orgNumber.value))
                    }

                    response.status shouldBe HttpStatusCode.OK
                    coVerify(exactly = 1) {
                        linemanagerSearchRepository.search(
                            match {
                                it.hasActiveSickLeave == true
                            },
                        )
                    }
                }
            }

            it("returns linemanager results for authorized TokenX principals") {
                withTestApplication {
                    val callerPid = "11223344556"
                    coEvery {
                        linemanagerSearchRepository.search(any())
                    } returns listOf(linemanagerSearchResult(cursorId = 1))
                    texasHttpClientMock.defaultMocks(
                        acr = "Level4",
                        pid = callerPid,
                    )
                    fakeAltinnTilgangerClient.addAccess(callerPid, narmesteLederRelasjon.orgNumber.value)

                    val response = client.post("$INTERNAL_API_V1_PATH$LINEMANAGER_SEARCH_API_PATH") {
                        contentType(ContentType.Application.Json)
                        setBody(
                            LinemanagerSearchRequest(
                                orgNumber = narmesteLederRelasjon.orgNumber,
                            ),
                        )
                        bearerAuth(createMockToken(callerPid, issuer = tokenXIssuer))
                    }

                    response.status shouldBe HttpStatusCode.OK
                    val body = response.body<LinemanagerReadCollection>()
                    body.linemanagers.single().manager.nationalIdentificationNumber shouldBe PersonalIdentificationNumber(
                        "10987654321"
                    )
                }
            }

            it("counts successful searches by principal type") {
                withTestApplication {
                    val systemSearchesBefore = linemanagerSearchMetricCount("system")
                    val userSearchesBefore = linemanagerSearchMetricCount("user")
                    val callerPid = "11223344556"
                    coEvery {
                        linemanagerSearchRepository.search(any())
                    } returns listOf(linemanagerSearchResult(cursorId = 1))

                    texasHttpClientMock.defaultMocks(
                        systemBrukerOrganisasjon = DefaultOrganization.copy(ID = "0192:${narmesteLederRelasjon.orgNumber.value}"),
                        scope = MASKINPORTEN_NL_SCOPE,
                    )
                    val systemResponse = client.post("$INTERNAL_API_V1_PATH$LINEMANAGER_SEARCH_API_PATH") {
                        contentType(ContentType.Application.Json)
                        setBody(LinemanagerSearchRequest(orgNumber = narmesteLederRelasjon.orgNumber))
                        bearerAuth(createMockToken(narmesteLederRelasjon.orgNumber.value))
                    }

                    texasHttpClientMock.defaultMocks(
                        acr = "Level4",
                        pid = callerPid,
                    )
                    fakeAltinnTilgangerClient.addAccess(callerPid, narmesteLederRelasjon.orgNumber.value)
                    val userResponse = client.post("$INTERNAL_API_V1_PATH$LINEMANAGER_SEARCH_API_PATH") {
                        contentType(ContentType.Application.Json)
                        setBody(LinemanagerSearchRequest(orgNumber = narmesteLederRelasjon.orgNumber))
                        bearerAuth(createMockToken(callerPid, issuer = tokenXIssuer))
                    }

                    systemResponse.status shouldBe HttpStatusCode.OK
                    userResponse.status shouldBe HttpStatusCode.OK
                    linemanagerSearchMetricCount("system") shouldBeExactly systemSearchesBefore + 1
                    linemanagerSearchMetricCount("user") shouldBeExactly userSearchesBefore + 1
                }
            }

            it("returns 400 for invalid orgNumber in request body") {
                withTestApplication {
                    texasHttpClientMock.defaultMocks(
                        systemBrukerOrganisasjon = DefaultOrganization.copy(ID = "0192:${narmesteLederRelasjon.orgNumber.value}"),
                        scope = MASKINPORTEN_NL_SCOPE,
                    )

                    val response = client.post("$INTERNAL_API_V1_PATH$LINEMANAGER_SEARCH_API_PATH") {
                        contentType(ContentType.Application.Json)
                        setBody(
                            """
                        {
                          "orgNumber": "12345678",
                          "pageSize": 1
                        }
                            """.trimIndent(),
                        )
                        bearerAuth(createMockToken(narmesteLederRelasjon.orgNumber.value))
                    }

                    response.status shouldBe HttpStatusCode.BadRequest
                    response.body<ApiError>().type shouldBe ErrorType.INVALID_FORMAT
                }
            }

            it("returns 400 when request contains an unknown field") {
                withTestApplication {
                    texasHttpClientMock.defaultMocks(
                        systemBrukerOrganisasjon = DefaultOrganization.copy(ID = "0192:${narmesteLederRelasjon.orgNumber.value}"),
                        scope = MASKINPORTEN_NL_SCOPE,
                    )

                    val response = client.post("$INTERNAL_API_V1_PATH$LINEMANAGER_SEARCH_API_PATH") {
                        contentType(ContentType.Application.Json)
                        setBody(
                            """
                        {
                          "orgNumber": "${narmesteLederRelasjon.orgNumber.value}",
                          "unknownField": "value"
                        }
                            """.trimIndent(),
                        )
                        bearerAuth(createMockToken(narmesteLederRelasjon.orgNumber.value))
                    }

                    response.status shouldBe HttpStatusCode.BadRequest
                    response.body<ApiError>().type shouldBe ErrorType.INVALID_FORMAT
                    response.body<ApiError>().message shouldBe "Invalid search request. Unknown field: unknownField"
                    coVerify(exactly = 0) { linemanagerSearchRepository.search(any()) }
                }
            }

            it("returns 400 for invalid managerNationalIdentificationNumber in request body") {
                withTestApplication {
                    texasHttpClientMock.defaultMocks(
                        systemBrukerOrganisasjon = DefaultOrganization.copy(ID = "0192:${narmesteLederRelasjon.orgNumber.value}"),
                        scope = MASKINPORTEN_NL_SCOPE,
                    )

                    val response = client.post("$INTERNAL_API_V1_PATH$LINEMANAGER_SEARCH_API_PATH") {
                        contentType(ContentType.Application.Json)
                        setBody(
                            """
                        {
                          "orgNumber": "${narmesteLederRelasjon.orgNumber.value}",
                          "managerNationalIdentificationNumber": "1098765432"
                        }
                            """.trimIndent(),
                        )
                        bearerAuth(createMockToken(narmesteLederRelasjon.orgNumber.value))
                    }

                    response.status shouldBe HttpStatusCode.BadRequest
                    response.body<ApiError>().type shouldBe ErrorType.INVALID_FORMAT
                }
            }

            it("returns 400 for invalid pageToken in request body") {
                withTestApplication {
                    texasHttpClientMock.defaultMocks(
                        systemBrukerOrganisasjon = DefaultOrganization.copy(ID = "0192:${narmesteLederRelasjon.orgNumber.value}"),
                        scope = MASKINPORTEN_NL_SCOPE,
                    )

                    val response = client.post("$INTERNAL_API_V1_PATH$LINEMANAGER_SEARCH_API_PATH") {
                        contentType(ContentType.Application.Json)
                        setBody(
                            """
                        {
                          "orgNumber": "${narmesteLederRelasjon.orgNumber.value}",
                          "pageToken": "not-a-valid-token"
                        }
                            """.trimIndent(),
                        )
                        bearerAuth(createMockToken(narmesteLederRelasjon.orgNumber.value))
                    }

                    response.status shouldBe HttpStatusCode.BadRequest
                    val body = response.body<ApiError>()
                    body.type shouldBe ErrorType.INVALID_FORMAT
                    body.message shouldBe "Invalid pageToken"
                    coVerify(exactly = 0) { linemanagerSearchRepository.search(any()) }
                }
            }

            it("returns 400 for v1 pageToken in request body") {
                withTestApplication {
                    texasHttpClientMock.defaultMocks(
                        systemBrukerOrganisasjon = DefaultOrganization.copy(ID = "0192:${narmesteLederRelasjon.orgNumber.value}"),
                        scope = MASKINPORTEN_NL_SCOPE,
                    )

                    val response = client.post("$INTERNAL_API_V1_PATH$LINEMANAGER_SEARCH_API_PATH") {
                        contentType(ContentType.Application.Json)
                        setBody(
                            """
                        {
                          "orgNumber": "${narmesteLederRelasjon.orgNumber.value}",
                          "pageToken": "djE6MQ"
                        }
                            """.trimIndent(),
                        )
                        bearerAuth(createMockToken(narmesteLederRelasjon.orgNumber.value))
                    }

                    response.status shouldBe HttpStatusCode.BadRequest
                    response.body<ApiError>().type shouldBe ErrorType.INVALID_FORMAT
                    response.body<ApiError>().message shouldBe "Invalid pageToken"
                    coVerify(exactly = 0) { linemanagerSearchRepository.search(any()) }
                }
            }

            it("does not query repository when Maskinporten principal lacks org access") {
                withTestApplication {
                    texasHttpClientMock.defaultMocks(
                        consumer = DefaultOrganization.copy(ID = "0192:000000000"),
                        scope = MASKINPORTEN_NL_SCOPE,
                    )
                    coEvery { pdpService.hasAccessToResource(any(), any(), any()) } returns false

                    val response = client.post("$INTERNAL_API_V1_PATH$LINEMANAGER_SEARCH_API_PATH") {
                        contentType(ContentType.Application.Json)
                        setBody(
                            LinemanagerSearchRequest(
                                orgNumber = narmesteLederRelasjon.orgNumber,
                            ),
                        )
                        bearerAuth(createMockToken("000000000"))
                    }

                    response.status shouldBe HttpStatusCode.Forbidden
                    response.body<ApiError>().type shouldBe ErrorType.MISSING_ALITINN_RESOURCE_ACCESS
                    coVerify(exactly = 0) { linemanagerSearchRepository.search(any()) }
                }
            }

            it("does not query repository when TokenX principal lacks org access") {
                withTestApplication {
                    val callerPid = "11223344556"
                    texasHttpClientMock.defaultMocks(
                        acr = "Level4",
                        pid = callerPid,
                    )

                    val response = client.post("$INTERNAL_API_V1_PATH$LINEMANAGER_SEARCH_API_PATH") {
                        contentType(ContentType.Application.Json)
                        setBody(
                            LinemanagerSearchRequest(
                                orgNumber = narmesteLederRelasjon.orgNumber,
                            ),
                        )
                        bearerAuth(createMockToken(callerPid, issuer = tokenXIssuer))
                    }

                    response.status shouldBe HttpStatusCode.Forbidden
                    coVerify(exactly = 0) { linemanagerSearchRepository.search(any()) }
                }
            }
        }
    })

private fun linemanagerSearchMetricCount(principalType: String): Double = METRICS_REGISTRY
    .find(LINEMANAGER_SEARCH_TOTAL)
    .tag("principal_type", principalType)
    .counter()
    ?.count()
    ?: 0.0

private fun employeeLinemanagerMetricCount(filtered: String): Double = METRICS_REGISTRY
    .find(EMPLOYEE_LINEMANAGER_TOTAL)
    .tag("filtered", filtered)
    .counter()
    ?.count()
    ?: 0.0
