package no.nav.syfo.narmesteleder.api.v1

import DefaultOrganization
import createMockToken
import defaultMocks
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.shouldBeExactly
import io.kotest.matchers.shouldBe
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.mockk.coEvery
import io.mockk.coVerify
import no.nav.syfo.altinn.pdp.client.Decision
import no.nav.syfo.application.api.API_V1_PATH
import no.nav.syfo.application.api.ApiError
import no.nav.syfo.application.api.ErrorType
import no.nav.syfo.application.api.INTERNAL_API_V1_PATH
import no.nav.syfo.application.metric.METRICS_REGISTRY
import no.nav.syfo.narmesteleder.domain.LinemanagerReadCollection
import no.nav.syfo.narmesteleder.domain.LinemanagerRequirementCollection
import no.nav.syfo.narmesteleder.domain.LinemanagerSearchCursor
import no.nav.syfo.narmesteleder.domain.LinemanagerSearchRequest
import no.nav.syfo.narmesteleder.domain.LinemanagerStatistics
import no.nav.syfo.narmesteleder.domain.PersonalIdentificationNumber
import no.nav.syfo.texas.MASKINPORTEN_NL_SCOPE
import java.util.UUID

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
                    coEvery { pdpService.accessDecisionForResource(any(), any(), any()) } returns Decision.Deny

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
