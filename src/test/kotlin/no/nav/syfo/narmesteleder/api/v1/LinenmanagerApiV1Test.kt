package no.nav.syfo.narmesteleder.api.v1

import DefaultOrganization
import createMockToken
import defaultMocks
import io.kotest.matchers.shouldBe
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.mockk.Called
import io.mockk.coVerify
import linemanager
import no.nav.syfo.application.api.ApiError
import no.nav.syfo.application.api.ErrorType
import no.nav.syfo.application.auth.maskinportenIdToOrgnumber
import no.nav.syfo.narmesteleder.domain.LinemanagerActors
import no.nav.syfo.narmesteleder.kafka.model.NlResponseSource
import no.nav.syfo.texas.MASKINPORTEN_NL_SCOPE
import prepareGetPersonResponse

class LinenmanagerApiV1Test :
    LinemanagerApiV1TestBase({
        describe("POST /linemanager") {
            context("Maskinporten token") {
                it("Maskinporten POST /linemanager should return 202 Accepted for valid payload") {
                    withTestApplication {
                        // Arrange
                        pdlService.prepareGetPersonResponse(narmesteLederRelasjon.manager)
                        pdlService.prepareGetPersonResponse(
                            narmesteLederRelasjon.employeeIdentificationNumber.value,
                            narmesteLederRelasjon.lastName,
                        )
                        texasHttpClientMock.defaultMocks(
                            systemBrukerOrganisasjon =
                            DefaultOrganization.copy(
                                ID = "0192:${narmesteLederRelasjon.orgNumber.value}",
                            ),
                            scope = MASKINPORTEN_NL_SCOPE,
                        )
                        fakeAaregClient.arbeidsForholdForIdent[narmesteLederRelasjon.employeeIdentificationNumber.value] =
                            listOf(narmesteLederRelasjon.orgNumber.value to narmesteLederRelasjon.orgNumber.value)
                        fakeAaregClient.arbeidsForholdForIdent[narmesteLederRelasjon.manager.nationalIdentificationNumber.value] =
                            listOf(narmesteLederRelasjon.orgNumber.value to narmesteLederRelasjon.orgNumber.value)
                        // Act
                        val response =
                            client.post("/api/v1/linemanager") {
                                contentType(ContentType.Application.Json)
                                setBody(narmesteLederRelasjon)
                                bearerAuth(createMockToken(narmesteLederRelasjon.orgNumber.value))
                            }

                        // Assert
                        response.status shouldBe HttpStatusCode.Accepted
                        coVerify(exactly = 1) {
                            narmestelederKafkaServiceSpy.sendNarmesteLederRelasjon(
                                narmesteLederRelasjon,
                                linemanagerActors = any<LinemanagerActors>(),
                                NlResponseSource.LPS,
                            )
                        }
                    }
                }

                it("Maskinporten POST /linemanager should normalize spaces in valid phone numbers") {
                    withTestApplication {
                        val linemanagerWithSpacedPhone = narmesteLederRelasjon.copy(
                            manager = narmesteLederRelasjon.manager.copy(
                                mobile = "+47 90 00 00 00",
                                email = "leder+ø@eksempelø.no; annen@domene.no ",
                            ),
                        )
                        pdlService.prepareGetPersonResponse(linemanagerWithSpacedPhone.manager)
                        pdlService.prepareGetPersonResponse(
                            linemanagerWithSpacedPhone.employeeIdentificationNumber.value,
                            linemanagerWithSpacedPhone.lastName,
                        )
                        texasHttpClientMock.defaultMocks(
                            systemBrukerOrganisasjon =
                            DefaultOrganization.copy(
                                ID = "0192:${linemanagerWithSpacedPhone.orgNumber.value}",
                            ),
                            scope = MASKINPORTEN_NL_SCOPE,
                        )
                        fakeAaregClient.arbeidsForholdForIdent[linemanagerWithSpacedPhone.employeeIdentificationNumber.value] =
                            listOf(linemanagerWithSpacedPhone.orgNumber.value to linemanagerWithSpacedPhone.orgNumber.value)
                        fakeAaregClient.arbeidsForholdForIdent[linemanagerWithSpacedPhone.manager.nationalIdentificationNumber.value] =
                            listOf(linemanagerWithSpacedPhone.orgNumber.value to linemanagerWithSpacedPhone.orgNumber.value)

                        val response =
                            client.post("/api/v1/linemanager") {
                                contentType(ContentType.Application.Json)
                                setBody(linemanagerWithSpacedPhone)
                                bearerAuth(createMockToken(linemanagerWithSpacedPhone.orgNumber.value))
                            }

                        response.status shouldBe HttpStatusCode.Accepted
                        coVerify(exactly = 1) {
                            narmestelederKafkaServiceSpy.sendNarmesteLederRelasjon(
                                match {
                                    it.manager.mobile == "+4790000000" &&
                                        it.manager.email == "leder+ø@eksempelø.no;annen@domene.no"
                                },
                                any(),
                                NlResponseSource.LPS,
                            )
                        }
                    }
                }

                it("Maskinporten POST /linemanager should return 400 for invalid phone and email") {
                    withTestApplication {
                        val linemanagerWithInvalidContacts = narmesteLederRelasjon.copy(
                            manager = narmesteLederRelasjon.manager.copy(
                                mobile = "90-00-00-00",
                                email = "gyldig@example.com; invalid @example.com",
                            ),
                        )
                        pdlService.prepareGetPersonResponse(linemanagerWithInvalidContacts.manager)
                        pdlService.prepareGetPersonResponse(
                            linemanagerWithInvalidContacts.employeeIdentificationNumber.value,
                            linemanagerWithInvalidContacts.lastName,
                        )
                        texasHttpClientMock.defaultMocks(
                            systemBrukerOrganisasjon =
                            DefaultOrganization.copy(
                                ID = "0192:${linemanagerWithInvalidContacts.orgNumber.value}",
                            ),
                            scope = MASKINPORTEN_NL_SCOPE,
                        )
                        fakeAaregClient.arbeidsForholdForIdent[linemanagerWithInvalidContacts.employeeIdentificationNumber.value] =
                            listOf(linemanagerWithInvalidContacts.orgNumber.value to linemanagerWithInvalidContacts.orgNumber.value)
                        fakeAaregClient.arbeidsForholdForIdent[linemanagerWithInvalidContacts.manager.nationalIdentificationNumber.value] =
                            listOf(linemanagerWithInvalidContacts.orgNumber.value to linemanagerWithInvalidContacts.orgNumber.value)

                        val response =
                            client.post("/api/v1/linemanager") {
                                contentType(ContentType.Application.Json)
                                setBody(linemanagerWithInvalidContacts)
                                bearerAuth(createMockToken(linemanagerWithInvalidContacts.orgNumber.value))
                            }

                        val apiError = response.body<ApiError>()
                        response.status shouldBe HttpStatusCode.BadRequest
                        apiError.type shouldBe ErrorType.INVALID_FORMAT
                        apiError.message.contains("90-00-00-00") shouldBe false
                        apiError.message.contains("invalid @example.com") shouldBe false
                        apiError.message.contains("gyldig@example.com") shouldBe false
                        coVerify(exactly = 0) {
                            narmestelederKafkaServiceSpy.sendNarmesteLederRelasjon(any(), any(), any())
                        }
                    }
                }

                it("should return 400 Bad Request for invalid payload") {
                    withTestApplication {
                        // Arrange
                        texasHttpClientMock.defaultMocks(
                            consumer =
                            DefaultOrganization.copy(
                                ID = "0192:${narmesteLederRelasjon.orgNumber.value}",
                            ),
                            scope = MASKINPORTEN_NL_SCOPE,
                        )
                        // Act
                        val response =
                            client.post("/api/v1/linemanager") {
                                contentType(ContentType.Application.Json)
                                setBody("""{ "name": "Ola Nordmann" }""")
                                bearerAuth(createMockToken(maskinportenIdToOrgnumber(DefaultOrganization.ID)))
                            }

                        // Assert
                        response.status shouldBe HttpStatusCode.BadRequest
                        response.body<ApiError>().type shouldBe ErrorType.INVALID_FORMAT
                        coVerify { narmestelederKafkaServiceSpy wasNot Called }
                    }
                }

                it("should return 400 Bad Request for invalid organization number in request body") {
                    withTestApplication {
                        texasHttpClientMock.defaultMocks(
                            consumer = DefaultOrganization.copy(
                                ID = "0192:${narmesteLederRelasjon.orgNumber.value}",
                            ),
                            scope = MASKINPORTEN_NL_SCOPE,
                        )

                        val response =
                            client.post("/api/v1/linemanager") {
                                contentType(ContentType.Application.Json)
                                setBody(
                                    """
                                {
                                  "employeeIdentificationNumber": "12345678901",
                                  "lastName": "Hansen",
                                  "orgNumber": "12345678",
                                  "manager": {
                                    "nationalIdentificationNumber": "10987654321",
                                    "lastName": "Jensen",
                                    "mobile": "+4790000000",
                                    "email": "leder@example.com"
                                  }
                                }
                                    """.trimIndent(),
                                )
                                bearerAuth(createMockToken(maskinportenIdToOrgnumber(DefaultOrganization.ID)))
                            }

                        response.status shouldBe HttpStatusCode.BadRequest
                        response.body<ApiError>().type shouldBe ErrorType.INVALID_FORMAT
                        coVerify { narmestelederKafkaServiceSpy wasNot Called }
                    }
                }

                it("should return 401 unauthorized for missing token") {
                    withTestApplication {
                        // Arrange
                        texasHttpClientMock.defaultMocks()
                        // Act
                        val response =
                            client.post("/api/v1/linemanager") {
                                contentType(ContentType.Application.Json)
                                setBody(linemanager())
                            }

                        // Assert
                        response.status shouldBe HttpStatusCode.Unauthorized
                        response.body<ApiError>().type shouldBe ErrorType.AUTHORIZATION_ERROR
                        coVerify { narmestelederKafkaServiceSpy wasNot Called }
                    }
                }

                it("should return 401 unauthorized for missing valid maskinporten scope") {
                    withTestApplication {
                        // Arrange
                        texasHttpClientMock.defaultMocks(
                            consumer =
                            DefaultOrganization.copy(
                                ID = "0192:${narmesteLederRelasjon.orgNumber.value}",
                            ),
                            scope = "invalid-scope",
                        )
                        // Act
                        val response =
                            client.post("/api/v1/linemanager") {
                                contentType(ContentType.Application.Json)
                                setBody(linemanager())
                            }

                        // Assert
                        response.status shouldBe HttpStatusCode.Unauthorized
                        response.body<ApiError>().type shouldBe ErrorType.AUTHORIZATION_ERROR
                        coVerify { narmestelederKafkaServiceSpy wasNot Called }
                    }
                }

                it("should return 401 unauthorized for invalid token issuer") {
                    withTestApplication {
                        // Arrange
                        texasHttpClientMock.defaultMocks()
                        // Act
                        val response =
                            client.post("/api/v1/linemanager") {
                                contentType(ContentType.Application.Json)
                                setBody(linemanager())
                                bearerAuth(createMockToken(ident = "", issuer = "invalid"))
                            }

                        // Assert
                        response.status shouldBe HttpStatusCode.Unauthorized
                        response.body<ApiError>().type shouldBe ErrorType.AUTHORIZATION_ERROR
                        coVerify { narmestelederKafkaServiceSpy wasNot Called }
                    }
                }
            }
            context("TokenX token") {
                it("TokenX POST /linemanager should return 202 Accepted for valid payload") {
                    withTestApplication {
                        // Arrange
                        pdlService.prepareGetPersonResponse(narmesteLederRelasjon.manager)
                        pdlService.prepareGetPersonResponse(
                            narmesteLederRelasjon.employeeIdentificationNumber.value,
                            narmesteLederRelasjon.lastName,
                        )
                        val callerPid = "11223344556"
                        texasHttpClientMock.defaultMocks(
                            acr = "Level4",
                            pid = callerPid,
                        )
                        fakeAltinnTilgangerClient.accessPolicy.clear()
                        fakeAltinnTilgangerClient.addAccess(callerPid, narmesteLederRelasjon.orgNumber.value)
                        fakeAaregClient.arbeidsForholdForIdent.put(
                            narmesteLederRelasjon.employeeIdentificationNumber.value,
                            listOf(narmesteLederRelasjon.orgNumber.value to narmesteLederRelasjon.orgNumber.value),
                        )
                        fakeAaregClient.arbeidsForholdForIdent.put(
                            narmesteLederRelasjon.manager.nationalIdentificationNumber.value,
                            listOf(narmesteLederRelasjon.orgNumber.value to narmesteLederRelasjon.orgNumber.value),
                        )
                        // Act
                        val response =
                            client.post("/api/v1/linemanager") {
                                contentType(ContentType.Application.Json)
                                setBody(narmesteLederRelasjon)
                                bearerAuth(createMockToken(callerPid, issuer = tokenXIssuer))
                            }
                        // Assert
                        response.status shouldBe HttpStatusCode.Accepted
                        coVerify(exactly = 1) {
                            narmestelederKafkaServiceSpy.sendNarmesteLederRelasjon(
                                narmesteLederRelasjon,
                                linemanagerActors = any<LinemanagerActors>(),
                                NlResponseSource.PERSONALLEDER,
                            )
                        }
                        coVerify(exactly = 1) {
                            validationServiceSpy.validateLinemanager(
                                narmesteLederRelasjon,
                                any(),
                            )
                        }
                    }
                }

                it("should return 403 when caller lacks access to organization number for Linemanager") {
                    withTestApplication {
                        // Arrange
                        val callerPid = "11223344556"
                        texasHttpClientMock.defaultMocks(
                            acr = "Level4",
                            pid = callerPid,
                        )
                        // Act
                        val response =
                            client.post("/api/v1/linemanager") {
                                contentType(ContentType.Application.Json)
                                setBody(narmesteLederRelasjon)
                                bearerAuth(createMockToken(callerPid, issuer = tokenXIssuer))
                            }

                        // Assert
                        response.status shouldBe HttpStatusCode.Forbidden
                    }
                }

                it("should return 403 when caller lacks Level4") {
                    withTestApplication {
                        // Arrange
                        val callerPid = "11223344556"
                        texasHttpClientMock.defaultMocks(
                            acr = "Level3",
                            pid = callerPid,
                        )
                        fakeAltinnTilgangerClient.addAccess(callerPid, narmesteLederRelasjon.orgNumber.value)
                        // Act
                        val response =
                            client.post("/api/v1/linemanager") {
                                contentType(ContentType.Application.Json)
                                setBody(narmesteLederRelasjon)
                                bearerAuth(createMockToken(callerPid, issuer = tokenXIssuer))
                            }

                        // Assert
                        response.status shouldBe HttpStatusCode.Forbidden
                    }
                }
            }
        }
    })
