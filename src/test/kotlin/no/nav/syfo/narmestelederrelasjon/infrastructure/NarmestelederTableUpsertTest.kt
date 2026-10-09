package no.nav.syfo.narmestelederrelasjon.infrastructure

import faker
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import no.nav.syfo.TestDB
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonIdent
import no.nav.syfo.narmestelederrelasjon.application.ValidLeesahNarmestelederrelasjon
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.SchemaUtils.checkMappingConsistence
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

class NarmestelederTableUpsertTest :
    FunSpec({
        beforeTest {
            TestDB.clearNarmestelederData()
        }

        test("NarmestelederTable mapping matches the database schema") {
            transaction(TestDB.exposedDatabase) {
                checkMappingConsistence(NarmestelederTable, withLogs = true) shouldBe emptyList()
            }
        }

        context("NarmestelederTable.upsertFromLeesah") {
            test("inserts every field of a new relation") {
                val relasjon = relasjon()

                upsert(relasjon)

                val row = rows(relasjon.narmestelederId).single()
                row.toRelasjon() shouldBe relasjon
            }

            test("on conflict updates mutable fields and keeps identity, fnr and created") {
                val original = relasjon()
                upsert(original)
                val before = rows(original.narmestelederId).single()

                val replayed = relasjon(narmestelederId = original.narmestelederId).copy(
                    aktivFom = original.aktivFom.minusDays(1),
                    aktivTom = LocalDate.of(2025, 1, 31),
                    arbeidsgiverForskutterer = false,
                )
                upsert(replayed)

                val after = rows(original.narmestelederId).single()
                after[NarmestelederTable.id] shouldBe before[NarmestelederTable.id]
                after[NarmestelederTable.created].toInstant() shouldBe before[NarmestelederTable.created].toInstant()
                after.toRelasjon() shouldBe replayed.copy(
                    sykmeldtFnr = original.sykmeldtFnr,
                    narmestelederFnr = original.narmestelederFnr,
                )
            }

            test("on conflict clears nullable fields") {
                val original = relasjon(aktivTom = LocalDate.of(2024, 12, 31), arbeidsgiverForskutterer = true)
                upsert(original)

                upsert(original.copy(aktivTom = null, arbeidsgiverForskutterer = null))

                val row = rows(original.narmestelederId).single()
                row[NarmestelederTable.aktivTom] shouldBe null
                row[NarmestelederTable.arbeidsgiverForskutterer] shouldBe null
            }
        }
    })

private fun relasjon(
    narmestelederId: UUID = UUID.randomUUID(),
    aktivTom: LocalDate? = null,
    arbeidsgiverForskutterer: Boolean? = true,
) = ValidLeesahNarmestelederrelasjon(
    narmestelederId = narmestelederId,
    sykmeldtFnr = PersonIdent(faker.numerify("###########")),
    orgnummer = OrganizationNumber(faker.numerify("#########")),
    narmestelederFnr = PersonIdent(faker.numerify("###########")),
    narmestelederTelefonnummer = faker.numerify("########"),
    narmestelederEpost = faker.internet().emailAddress(),
    aktivFom = LocalDate.of(2024, 1, 1),
    aktivTom = aktivTom,
    arbeidsgiverForskutterer = arbeidsgiverForskutterer,
)

private fun upsert(relasjon: ValidLeesahNarmestelederrelasjon) = transaction(TestDB.exposedDatabase) {
    NarmestelederTable.upsertFromLeesah(relasjon)
}

private fun rows(narmestelederId: UUID): List<ResultRow> = transaction(TestDB.exposedDatabase) {
    NarmestelederTable.selectAll().where { NarmestelederTable.narmestelederId eq narmestelederId }.toList()
}

private fun ResultRow.toRelasjon() = ValidLeesahNarmestelederrelasjon(
    narmestelederId = this[NarmestelederTable.narmestelederId],
    sykmeldtFnr = PersonIdent(this[NarmestelederTable.sykmeldtFnr]),
    orgnummer = OrganizationNumber(this[NarmestelederTable.orgnummer]),
    narmestelederFnr = PersonIdent(this[NarmestelederTable.narmestelederFnr]),
    narmestelederTelefonnummer = this[NarmestelederTable.narmestelederTelefonnummer],
    narmestelederEpost = this[NarmestelederTable.narmestelederEpost],
    aktivFom = this[NarmestelederTable.aktivFom].withOffsetSameInstant(ZoneOffset.UTC).toLocalDate(),
    aktivTom = this[NarmestelederTable.aktivTom]?.withOffsetSameInstant(ZoneOffset.UTC)?.toLocalDate(),
    arbeidsgiverForskutterer = this[NarmestelederTable.arbeidsgiverForskutterer],
)
