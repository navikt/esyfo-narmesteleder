package no.nav.syfo.narmesteleder.api.v1

import DefaultOrganization
import createMockToken
import defaultMocks
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.mockk.coEvery
import io.mockk.coVerify
import manager
import nlBehovEntity
import no.nav.syfo.API_V1_PATH
import no.nav.syfo.application.api.ApiError
import no.nav.syfo.application.api.ErrorType
import no.nav.syfo.ereg.client.Organisasjon
import no.nav.syfo.narmesteleder.db.NarmestelederBehovEntity
import no.nav.syfo.narmesteleder.domain.BehovReason
import no.nav.syfo.narmesteleder.domain.BehovStatus
import no.nav.syfo.narmesteleder.domain.Linemanager
import no.nav.syfo.narmesteleder.domain.LinemanagerRequirementCollection
import no.nav.syfo.narmesteleder.domain.LinemanagerRequirementRead
import no.nav.syfo.narmesteleder.domain.LinemanagerRequirementWrite
import no.nav.syfo.narmesteleder.domain.OrganizationNumber
import no.nav.syfo.narmesteleder.domain.PersonalIdentificationNumber
import no.nav.syfo.narmesteleder.service.BehovSource
import no.nav.syfo.texas.MASKINPORTEN_NL_SCOPE
import prepareGetPersonResponse
import java.time.Instant
import java.util.UUID

class LinemanagerRequirementApiV1Test :
    LinemanagerApiV1TestBase({
        describe("/linemanager/requirement endpoints") {
            val sykmeldtFnr = narmesteLederRelasjon.employeeIdentificationNumber.value
            val lederFnr = narmesteLederRelasjon.manager.nationalIdentificationNumber.value
            val orgnummer = narmesteLederRelasjon.orgNumber.value

            fun Linemanager.toNlBehovWrite(): LinemanagerRequirementWrite = LinemanagerRequirementWrite(
                employeeIdentificationNumber = PersonalIdentificationNumber(sykmeldtFnr),
                orgNumber = OrganizationNumber(orgNumber.value),
                managerIdentificationNumber = manager.nationalIdentificationNumber,
                behovReason = BehovReason.DEAKTIVERT_LEDER,
                revokedLinemanagerId = UUID.randomUUID(),
            )

            suspend fun seedLinemanagerRequirement(): UUID {
                fakeAaregClient.arbeidsForholdForIdent.put(sykmeldtFnr, listOf(orgnummer to orgnummer))
                fakeAaregClient.arbeidsForholdForIdent.put(lederFnr, listOf(orgnummer to orgnummer))
                narmesteLederService.createNewNlBehov(
                    narmesteLederRelasjon.toNlBehovWrite(),
                    behovSource = BehovSource(UUID.randomUUID().toString(), "test")
                )
                return fakeRepo.lastId() ?: error("No requirement seeded")
            }
            describe("GET /requirement/{id}") {
                it("GET /requirement/{id} 200 with Maskinporten token") {
                    withTestApplication {
                        texasHttpClientMock.defaultMocks(
                            systemBrukerOrganisasjon = DefaultOrganization.copy(ID = "0192:$orgnummer"),
                            scope = MASKINPORTEN_NL_SCOPE,
                        )
                        val requirementId = seedLinemanagerRequirement()
                        val response =
                            client.get("$API_V1_PATH/$RECUIREMENT_PATH/$requirementId") {
                                bearerAuth(createMockToken(orgnummer))
                            }
                        response.status shouldBe HttpStatusCode.OK
                        val body = response.body<LinemanagerRequirementRead>()
                        body.id shouldBe requirementId
                        body.orgNumber.value shouldBe orgnummer
                        body.employeeIdentificationNumber.value shouldBe sykmeldtFnr
                    }
                }

                it("GET /requirement/{id} 404 when requirement not found") {
                    withTestApplication {
                        texasHttpClientMock.defaultMocks(
                            consumer = DefaultOrganization.copy(ID = "0192:$orgnummer"),
                            scope = MASKINPORTEN_NL_SCOPE,
                        )
                        val randomId = UUID.randomUUID()
                        val response =
                            client.get("$API_V1_PATH/$RECUIREMENT_PATH/$randomId") {
                                bearerAuth(createMockToken(orgnummer))
                            }
                        response.status shouldBe HttpStatusCode.NotFound
                        response.body<ApiError>().type shouldBe ErrorType.NOT_FOUND
                    }
                }

                it("GET /requirement/{id} 403 when system principal lacks access to AltinnResource for orgnumber") {
                    withTestApplication {
                        texasHttpClientMock.defaultMocks(
                            systemBrukerOrganisasjon = DefaultOrganization.copy(ID = "0192:000000000"), // mismatch org
                            scope = MASKINPORTEN_NL_SCOPE,
                        )
                        fakeEregClient.organisasjoner[narmesteLederRelasjon.orgNumber.value] = Organisasjon(
                            organisasjonsnummer = narmesteLederRelasjon.orgNumber.value,
                            inngaarIJuridiskEnheter = emptyList()
                        )
                        val requirementId = seedLinemanagerRequirement()
                        coEvery { pdpService.hasAccessToResource(any(), any(), any()) } returns false
                        val response =
                            client.get("$API_V1_PATH/$RECUIREMENT_PATH/$requirementId") {
                                bearerAuth(createMockToken("999999999"))
                            }
                        response.status shouldBe HttpStatusCode.Forbidden
                        response.body<ApiError>().type shouldBe ErrorType.MISSING_ALITINN_RESOURCE_ACCESS
                    }
                }
            }

            describe("PUT /requirement/{id}") {
                it("PUT /requirement/{id} 202 updates behov and sends kafka message") {
                    withTestApplication {
                        texasHttpClientMock.defaultMocks(
                            systemBrukerOrganisasjon = DefaultOrganization.copy(ID = "0192:$orgnummer"),
                            scope = MASKINPORTEN_NL_SCOPE,
                        )
                        val requirementId = seedLinemanagerRequirement()
                        val manager =
                            manager().copy(
                                nationalIdentificationNumber =
                                PersonalIdentificationNumber(
                                    narmesteLederRelasjon
                                        .manager
                                        .nationalIdentificationNumber
                                        .value
                                        .reversed(),
                                ),
                            )
                        pdlService.prepareGetPersonResponse(manager)
                        fakeAaregClient.arbeidsForholdForIdent[manager.nationalIdentificationNumber.value] =
                            listOf(orgnummer to orgnummer)

                        val response =
                            client.put("$API_V1_PATH/$RECUIREMENT_PATH/$requirementId") {
                                contentType(ContentType.Application.Json)
                                setBody(manager)
                                bearerAuth(createMockToken(orgnummer))
                            }
                        response.status shouldBe HttpStatusCode.Accepted
                        coVerify(exactly = 1) {
                            narmestelederKafkaServiceSpy.sendNarmesteLederRelasjon(
                                match { linemanager ->
                                    linemanager.employeeIdentificationNumber.value == sykmeldtFnr &&
                                        linemanager.orgNumber.value == orgnummer &&
                                        linemanager.manager.nationalIdentificationNumber.value == manager.nationalIdentificationNumber.value
                                },
                                any(),
                                any(),
                            )
                        }
                        val stored = fakeRepo.findBehovById(requirementId) ?: error("Stored requirement missing")
                        stored.behovStatus.name shouldBe BehovStatus.BEHOV_FULFILLED.name
                    }
                }

                it("PUT /requirement/{id} should normalize spaces in valid phone numbers") {
                    withTestApplication {
                        texasHttpClientMock.defaultMocks(
                            systemBrukerOrganisasjon = DefaultOrganization.copy(ID = "0192:$orgnummer"),
                            scope = MASKINPORTEN_NL_SCOPE,
                        )
                        val requirementId = seedLinemanagerRequirement()
                        val spacedPhoneManager = manager().copy(
                            nationalIdentificationNumber = PersonalIdentificationNumber(
                                narmesteLederRelasjon.manager.nationalIdentificationNumber.value.reversed(),
                            ),
                            mobile = "+47 90 00 00 00",
                            email = "leder+ø@eksempelø.no; annen@domene.no ",
                        )
                        pdlService.prepareGetPersonResponse(spacedPhoneManager)
                        fakeAaregClient.arbeidsForholdForIdent[spacedPhoneManager.nationalIdentificationNumber.value] =
                            listOf(orgnummer to orgnummer)

                        val response =
                            client.put("$API_V1_PATH/$RECUIREMENT_PATH/$requirementId") {
                                contentType(ContentType.Application.Json)
                                setBody(spacedPhoneManager)
                                bearerAuth(createMockToken(orgnummer))
                            }

                        response.status shouldBe HttpStatusCode.Accepted
                        coVerify(exactly = 1) {
                            narmestelederKafkaServiceSpy.sendNarmesteLederRelasjon(
                                match { linemanager ->
                                    linemanager.manager.mobile == "+4790000000" &&
                                        linemanager.manager.email == "leder+ø@eksempelø.no;annen@domene.no"
                                },
                                any(),
                                any(),
                            )
                        }
                    }
                }

                it("PUT /requirement/{id} should return 400 for invalid phone and email") {
                    withTestApplication {
                        texasHttpClientMock.defaultMocks(
                            systemBrukerOrganisasjon = DefaultOrganization.copy(ID = "0192:$orgnummer"),
                            scope = MASKINPORTEN_NL_SCOPE,
                        )
                        val requirementId = seedLinemanagerRequirement()
                        val invalidContactManager = manager().copy(
                            nationalIdentificationNumber = PersonalIdentificationNumber(
                                narmesteLederRelasjon.manager.nationalIdentificationNumber.value.reversed(),
                            ),
                            mobile = "90-00-00-00",
                            email = "gyldig@example.com; invalid @example.com",
                        )

                        val response =
                            client.put("$API_V1_PATH/$RECUIREMENT_PATH/$requirementId") {
                                contentType(ContentType.Application.Json)
                                setBody(invalidContactManager)
                                bearerAuth(createMockToken(orgnummer))
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

                it("PUT /requirement/{id} 404 when behov not found") {
                    withTestApplication {
                        texasHttpClientMock.defaultMocks(
                            consumer = DefaultOrganization.copy(ID = "0192:$orgnummer"),
                            scope = MASKINPORTEN_NL_SCOPE,
                        )
                        val randomId = UUID.randomUUID()
                        fakeAaregClient.arbeidsForholdForIdent.put(sykmeldtFnr, listOf(orgnummer to orgnummer))
                        fakeAaregClient.arbeidsForholdForIdent.put(lederFnr, listOf(orgnummer to orgnummer))
                        val response =
                            client.put("$API_V1_PATH/$RECUIREMENT_PATH/$randomId") {
                                contentType(ContentType.Application.Json)
                                setBody(manager())
                                bearerAuth(createMockToken(orgnummer))
                            }
                        response.status shouldBe HttpStatusCode.NotFound
                        response.body<ApiError>().type shouldBe ErrorType.NOT_FOUND
                    }
                }

                it("PUT /requirement/{id} 400 invalid payload") {
                    withTestApplication {
                        texasHttpClientMock.defaultMocks(
                            consumer = DefaultOrganization.copy(ID = "0192:$orgnummer"),
                            scope = MASKINPORTEN_NL_SCOPE,
                        )
                        val requirementId = seedLinemanagerRequirement()
                        val response =
                            client.put("$API_V1_PATH/$RECUIREMENT_PATH/$requirementId") {
                                contentType(ContentType.Application.Json)
                                setBody("""{ "foo": "bar" }""")
                                bearerAuth(createMockToken(orgnummer))
                            }
                        response.status shouldBe HttpStatusCode.BadRequest
                        response.body<ApiError>().type shouldBe ErrorType.INVALID_FORMAT
                    }
                }

                it("PUT /requirement/{id} 403 when principal lacks Altinn resource access to orgnumber") {
                    withTestApplication {
                        pdlService.prepareGetPersonResponse(narmesteLederRelasjon.manager)
                        val requirementId = seedLinemanagerRequirement()
                        texasHttpClientMock.defaultMocks(
                            consumer = DefaultOrganization.copy(ID = "0192:000000000"), // mismatch org
                            scope = MASKINPORTEN_NL_SCOPE,
                        )
                        coEvery { pdpService.hasAccessToResource(any(), any(), any()) } returns false
                        fakeAaregClient.arbeidsForholdForIdent.put(sykmeldtFnr, listOf(orgnummer to orgnummer))
                        fakeAaregClient.arbeidsForholdForIdent.put(lederFnr, listOf(orgnummer to orgnummer))
                        val response =
                            client.put("$API_V1_PATH/$RECUIREMENT_PATH/$requirementId") {
                                contentType(ContentType.Application.Json)
                                setBody(narmesteLederRelasjon.manager)
                                bearerAuth(createMockToken("000000000"))
                            }
                        response.status shouldBe HttpStatusCode.Forbidden
                        response.body<ApiError>().type shouldBe ErrorType.MISSING_ALITINN_RESOURCE_ACCESS
                    }
                }
            }
            describe("GET /requirement") {
                it("GET /requirement should skip count query when all results fit in the current page") {
                    withTestApplication {
                        texasHttpClientMock.defaultMocks(
                            systemBrukerOrganisasjon = DefaultOrganization.copy(ID = "0192:${narmesteLederRelasjon.orgNumber.value}"),
                            scope = MASKINPORTEN_NL_SCOPE,
                        )
                        val requirementId = seedLinemanagerRequirement()
                        val requirement = narmesteLederService.getLinemanagerRequirementReadById(requirementId)
                        val pageSize = 10
                        val response =
                            client.get(
                                "$API_V1_PATH/$RECUIREMENT_PATH?orgNumber=${requirement.orgNumber.value}&createdAfter=${
                                    Instant.now().minusSeconds(60)
                                }&pageSize=$pageSize",
                            ) {
                                bearerAuth(createMockToken(narmesteLederRelasjon.orgNumber.value))
                            }
                        response.status shouldBe HttpStatusCode.OK
                        val body = response.body<LinemanagerRequirementCollection>()
                        body.meta.pageSize shouldBe pageSize
                        body.meta.size shouldBe 1
                        body.meta.total shouldBe 1L
                        body.linemanagerRequirements.first().id shouldBe requirementId

                        coVerify(exactly = 1) {
                            fakeRepo.findBehovByParameters(
                                orgNumber = requirement.orgNumber.value,
                                createdAfter = any(),
                                status =
                                listOf(
                                    BehovStatus.BEHOV_CREATED,
                                    BehovStatus.DIALOGPORTEN_STATUS_SET_REQUIRES_ATTENTION,
                                ),
                                limit = pageSize + 1, // +1 to check if there is more pages
                            )
                        }
                        coVerify(exactly = 0) {
                            fakeRepo.countBehovByParameters(
                                orgNumber = requirement.orgNumber.value,
                                createdAfter = any(),
                                status = listOf(
                                    BehovStatus.BEHOV_CREATED,
                                    BehovStatus.DIALOGPORTEN_STATUS_SET_REQUIRES_ATTENTION,
                                ),
                            )
                        }
                    }
                }

                it("GET /requirement should count total when the current page may have more results") {
                    withTestApplication {
                        texasHttpClientMock.defaultMocks(
                            systemBrukerOrganisasjon = DefaultOrganization.copy(ID = "0192:${narmesteLederRelasjon.orgNumber.value}"),
                            scope = MASKINPORTEN_NL_SCOPE,
                        )
                        val createdAfter = Instant.now().minusSeconds(60)
                        suspend fun insertRequirement(entity: NarmestelederBehovEntity) {
                            fakeRepo.insertNlBehov(entity)
                        }
                        insertRequirement(
                            nlBehovEntity().copy(
                                orgnummer = narmesteLederRelasjon.orgNumber.value,
                                hovedenhetOrgnummer = narmesteLederRelasjon.orgNumber.value,
                                behovStatus = BehovStatus.BEHOV_CREATED,
                                fornavn = "Ansatt",
                                etternavn = "En",
                            )
                        )
                        insertRequirement(
                            nlBehovEntity().copy(
                                orgnummer = narmesteLederRelasjon.orgNumber.value,
                                hovedenhetOrgnummer = narmesteLederRelasjon.orgNumber.value,
                                behovStatus = BehovStatus.DIALOGPORTEN_STATUS_SET_REQUIRES_ATTENTION,
                                fornavn = "Ansatt",
                                etternavn = "To",
                            )
                        )

                        val response = client.get(
                            "$API_V1_PATH/$RECUIREMENT_PATH?orgNumber=${narmesteLederRelasjon.orgNumber.value}&createdAfter=$createdAfter&pageSize=1",
                        ) {
                            bearerAuth(createMockToken(narmesteLederRelasjon.orgNumber.value))
                        }

                        response.status shouldBe HttpStatusCode.OK
                        val body = response.body<LinemanagerRequirementCollection>()
                        body.linemanagerRequirements.shouldHaveSize(1)
                        body.meta.size shouldBe 1
                        body.meta.pageSize shouldBe 1
                        body.meta.hasMore shouldBe true
                        body.meta.total shouldBe 2L

                        coVerify(exactly = 1) {
                            fakeRepo.countBehovByParameters(
                                orgNumber = narmesteLederRelasjon.orgNumber.value,
                                createdAfter = any(),
                                status = listOf(
                                    BehovStatus.BEHOV_CREATED,
                                    BehovStatus.DIALOGPORTEN_STATUS_SET_REQUIRES_ATTENTION,
                                ),
                            )
                        }
                    }
                }

                it("GET /requirement should return 400 for invalid orgNumber query parameter") {
                    withTestApplication {
                        texasHttpClientMock.defaultMocks(
                            systemBrukerOrganisasjon = DefaultOrganization.copy(ID = "0192:${narmesteLederRelasjon.orgNumber.value}"),
                            scope = MASKINPORTEN_NL_SCOPE,
                        )

                        val response =
                            client.get(
                                "$API_V1_PATH/$RECUIREMENT_PATH?orgNumber=12345678&createdAfter=${
                                    Instant.now().minusSeconds(60)
                                }",
                            ) {
                                bearerAuth(createMockToken(narmesteLederRelasjon.orgNumber.value))
                            }

                        response.status shouldBe HttpStatusCode.BadRequest
                        response.body<ApiError>().type shouldBe ErrorType.INVALID_FORMAT
                    }
                }

                it("GET /requirement should return 400 for non-digit orgNumber query parameter") {
                    withTestApplication {
                        texasHttpClientMock.defaultMocks(
                            systemBrukerOrganisasjon = DefaultOrganization.copy(ID = "0192:${narmesteLederRelasjon.orgNumber.value}"),
                            scope = MASKINPORTEN_NL_SCOPE,
                        )

                        val response =
                            client.get(
                                "$API_V1_PATH/$RECUIREMENT_PATH?orgNumber=12345678a&createdAfter=${
                                    Instant.now().minusSeconds(60)
                                }",
                            ) {
                                bearerAuth(createMockToken(narmesteLederRelasjon.orgNumber.value))
                            }

                        response.status shouldBe HttpStatusCode.BadRequest
                        response.body<ApiError>().type shouldBe ErrorType.INVALID_FORMAT
                    }
                }
            }
        }
    })
