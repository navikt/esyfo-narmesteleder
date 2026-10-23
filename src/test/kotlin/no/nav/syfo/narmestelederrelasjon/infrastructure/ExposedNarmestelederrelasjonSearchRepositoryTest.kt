package no.nav.syfo.narmestelederrelasjon.infrastructure

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import no.nav.syfo.TestDB
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonalIdentificationNumber
import no.nav.syfo.narmestelederrelasjon.application.search.NarmestelederrelasjonSearchQuery
import no.nav.syfo.narmestelederrelasjon.application.search.SearchName
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.date
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

class ExposedNarmestelederrelasjonSearchRepositoryTest :
    FunSpec({
        val fixedInstant = Instant.parse("2026-02-01T12:00:00Z")
        val fixedClock = Clock.fixed(fixedInstant, ZoneOffset.UTC)
        val repository = ExposedNarmestelederrelasjonSearchRepository(TestDB.exposedDatabase, fixedClock)
        val now = OffsetDateTime.ofInstant(fixedInstant, ZoneOffset.UTC)
        val orgNumber = OrganizationNumber("123456789")

        beforeTest {
            TestDB.clearAllData()
        }

        context("search") {
            test("returns active linemanager relations with names for both employee and manager") {
                val employeeFnr = "12345678910"
                val managerFnr = "10987654321"
                val narmestelederId = UUID.randomUUID()
                insertSearchPerson(employeeFnr, firstName = "Ola", middleName = "Mellom", lastName = "Nordmann")
                insertSearchPerson(managerFnr, firstName = "Kari", lastName = "Nordmann")
                insertSearchRelation(
                    employeeFnr = employeeFnr,
                    managerFnr = managerFnr,
                    email = "kari@example.com",
                    mobile = "90000000",
                    narmestelederId = narmestelederId,
                )

                val results = repository.search(
                    NarmestelederrelasjonSearchQuery(
                        orgNumber = orgNumber,
                        pageSize = 50,
                    ),
                )

                results.shouldHaveSize(1)
                val relation = results.single().linemanager
                relation.id shouldBe narmestelederId
                relation.orgNumber shouldBe orgNumber
                relation.activeFrom shouldBe now.minusDays(1).toInstant()
                relation.employee.nationalIdentificationNumber shouldBe PersonalIdentificationNumber(employeeFnr)
                relation.employee.name shouldBe SearchName(
                    firstName = "Ola",
                    middleName = "Mellom",
                    lastName = "Nordmann",
                )
                relation.manager.nationalIdentificationNumber shouldBe PersonalIdentificationNumber(managerFnr)
                relation.manager.name shouldBe SearchName(
                    firstName = "Kari",
                    middleName = null,
                    lastName = "Nordmann",
                )
                relation.manager.email shouldBe "kari@example.com"
                relation.manager.mobile shouldBe "90000000"
            }

            test("keeps active relations when person rows are missing and returns null names") {
                insertSearchRelation(
                    employeeFnr = "12345678910",
                    managerFnr = "10987654321",
                )

                val results = repository.search(
                    NarmestelederrelasjonSearchQuery(
                        orgNumber = orgNumber,
                        pageSize = 50,
                    ),
                )

                results.shouldHaveSize(1)
                val relation = results.single().linemanager
                relation.employee.name.shouldBeNull()
                relation.manager.name.shouldBeNull()
            }

            test("filters on orgnumber, manager fnr, activeTom null and activeFrom not in the future") {
                val expectedEmployeeFnr = "12345678910"
                val expectedManagerFnr = "10987654321"
                insertSearchRelation(
                    employeeFnr = expectedEmployeeFnr,
                    managerFnr = expectedManagerFnr,
                )
                insertSearchRelation(
                    employeeFnr = "12345678911",
                    managerFnr = expectedManagerFnr,
                    orgnummer = "987654321",
                )
                insertSearchRelation(
                    employeeFnr = "12345678912",
                    managerFnr = expectedManagerFnr,
                    aktivTom = now.minusDays(1),
                )
                insertSearchRelation(
                    employeeFnr = "12345678913",
                    managerFnr = expectedManagerFnr,
                    aktivFom = now.plusDays(1),
                )
                insertSearchRelation(
                    employeeFnr = "12345678914",
                    managerFnr = "10987654322",
                )

                val results = repository.search(
                    NarmestelederrelasjonSearchQuery(
                        orgNumber = orgNumber,
                        managerNationalIdentificationNumber = PersonalIdentificationNumber(expectedManagerFnr),
                        pageSize = 50,
                    ),
                )

                results.map { it.linemanager.employee.nationalIdentificationNumber.value } shouldBe listOf(expectedEmployeeFnr)
            }

            test("filters on employee national identification number") {
                val expectedEmployeeFnr = "12345678910"
                insertSearchRelation(
                    employeeFnr = expectedEmployeeFnr,
                    managerFnr = "10987654321",
                )
                insertSearchRelation(
                    employeeFnr = "12345678911",
                    managerFnr = "10987654321",
                )

                val results = repository.search(
                    NarmestelederrelasjonSearchQuery(
                        orgNumber = orgNumber,
                        employeeNationalIdentificationNumber = PersonalIdentificationNumber(expectedEmployeeFnr),
                        pageSize = 50,
                    ),
                )

                results.map { it.linemanager.employee.nationalIdentificationNumber.value } shouldBe listOf(expectedEmployeeFnr)
            }

            test("searches names for either employee or manager") {
                val employeeFnr = "12345678910"
                val managerFnr = "10987654321"
                val otherEmployeeFnr = "12345678911"
                val otherManagerFnr = "10987654322"
                insertSearchPerson(employeeFnr, firstName = "Ola", lastName = "Nordmann")
                insertSearchPerson(managerFnr, firstName = "Kari", lastName = "Lunde")
                insertSearchPerson(otherEmployeeFnr, firstName = "Anne", lastName = "Hansen")
                insertSearchPerson(otherManagerFnr, firstName = "Ola", lastName = "Bjerke")
                insertSearchRelation(employeeFnr = employeeFnr, managerFnr = managerFnr)
                insertSearchRelation(employeeFnr = otherEmployeeFnr, managerFnr = otherManagerFnr)

                val results = repository.search(
                    NarmestelederrelasjonSearchQuery(
                        orgNumber = orgNumber,
                        text = "ola",
                        pageSize = 50,
                    ),
                )

                results.map { it.linemanager.employee.nationalIdentificationNumber.value } shouldBe
                    listOf(otherEmployeeFnr, employeeFnr)
            }

            test("treats LIKE wildcard characters in name searches as literals") {
                val employeeFnr = "12345678910"
                insertSearchPerson(employeeFnr, firstName = "Ola", lastName = "Nordmann")
                insertSearchRelation(employeeFnr = employeeFnr, managerFnr = "10987654321")

                val results = repository.search(
                    NarmestelederrelasjonSearchQuery(
                        orgNumber = orgNumber,
                        text = "%",
                        pageSize = 50,
                    ),
                )

                results.shouldHaveSize(0)
            }

            test("searches national identification numbers for either employee or manager") {
                val nationalIdentificationNumber = PersonalIdentificationNumber("12345678910")
                val employeeFnr = "12345678911"
                val managerFnr = "10987654321"
                insertSearchRelation(employeeFnr = nationalIdentificationNumber.value, managerFnr = managerFnr)
                insertSearchRelation(employeeFnr = employeeFnr, managerFnr = nationalIdentificationNumber.value)

                val results = repository.search(
                    NarmestelederrelasjonSearchQuery(
                        orgNumber = orgNumber,
                        nationalIdentificationNumber = nationalIdentificationNumber,
                        pageSize = 50,
                    ),
                )

                results.map { it.linemanager.employee.nationalIdentificationNumber.value } shouldBe
                    listOf(nationalIdentificationNumber.value, employeeFnr)
            }

            test("filters on whether an employee has an active sick leave") {
                val activeEmployeeFnr = "12345678910"
                val expiredEmployeeFnr = "12345678911"
                val revokedEmployeeFnr = "12345678912"
                val revokedInFutureEmployeeFnr = "12345678913"
                val noSickLeaveEmployeeFnr = "12345678914"
                listOf(
                    activeEmployeeFnr,
                    expiredEmployeeFnr,
                    revokedEmployeeFnr,
                    revokedInFutureEmployeeFnr,
                    noSickLeaveEmployeeFnr,
                ).forEach {
                    insertSearchRelation(employeeFnr = it, managerFnr = "10987654321")
                }
                val today = now.toLocalDate()
                insertSendtSykmelding(fnr = activeEmployeeFnr, tom = today)
                insertSendtSykmelding(
                    fnr = noSickLeaveEmployeeFnr,
                    orgnummer = "987654321",
                    tom = today.plusDays(1),
                )
                insertSendtSykmelding(fnr = expiredEmployeeFnr, tom = today.minusDays(1))
                insertSendtSykmelding(
                    fnr = revokedEmployeeFnr,
                    tom = today.plusDays(1),
                    revokedDate = today.minusDays(1),
                )
                insertSendtSykmelding(
                    fnr = revokedInFutureEmployeeFnr,
                    tom = today.plusDays(1),
                    revokedDate = today,
                )

                val activeResults = repository.search(
                    NarmestelederrelasjonSearchQuery(
                        orgNumber = orgNumber,
                        hasActiveSickLeave = true,
                        pageSize = 50,
                    ),
                )
                val inactiveResults = repository.search(
                    NarmestelederrelasjonSearchQuery(
                        orgNumber = orgNumber,
                        hasActiveSickLeave = false,
                        pageSize = 50,
                    ),
                )
                val unfilteredResults = repository.search(
                    NarmestelederrelasjonSearchQuery(
                        orgNumber = orgNumber,
                        pageSize = 50,
                    ),
                )

                activeResults.map { it.linemanager.employee.nationalIdentificationNumber.value } shouldBe
                    listOf(activeEmployeeFnr, revokedInFutureEmployeeFnr)
                inactiveResults.map { it.linemanager.employee.nationalIdentificationNumber.value } shouldBe
                    listOf(expiredEmployeeFnr, revokedEmployeeFnr, noSickLeaveEmployeeFnr)
                unfilteredResults.map { it.linemanager.employee.nationalIdentificationNumber.value } shouldBe
                    listOf(
                        activeEmployeeFnr,
                        expiredEmployeeFnr,
                        revokedEmployeeFnr,
                        revokedInFutureEmployeeFnr,
                        noSickLeaveEmployeeFnr,
                    )
            }

            test("sorts employee names case-insensitively by first name, last name, and relation id") {
                val benteId = insertSearchRelation(
                    employeeFnr = "12345678910",
                    managerFnr = "10987654321",
                )
                val firstAdaId = insertSearchRelation(
                    employeeFnr = "12345678911",
                    managerFnr = "10987654321",
                )
                val secondAdaId = insertSearchRelation(
                    employeeFnr = "12345678912",
                    managerFnr = "10987654321",
                )
                val adaSolId = insertSearchRelation(
                    employeeFnr = "12345678913",
                    managerFnr = "10987654321",
                )
                val missingPersonId = insertSearchRelation(
                    employeeFnr = "12345678914",
                    managerFnr = "10987654321",
                )
                insertSearchPerson("12345678910", firstName = "Bente", lastName = "Andersen")
                insertSearchPerson("12345678911", firstName = "Ada", lastName = "Lund")
                insertSearchPerson("12345678912", firstName = "ada", lastName = "lund")
                insertSearchPerson("12345678913", firstName = "ADA", lastName = "Sol")

                val results = repository.search(
                    NarmestelederrelasjonSearchQuery(
                        orgNumber = orgNumber,
                        pageSize = 50,
                    ),
                )

                results.map { it.cursor.id } shouldBe
                    listOf(firstAdaId, secondAdaId, adaSolId, benteId, missingPersonId)
            }

            test("paginates stably through equal and missing employee names") {
                val benteId = insertSearchRelation(
                    employeeFnr = "12345678910",
                    managerFnr = "10987654321",
                )
                val firstAdaId = insertSearchRelation(
                    employeeFnr = "12345678911",
                    managerFnr = "10987654321",
                )
                val secondAdaId = insertSearchRelation(
                    employeeFnr = "12345678912",
                    managerFnr = "10987654321",
                )
                val adaWithoutLastNameId = insertSearchRelation(
                    employeeFnr = "12345678913",
                    managerFnr = "10987654321",
                )
                val secondAdaWithoutLastNameId = insertSearchRelation(
                    employeeFnr = "12345678914",
                    managerFnr = "10987654321",
                )
                val firstMissingPersonId = insertSearchRelation(
                    employeeFnr = "12345678915",
                    managerFnr = "10987654321",
                )
                val secondMissingPersonId = insertSearchRelation(
                    employeeFnr = "12345678916",
                    managerFnr = "10987654321",
                )
                insertSearchPerson("12345678910", firstName = "Bente", lastName = "Andersen")
                insertSearchPerson("12345678911", firstName = "Ada", lastName = "Lund")
                insertSearchPerson("12345678912", firstName = "ada", lastName = "lund")
                insertSearchPerson("12345678913", firstName = "Ada")
                insertSearchPerson("12345678914", firstName = "ada")

                val firstPage = repository.search(
                    NarmestelederrelasjonSearchQuery(
                        orgNumber = orgNumber,
                        pageSize = 2,
                    ),
                )
                val secondPage = repository.search(
                    NarmestelederrelasjonSearchQuery(
                        orgNumber = orgNumber,
                        pageSize = 2,
                        cursor = firstPage.take(2).last().cursor,
                    ),
                )
                val thirdPage = repository.search(
                    NarmestelederrelasjonSearchQuery(
                        orgNumber = orgNumber,
                        pageSize = 2,
                        cursor = secondPage.take(2).last().cursor,
                    ),
                )

                val fourthPage = repository.search(
                    NarmestelederrelasjonSearchQuery(
                        orgNumber = orgNumber,
                        pageSize = 2,
                        cursor = thirdPage.take(2).last().cursor,
                    ),
                )

                (
                    firstPage.take(2).map { it.cursor.id } +
                        secondPage.take(2).map { it.cursor.id } +
                        thirdPage.take(2).map { it.cursor.id } +
                        fourthPage.map { it.cursor.id }
                    ) shouldBe listOf(
                    firstAdaId,
                    secondAdaId,
                    adaWithoutLastNameId,
                    secondAdaWithoutLastNameId,
                    benteId,
                    firstMissingPersonId,
                    secondMissingPersonId,
                )
            }
        }
    })

private fun insertSearchPerson(
    fnr: String,
    firstName: String? = null,
    middleName: String? = null,
    lastName: String? = null,
) = insertPerson(fnr, firstName = firstName, middleName = middleName, lastName = lastName, birthDate = LocalDate.parse("1990-01-01"))

private fun insertSearchRelation(
    employeeFnr: String,
    managerFnr: String,
    orgnummer: String = "123456789",
    aktivFom: OffsetDateTime = OffsetDateTime.parse("2026-01-31T12:00:00Z"),
    aktivTom: OffsetDateTime? = null,
    email: String = "leder@example.com",
    mobile: String = "99999999",
    narmestelederId: UUID = UUID.randomUUID(),
): Int = insertRelation(
    narmestelederId = narmestelederId,
    employeeIdent = employeeFnr,
    managerIdent = managerFnr,
    organizationNumber = orgnummer,
    activeFrom = aktivFom,
    activeTo = aktivTom,
    email = email,
    mobile = mobile,
    employerAdvancesPay = true,
)

private object SendtSykmeldingSeedTable : Table("sendt_sykmelding") {
    val sykmeldingId = javaUUID("sykmelding_id")
    val orgnummer = varchar("orgnummer", 9)
    val syketilfelleStartDato = date("syketilfelle_startdato").nullable()
    val fnr = text("fnr")
    val fom = date("fom")
    val tom = date("tom")
    val revokedDate = date("revoked_date").nullable()
}

private fun insertSendtSykmelding(
    fnr: String,
    orgnummer: String = "123456789",
    tom: LocalDate,
    revokedDate: LocalDate? = null,
) {
    transaction(TestDB.exposedDatabase) {
        SendtSykmeldingSeedTable.insert {
            it[sykmeldingId] = UUID.randomUUID()
            it[SendtSykmeldingSeedTable.orgnummer] = orgnummer
            it[syketilfelleStartDato] = tom.minusDays(10)
            it[SendtSykmeldingSeedTable.fnr] = fnr
            it[fom] = tom.minusDays(20)
            it[SendtSykmeldingSeedTable.tom] = tom
            it[SendtSykmeldingSeedTable.revokedDate] = revokedDate
        }
    }
}
