package no.nav.syfo.narmesteleder.service.validators

import faker
import io.kotest.assertions.throwables.shouldNotThrow
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.doubles.shouldBeExactly
import io.kotest.matchers.shouldBe
import linemanager
import linemanagerRevoke
import no.nav.syfo.application.api.ErrorType
import no.nav.syfo.application.exception.ApiErrorException
import no.nav.syfo.application.metric.METRICS_NS
import no.nav.syfo.application.metric.METRICS_REGISTRY
import no.nav.syfo.narmesteleder.domain.PersonalIdentificationNumber
import no.nav.syfo.pdl.Person
import no.nav.syfo.pdl.client.Navn

class NameValidatorTest :
    DescribeSpec({
        fun person(lastName: String, fnr: String): Person = Person(
            name = Navn(
                fornavn = faker.name().firstName(),
                mellomnavn = null,
                etternavn = lastName,
            ),
            nationalIdentificationNumber = PersonalIdentificationNumber(fnr),
        )

        fun personWithParallelLastNames(lastNames: List<String>, fnr: String): Person = Person(
            name = Navn(
                fornavn = faker.name().firstName(),
                mellomnavn = null,
                etternavn = lastNames.first(),
            ),
            names = lastNames.map { lastName ->
                Navn(
                    fornavn = faker.name().firstName(),
                    mellomnavn = null,
                    etternavn = lastName,
                )
            },
            nationalIdentificationNumber = PersonalIdentificationNumber(fnr),
        )

        describe("validateLinemanagerLastName") {
            it("should throw BadRequestException if lastname of PdlPerson and manager does not match") {
                val linemanager = linemanager()
                val manager = person(
                    lastName = linemanager.manager.lastName.reversed(),
                    fnr = linemanager.manager.nationalIdentificationNumber.value,
                )

                val exception = shouldThrow<ApiErrorException.BadRequestException> {
                    NameValidator.validateLinemanagerLastName(manager, linemanager)
                }

                exception.message shouldBe "Last name for linemanager does not correspond with registered value for the given national identification number"
                exception.type shouldBe ErrorType.LINEMANAGER_NAME_NATIONAL_IDENTIFICATION_NUMBER_MISMATCH
            }

            it("should not throw BadRequestException if lastname of PdlPerson and manager matches case insensitively") {
                val linemanager = linemanager()
                val manager = person(
                    lastName = linemanager.manager.lastName.lowercase(),
                    fnr = linemanager.manager.nationalIdentificationNumber.value,
                )

                shouldNotThrow<ApiErrorException.BadRequestException> {
                    NameValidator.validateLinemanagerLastName(manager, linemanager)
                }
            }
        }

        describe("validateEmployeeLastName with Linemanager") {
            it("should not throw when employee last name matches case insensitively") {
                val linemanager = linemanager()
                val employee = person(
                    lastName = linemanager.lastName.lowercase(),
                    fnr = linemanager.employeeIdentificationNumber.value,
                )

                shouldNotThrow<ApiErrorException.BadRequestException> {
                    NameValidator.validateEmployeeLastName(employee, linemanager)
                }
            }

            it("should not throw when employee last name matches exactly") {
                val linemanager = linemanager()
                val employee = person(
                    lastName = linemanager.lastName,
                    fnr = linemanager.employeeIdentificationNumber.value,
                )

                shouldNotThrow<ApiErrorException.BadRequestException> {
                    NameValidator.validateEmployeeLastName(employee, linemanager)
                }
            }

            it("should throw BadRequestException when employee last name does not match") {
                val linemanager = linemanager()
                val employee = person(
                    lastName = linemanager.lastName.reversed(),
                    fnr = linemanager.employeeIdentificationNumber.value,
                )

                val exception = shouldThrow<ApiErrorException.BadRequestException> {
                    NameValidator.validateEmployeeLastName(employee, linemanager)
                }

                exception.message shouldBe "Last name for employee on sick leave does not correspond with registered value for the given national identification number"
                exception.type shouldBe ErrorType.EMPLOYEE_NAME_NATIONAL_IDENTIFICATION_NUMBER_MISMATCH
            }

            it("should not throw when employee last name matches one of the parallel last names from PDL") {
                val linemanager = linemanager()
                val employee = personWithParallelLastNames(
                    lastNames = listOf(
                        linemanager.lastName.reversed(),
                        linemanager.lastName,
                    ),
                    fnr = linemanager.employeeIdentificationNumber.value,
                )
                val attemptedBefore = parallelNamesValidationCount(result = RESULT_ATTEMPTED)
                val successBefore = parallelNamesValidationCount(result = RESULT_SUCCESS)
                val failedBefore = parallelNamesValidationCount(result = RESULT_FAILED)

                shouldNotThrow<ApiErrorException.BadRequestException> {
                    NameValidator.validateEmployeeLastName(employee, linemanager)
                }

                parallelNamesValidationCount(result = RESULT_ATTEMPTED) shouldBeExactly attemptedBefore + 1.0
                parallelNamesValidationCount(result = RESULT_SUCCESS) shouldBeExactly successBefore + 1.0
                parallelNamesValidationCount(result = RESULT_FAILED) shouldBeExactly failedBefore
            }

            it("should throw when employee last name matches none of the parallel last names from PDL") {
                val linemanager = linemanager()
                val employee = personWithParallelLastNames(
                    lastNames = listOf(
                        linemanager.lastName.reversed(),
                        "${linemanager.lastName}x",
                    ),
                    fnr = linemanager.employeeIdentificationNumber.value,
                )
                val attemptedBefore = parallelNamesValidationCount(result = RESULT_ATTEMPTED)
                val successBefore = parallelNamesValidationCount(result = RESULT_SUCCESS)
                val failedBefore = parallelNamesValidationCount(result = RESULT_FAILED)

                shouldThrow<ApiErrorException.BadRequestException> {
                    NameValidator.validateEmployeeLastName(employee, linemanager)
                }

                parallelNamesValidationCount(result = RESULT_ATTEMPTED) shouldBeExactly attemptedBefore + 1.0
                parallelNamesValidationCount(result = RESULT_SUCCESS) shouldBeExactly successBefore
                parallelNamesValidationCount(result = RESULT_FAILED) shouldBeExactly failedBefore + 1.0
            }
        }

        describe("validateEmployeeLastName with LinemanagerRevoke") {
            it("should not throw when employee last name matches case insensitively") {
                val linemanagerRevoke = linemanagerRevoke()
                val employee = person(
                    lastName = linemanagerRevoke.lastName.lowercase(),
                    fnr = linemanagerRevoke.employeeIdentificationNumber.value,
                )

                shouldNotThrow<ApiErrorException.BadRequestException> {
                    NameValidator.validateEmployeeLastName(employee, linemanagerRevoke)
                }
            }

            it("should throw BadRequestException when employee last name does not match") {
                val linemanagerRevoke = linemanagerRevoke()
                val employee = person(
                    lastName = linemanagerRevoke.lastName.reversed(),
                    fnr = linemanagerRevoke.employeeIdentificationNumber.value,
                )

                val exception = shouldThrow<ApiErrorException.BadRequestException> {
                    NameValidator.validateEmployeeLastName(employee, linemanagerRevoke)
                }

                exception.message shouldBe "Last name for employee on sick leave does not correspond with registered value for the given national identification number"
                exception.type shouldBe ErrorType.EMPLOYEE_NAME_NATIONAL_IDENTIFICATION_NUMBER_MISMATCH
            }
        }
    })

private const val PARALLEL_NAMES_VALIDATION_TOTAL = "${METRICS_NS}_parallel_names_validation_total"
private const val RESULT_TAG = "result"
private const val RESULT_ATTEMPTED = "attempted"
private const val RESULT_SUCCESS = "success"
private const val RESULT_FAILED = "failed"

private fun parallelNamesValidationCount(result: String): Double = METRICS_REGISTRY.find(PARALLEL_NAMES_VALIDATION_TOTAL)
    .tag(RESULT_TAG, result)
    .counter()
    ?.count() ?: 0.0
