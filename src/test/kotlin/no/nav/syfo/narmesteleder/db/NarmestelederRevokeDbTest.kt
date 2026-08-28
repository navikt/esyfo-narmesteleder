package no.nav.syfo.narmesteleder.db

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import no.nav.syfo.TestDB
import no.nav.syfo.narmesteleder.domain.OrganizationNumber
import no.nav.syfo.narmesteleder.domain.PersonalIdentificationNumber
import no.nav.syfo.narmesteleder.exposed.NarmestelederTable
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

class NarmestelederRevokeDbTest :
    DescribeSpec({
        val revokeDb = NarmestelederRevokeDb(TestDB.exposedDatabase)
        val sykmeldtFnr = PersonalIdentificationNumber("12345678901")
        val lederFnr = PersonalIdentificationNumber("10987654321")
        val orgnummer = OrganizationNumber("123456789")
        val aktivFom = OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)

        beforeTest {
            TestDB.clearNarmestelederData()
        }

        fun insertNarmesteleder(
            id: UUID = UUID.randomUUID(),
            sykmeldt: String = sykmeldtFnr.value,
            leder: String = lederFnr.value,
            org: String = orgnummer.value,
            tom: OffsetDateTime? = null,
        ): UUID {
            transaction(TestDB.exposedDatabase) {
                NarmestelederTable.insert {
                    it[narmestelederId] = id
                    it[NarmestelederTable.orgnummer] = org
                    it[NarmestelederTable.sykmeldtFnr] = sykmeldt
                    it[narmestelederFnr] = leder
                    it[narmestelederTelefonnummer] = "99887766"
                    it[narmestelederEpost] = "leder@example.com"
                    it[NarmestelederTable.aktivFom] = aktivFom
                    it[aktivTom] = tom
                }
            }
            return id
        }

        describe("findByNarmestelederId") {
            it("returns the active relation with both parties and organization") {
                val id = insertNarmesteleder()

                val result = revokeDb.findByNarmestelederId(id)

                result shouldBe RevokableNarmestelederEntity(
                    narmestelederId = id,
                    employeeIdentificationNumber = sykmeldtFnr,
                    managerIdentificationNumber = lederFnr,
                    orgNumber = orgnummer,
                    isActive = true,
                )
            }

            it("returns the relation with isActive false when aktiv_tom is set") {
                val id = insertNarmesteleder(tom = aktivFom.plusDays(1))

                revokeDb.findByNarmestelederId(id)?.isActive shouldBe false
            }

            it("returns null when no relation matches the id") {
                insertNarmesteleder()

                revokeDb.findByNarmestelederId(UUID.randomUUID()) shouldBe null
            }

            it("does not return a relation belonging to another id") {
                val other = insertNarmesteleder(sykmeldt = "12345678902", leder = "10987654322")
                val wanted = insertNarmesteleder()

                revokeDb.findByNarmestelederId(other)?.employeeIdentificationNumber shouldBe
                    PersonalIdentificationNumber("12345678902")
                revokeDb.findByNarmestelederId(wanted)?.employeeIdentificationNumber shouldBe sykmeldtFnr
            }
        }
    })
