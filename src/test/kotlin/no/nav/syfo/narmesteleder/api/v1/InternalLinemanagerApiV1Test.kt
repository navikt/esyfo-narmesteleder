package no.nav.syfo.narmesteleder.api.v1

import DefaultOrganization
import createMockToken
import defaultMocks
import io.kotest.matchers.shouldBe
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.mockk.coEvery
import io.mockk.coVerify
import no.nav.syfo.application.api.INTERNAL_API_V1_PATH
import no.nav.syfo.narmesteleder.domain.LinemanagerStatistics
import no.nav.syfo.texas.MASKINPORTEN_NL_SCOPE

class InternalLinemanagerApiV1Test :
    LinemanagerApiV1TestBase({
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
    })
