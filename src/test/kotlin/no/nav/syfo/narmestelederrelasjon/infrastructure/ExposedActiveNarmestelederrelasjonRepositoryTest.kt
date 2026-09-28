package no.nav.syfo.narmestelederrelasjon.infrastructure

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import no.nav.syfo.TestDB
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonIdent
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

class ExposedActiveNarmestelederrelasjonRepositoryTest :
    DescribeSpec({
        val lookupDb = ExposedActiveNarmestelederrelasjonRepository(TestDB.exposedDatabase)
        val sykmeldtFnr = PersonIdent("12345678901")
        val orgnummer = OrganizationNumber("123456789")

        beforeTest {
            TestDB.clearNarmestelederData()
        }

        fun insertNarmesteleder(
            id: UUID = UUID.randomUUID(),
            lederFnr: String = "10987654321",
            epost: String = "leder@example.com",
            sykmeldt: String = sykmeldtFnr.value,
            org: String = orgnummer.value,
            fom: OffsetDateTime,
            tom: OffsetDateTime? = null,
        ) {
            transaction(TestDB.exposedDatabase) {
                NarmestelederTable.insert {
                    it[narmestelederId] = id
                    it[NarmestelederTable.orgnummer] = org
                    it[NarmestelederTable.sykmeldtFnr] = sykmeldt
                    it[NarmestelederTable.narmestelederFnr] = lederFnr
                    it[narmestelederTelefonnummer] = "99887766"
                    it[narmestelederEpost] = epost
                    it[aktivFom] = fom
                    it[aktivTom] = tom
                }
            }
        }

        describe("findActive") {
            it("returns only active relations for the given sykmeldt and organization") {
                val aktivFom = OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)
                val narmestelederId = UUID.fromString("4ffc41ed-75df-4802-9867-b5262783da5d")
                insertNarmesteleder(id = narmestelederId, fom = aktivFom)
                insertNarmesteleder(
                    lederFnr = "10987654322",
                    fom = aktivFom.minusYears(1),
                    tom = aktivFom,
                )
                insertNarmesteleder(lederFnr = "10987654323", org = "987654321", fom = aktivFom)
                insertNarmesteleder(lederFnr = "10987654324", sykmeldt = "12345678902", fom = aktivFom)

                val result = lookupDb.findActive(sykmeldtFnr, orgnummer)

                result.size shouldBe 1
                result.first().id shouldBe narmestelederId
                result.first().managerIdent shouldBe PersonIdent("10987654321")
                result.first().managerEmail shouldBe "leder@example.com"
                result.first().activeFrom shouldBe aktivFom.toInstant()
            }

            it("orders multiple active relations by newest aktiv_fom first") {
                val aktivFom = OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)
                insertNarmesteleder(lederFnr = "10987654321", fom = aktivFom.minusMonths(1))
                insertNarmesteleder(lederFnr = "10987654322", fom = aktivFom)

                val result = lookupDb.findActive(sykmeldtFnr, orgnummer)

                result.map { it.managerIdent.value } shouldBe listOf("10987654322", "10987654321")
            }

            it("orders relations with the same aktiv_fom by id descending") {
                val aktivFom = OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)
                insertNarmesteleder(
                    id = UUID.fromString("00000000-0000-0000-0000-000000000001"),
                    lederFnr = "10987654321",
                    fom = aktivFom,
                )
                insertNarmesteleder(
                    id = UUID.fromString("00000000-0000-0000-0000-000000000002"),
                    lederFnr = "10987654322",
                    fom = aktivFom,
                )

                lookupDb.findActive(sykmeldtFnr, orgnummer).map { it.managerIdent.value } shouldBe
                    listOf("10987654322", "10987654321")
            }

            it("returns an empty list when no relation exists") {
                lookupDb.findActive(sykmeldtFnr, orgnummer) shouldBe emptyList()
            }
        }
    })
