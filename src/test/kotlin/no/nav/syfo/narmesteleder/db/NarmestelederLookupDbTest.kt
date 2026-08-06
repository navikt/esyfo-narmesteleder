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

class NarmestelederLookupDbTest :
    DescribeSpec({
        val lookupDb = NarmestelederLookupDb(TestDB.exposedDatabase)
        val sykmeldtFnr = PersonalIdentificationNumber("12345678901")
        val orgnummer = OrganizationNumber("123456789")

        beforeTest {
            TestDB.clearNarmestelederData()
        }

        fun insertNarmesteleder(
            lederFnr: String = "10987654321",
            epost: String = "leder@example.com",
            sykmeldt: String = sykmeldtFnr.value,
            org: String = orgnummer.value,
            fom: OffsetDateTime,
            tom: OffsetDateTime? = null,
        ) {
            transaction(TestDB.exposedDatabase) {
                NarmestelederTable.insert {
                    it[narmestelederId] = UUID.randomUUID()
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

        describe("findActiveNarmesteledere") {
            it("returns only active relations for the given sykmeldt and organization") {
                val aktivFom = OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)
                insertNarmesteleder(fom = aktivFom)
                insertNarmesteleder(
                    lederFnr = "10987654322",
                    fom = aktivFom.minusYears(1),
                    tom = aktivFom,
                )
                insertNarmesteleder(lederFnr = "10987654323", org = "987654321", fom = aktivFom)
                insertNarmesteleder(lederFnr = "10987654324", sykmeldt = "12345678902", fom = aktivFom)

                val result = lookupDb.findActiveNarmesteledere(sykmeldtFnr, orgnummer)

                result.size shouldBe 1
                result.first().narmestelederFnr shouldBe PersonalIdentificationNumber("10987654321")
                result.first().narmestelederEpost shouldBe "leder@example.com"
                result.first().aktivFom shouldBe aktivFom.toInstant()
            }

            it("orders multiple active relations by newest aktiv_fom first") {
                val aktivFom = OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)
                insertNarmesteleder(lederFnr = "10987654321", fom = aktivFom.minusMonths(1))
                insertNarmesteleder(lederFnr = "10987654322", fom = aktivFom)

                val result = lookupDb.findActiveNarmesteledere(sykmeldtFnr, orgnummer)

                result.map { it.narmestelederFnr.value } shouldBe listOf("10987654322", "10987654321")
            }

            it("returns an empty list when no relation exists") {
                lookupDb.findActiveNarmesteledere(sykmeldtFnr, orgnummer) shouldBe emptyList()
            }
        }
    })
