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
import linemanagerRevoke
import no.nav.syfo.application.api.API_V1_PATH
import no.nav.syfo.application.api.ApiError
import no.nav.syfo.application.api.ErrorType
import no.nav.syfo.application.auth.maskinportenIdToOrgnumber
import no.nav.syfo.ident.PersonIdent
import no.nav.syfo.narmesteleder.domain.LinemanagerRevoke
import no.nav.syfo.narmesteleder.domain.OrganizationNumber
import no.nav.syfo.narmesteleder.domain.PersonalIdentificationNumber
import no.nav.syfo.narmestelederrelasjon.application.ActiveNarmestelederrelasjon
import no.nav.syfo.narmestelederrelasjon.infrastructure.kafka.NlResponseSource
import no.nav.syfo.texas.MASKINPORTEN_NL_SCOPE
import prepareGetPersonResponse
import java.time.Instant
import java.util.UUID
import no.nav.syfo.ident.OrganizationNumber as IdentOrganizationNumber

class LinemanagerRevokeApiV1Test :
    LinemanagerApiV1TestBase({
        describe("POST /linemanager/revoke") {
            it("Maskinporten POST /linemanager/revoke should return 202 Accepted for valid payload") {
                val narmesteLederAvkreft = LinemanagerRevoke(
                    employeeIdentificationNumber = PersonalIdentificationNumber("12345678901"),
                    orgNumber = OrganizationNumber("123456789"),
                    lastName = "Hansen",
                )
                withTestApplication {
                    // Arrange
                    texasHttpClientMock.defaultMocks(
                        systemBrukerOrganisasjon =
                        DefaultOrganization.copy(
                            ID = "0192:${narmesteLederAvkreft.orgNumber.value}",
                        ),
                        scope = MASKINPORTEN_NL_SCOPE,
                    )
                    pdlService.prepareGetPersonResponse(
                        narmesteLederAvkreft.employeeIdentificationNumber.value,
                        narmesteLederAvkreft.lastName,
                    )
                    activeRelationRepository.rows = listOf(activeRelation())
                    fakeAaregClient.arbeidsForholdForIdent.clear()
                    fakeAaregClient.arbeidsForholdForIdent[narmesteLederAvkreft.employeeIdentificationNumber.value] =
                        listOf(narmesteLederAvkreft.orgNumber.value to narmesteLederAvkreft.orgNumber.value)
                    // Act
                    val response =
                        client.post("$API_V1_PATH/$REVOKE_PATH") {
                            contentType(ContentType.Application.Json)
                            setBody(narmesteLederAvkreft)
                            bearerAuth(createMockToken(narmesteLederAvkreft.orgNumber.value))
                        }

                    // Assert
                    response.status shouldBe HttpStatusCode.Accepted
                    coVerify(exactly = 1) {
                        narmestelederKafkaServiceSpy.avbrytNarmesteLederRelation(
                            narmesteLederAvkreft,
                            NlResponseSource.LPS_REVOKE,
                        )
                    }
                    coVerify(exactly = 1) {
                        validationServiceSpy.validateLinemanagerRevoke(
                            narmesteLederAvkreft,
                            any(),
                        )
                    }
                    activeRelationRepository.lookups shouldBe listOf(
                        PersonIdent(narmesteLederAvkreft.employeeIdentificationNumber.value) to
                            IdentOrganizationNumber(narmesteLederAvkreft.orgNumber.value)
                    )
                }
            }

            it("should revoke an active relation with an invalid stored manager email") {
                val narmesteLederAvkreft = linemanagerRevoke()
                withTestApplication {
                    // Arrange
                    texasHttpClientMock.defaultMocks(
                        systemBrukerOrganisasjon = DefaultOrganization.copy(
                            ID = "0192:${narmesteLederAvkreft.orgNumber.value}",
                        ),
                        scope = MASKINPORTEN_NL_SCOPE,
                    )
                    pdlService.prepareGetPersonResponse(
                        narmesteLederAvkreft.employeeIdentificationNumber.value,
                        narmesteLederAvkreft.lastName,
                    )
                    activeRelationRepository.rows = listOf(activeRelation().copy(managerEmail = "leder@nav"))

                    // Act
                    val response = client.post("$API_V1_PATH/$REVOKE_PATH") {
                        contentType(ContentType.Application.Json)
                        setBody(narmesteLederAvkreft)
                        bearerAuth(createMockToken(narmesteLederAvkreft.orgNumber.value))
                    }

                    // Assert
                    response.status shouldBe HttpStatusCode.Accepted
                    coVerify(exactly = 1) {
                        narmestelederKafkaServiceSpy.avbrytNarmesteLederRelation(
                            narmesteLederAvkreft,
                            NlResponseSource.LPS_REVOKE,
                        )
                    }
                    activeRelationRepository.lookups shouldBe listOf(
                        PersonIdent(narmesteLederAvkreft.employeeIdentificationNumber.value) to
                            IdentOrganizationNumber(narmesteLederAvkreft.orgNumber.value)
                    )
                }
            }

            it("should return 400 when lastName in payload does not match the nin") {
                val narmesteLederAvkreft = linemanagerRevoke()
                withTestApplication {
                    // Arrange
                    texasHttpClientMock.defaultMocks(
                        systemBrukerOrganisasjon =
                        DefaultOrganization.copy(
                            ID = "0192:${narmesteLederAvkreft.orgNumber.value}",
                        ),
                        scope = MASKINPORTEN_NL_SCOPE,
                    )
                    pdlService.prepareGetPersonResponse(
                        narmesteLederAvkreft.employeeIdentificationNumber.value,
                        narmesteLederAvkreft.lastName.reversed(),
                    )
                    fakeAaregClient.arbeidsForholdForIdent.clear()
                    fakeAaregClient.arbeidsForholdForIdent[narmesteLederAvkreft.employeeIdentificationNumber.value] =
                        listOf(narmesteLederAvkreft.orgNumber.value to narmesteLederRelasjon.orgNumber.value)
                    // Act
                    val response =
                        client.post("$API_V1_PATH/$REVOKE_PATH") {
                            contentType(ContentType.Application.Json)
                            setBody(narmesteLederAvkreft)
                            bearerAuth(createMockToken(maskinportenIdToOrgnumber(DefaultOrganization.ID)))
                        }

                    // Assert
                    response.status shouldBe HttpStatusCode.BadRequest
                    val body = response.body<ApiError>()
                    body.type shouldBe ErrorType.EMPLOYEE_NAME_NATIONAL_IDENTIFICATION_NUMBER_MISMATCH
                    coVerify(exactly = 0) {
                        narmestelederKafkaServiceSpy.avbrytNarmesteLederRelation(
                            eq(narmesteLederAvkreft),
                            eq(
                                NlResponseSource.LPS,
                            ),
                        )
                    }
                    coVerify(exactly = 1) {
                        validationServiceSpy.validateLinemanagerRevoke(
                            eq(narmesteLederAvkreft),
                            any(),
                        )
                    }
                }
            }

            it("should return 202 if employee lacks employment for organization number") {
                withTestApplication {
                    // Arrange
                    val narmesteLederAvkreft = linemanagerRevoke()
                    texasHttpClientMock.defaultMocks(
                        systemBrukerOrganisasjon = DefaultOrganization.copy(
                            ID = "0192:${narmesteLederAvkreft.orgNumber.value}",
                        ),
                        scope = MASKINPORTEN_NL_SCOPE,
                    )
                    pdlService.prepareGetPersonResponse(
                        narmesteLederAvkreft.employeeIdentificationNumber.value,
                        narmesteLederAvkreft.lastName,
                    )
                    activeRelationRepository.rows = listOf(activeRelation())

                    // Act
                    val response =
                        client.post("$API_V1_PATH/$REVOKE_PATH") {
                            contentType(ContentType.Application.Json)
                            setBody(narmesteLederAvkreft)
                            bearerAuth(createMockToken(maskinportenIdToOrgnumber(DefaultOrganization.ID)))
                        }

                    // Assert
                    response.status shouldBe HttpStatusCode.Accepted
                    coVerify(exactly = 1) {
                        narmestelederKafkaServiceSpy.avbrytNarmesteLederRelation(
                            narmesteLederAvkreft,
                            NlResponseSource.LPS_REVOKE,
                        )
                    }
                }
            }

            it("should return 204 and not publish when no active line manager relation exists") {
                val narmesteLederAvkreft = linemanagerRevoke()
                withTestApplication {
                    // Arrange
                    texasHttpClientMock.defaultMocks(
                        systemBrukerOrganisasjon = DefaultOrganization.copy(
                            ID = "0192:${narmesteLederAvkreft.orgNumber.value}",
                        ),
                        scope = MASKINPORTEN_NL_SCOPE,
                    )
                    pdlService.prepareGetPersonResponse(
                        narmesteLederAvkreft.employeeIdentificationNumber.value,
                        narmesteLederAvkreft.lastName,
                    )

                    // Act
                    val response =
                        client.post("$API_V1_PATH/$REVOKE_PATH") {
                            contentType(ContentType.Application.Json)
                            setBody(narmesteLederAvkreft)
                            bearerAuth(createMockToken(narmesteLederAvkreft.orgNumber.value))
                        }

                    // Assert
                    response.status shouldBe HttpStatusCode.NoContent
                    coVerify(exactly = 1) {
                        validationServiceSpy.validateLinemanagerRevoke(
                            narmesteLederAvkreft,
                            any(),
                        )
                    }
                    activeRelationRepository.lookups shouldBe listOf(
                        PersonIdent(narmesteLederAvkreft.employeeIdentificationNumber.value) to
                            IdentOrganizationNumber(narmesteLederAvkreft.orgNumber.value)
                    )
                    coVerify(exactly = 0) {
                        narmestelederKafkaServiceSpy.avbrytNarmesteLederRelation(any(), any())
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
                        client.post("$API_V1_PATH/$REVOKE_PATH") {
                            contentType(ContentType.Application.Json)
                            setBody("""{ "navn": "Ola Nordmann" }""")
                            bearerAuth(createMockToken(maskinportenIdToOrgnumber(DefaultOrganization.ID)))
                        }

                    // Assert
                    response.status shouldBe HttpStatusCode.BadRequest
                    response.body<ApiError>().type shouldBe ErrorType.INVALID_FORMAT
                    coVerify { narmestelederKafkaServiceSpy wasNot Called }
                }
            }
        }
    })

private fun activeRelation() = ActiveNarmestelederrelasjon(
    id = UUID.fromString("00000000-0000-0000-0000-000000000001"),
    managerIdent = PersonIdent("10987654321"),
    managerEmail = "leder@example.com",
    activeFrom = Instant.parse("2026-01-01T00:00:00Z"),
)
