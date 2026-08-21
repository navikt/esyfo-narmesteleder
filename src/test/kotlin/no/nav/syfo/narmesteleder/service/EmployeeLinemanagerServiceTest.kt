package no.nav.syfo.narmesteleder.service

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import no.nav.syfo.narmesteleder.domain.EmployeeLinemanagerCollection
import no.nav.syfo.narmesteleder.domain.EmployeeLinemanagerQuery
import no.nav.syfo.narmesteleder.domain.EmployeeLinemanagerRead
import no.nav.syfo.narmesteleder.domain.OrganizationNumber
import no.nav.syfo.narmesteleder.domain.PersonalIdentificationNumber
import no.nav.syfo.narmesteleder.exposed.IEmployeeLinemanagerRepository
import java.time.Instant
import java.util.UUID

class EmployeeLinemanagerServiceTest :
    DescribeSpec({
        val repository = mockk<IEmployeeLinemanagerRepository>()
        val service = EmployeeLinemanagerService(repository)
        val employee = PersonalIdentificationNumber("12345678910")
        val orgNumber = OrganizationNumber("123456789")

        beforeTest {
            clearAllMocks(currentThreadOnly = true)
        }

        fun linemanager(id: UUID = UUID.randomUUID()) = EmployeeLinemanagerRead(
            id = id,
            orgNumber = orgNumber,
            activeFrom = Instant.parse("2026-02-01T12:00:00Z"),
            name = null,
            emailAddresses = listOf("leder@example.com"),
            mobile = "99999999",
        )

        describe("findActiveLinemanagersForEmployee") {
            it("passes the employee and a null organization filter to the repository") {
                coEvery {
                    repository.findActiveForEmployee(EmployeeLinemanagerQuery(employee))
                } returns emptyList()

                service.findActiveLinemanagersForEmployee(employee, null)

                coVerify(exactly = 1) {
                    repository.findActiveForEmployee(
                        EmployeeLinemanagerQuery(
                            employeeNationalIdentificationNumber = employee,
                            orgNumber = null,
                        ),
                    )
                }
            }

            it("passes the organization filter unchanged to the repository") {
                coEvery {
                    repository.findActiveForEmployee(EmployeeLinemanagerQuery(employee, orgNumber))
                } returns emptyList()

                service.findActiveLinemanagersForEmployee(employee, orgNumber)

                coVerify(exactly = 1) {
                    repository.findActiveForEmployee(EmployeeLinemanagerQuery(employee, orgNumber))
                }
            }

            it("preserves the repository result order") {
                val first = linemanager()
                val second = linemanager()
                coEvery {
                    repository.findActiveForEmployee(EmployeeLinemanagerQuery(employee))
                } returns listOf(first, second)

                val result = service.findActiveLinemanagersForEmployee(employee, null)

                result shouldBe EmployeeLinemanagerCollection(listOf(first, second))
            }

            it("wraps an empty repository result") {
                coEvery {
                    repository.findActiveForEmployee(EmployeeLinemanagerQuery(employee))
                } returns emptyList()

                val result = service.findActiveLinemanagersForEmployee(employee, null)

                result shouldBe EmployeeLinemanagerCollection(emptyList())
            }
        }
    })
