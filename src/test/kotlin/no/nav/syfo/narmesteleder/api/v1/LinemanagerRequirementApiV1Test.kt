package no.nav.syfo.narmesteleder.api.v1

import DefaultOrganization
import createMockToken
import defaultMocks
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.mockk.coEvery
import io.mockk.coVerify
import nlBehovEntity
import no.nav.syfo.altinn.pdp.client.Decision
import no.nav.syfo.application.api.API_V1_PATH
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
                            client.get("$API_V1_PATH/$REQUIREMENT_PATH/$requirementId") {
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
                            client.get("$API_V1_PATH/$REQUIREMENT_PATH/$randomId") {
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
                        coEvery { pdpService.accessDecisionForResource(any(), any(), any()) } returns Decision.Deny
                        val response =
                            client.get("$API_V1_PATH/$REQUIREMENT_PATH/$requirementId") {
                                bearerAuth(createMockToken("999999999"))
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
                                "$API_V1_PATH/$REQUIREMENT_PATH?orgNumber=${requirement.orgNumber.value}&createdAfter=${
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
                            "$API_V1_PATH/$REQUIREMENT_PATH?orgNumber=${narmesteLederRelasjon.orgNumber.value}&createdAfter=$createdAfter&pageSize=1",
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
                                "$API_V1_PATH/$REQUIREMENT_PATH?orgNumber=12345678&createdAfter=${
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
                                "$API_V1_PATH/$REQUIREMENT_PATH?orgNumber=12345678a&createdAfter=${
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
