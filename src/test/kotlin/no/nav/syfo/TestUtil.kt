package no.nav.syfo

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import net.datafaker.Faker
import no.nav.syfo.ident.OrganizationNumber
import no.nav.syfo.ident.PersonalIdentificationNumber
import no.nav.syfo.narmestelederrelasjon.api.model.Linemanager
import no.nav.syfo.narmestelederrelasjon.api.model.Manager
import no.nav.syfo.platform.auth.JwtIssuer
import no.nav.syfo.sykmelding.application.Arbeidsgiver
import no.nav.syfo.sykmelding.application.ArbeidsgiverSykmelding
import no.nav.syfo.sykmelding.application.BrukerSvar
import no.nav.syfo.sykmelding.application.Event
import no.nav.syfo.sykmelding.application.KafkaMetadata
import no.nav.syfo.sykmelding.application.RiktigNarmesteLeder
import no.nav.syfo.sykmelding.application.SendtSykmeldingKafkaMessage
import no.nav.syfo.sykmelding.application.SykmeldingsperiodeAGDTO
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.Random

val faker = Faker(Random(760))

fun manager(): Manager = Manager(
    nationalIdentificationNumber = PersonalIdentificationNumber(faker.numerify("###########")),
    mobile = "+47${faker.numerify("########")}",
    email = "test+${faker.numerify("#####")}@example.com",
    lastName = faker.name().lastName(),
)

fun linemanager(): Linemanager = Linemanager(
    manager = manager(),
    employeeIdentificationNumber = PersonalIdentificationNumber(faker.numerify("###########")),
    orgNumber = OrganizationNumber(faker.numerify("#########")),
    lastName = faker.name().lastName(),
)

fun createMockToken(
    ident: String,
    supplierId: String? = null,
    issuer: String = "https://test.maskinporten.no",
    expiresAt: Instant = Instant.now(),
    scope: String = "testscope",
): String {
    val hmacSecet = "not_for_prod!"
    val algorithm = Algorithm.HMAC256(hmacSecet)

    val builder = JWT.create()
    builder
        .withKeyId("fake")
        .withIssuer(issuer)
        .withExpiresAt(expiresAt)
        .withClaim("scope", scope)
    if (issuer.contains(JwtIssuer.MASKINPORTEN.value!!)) {
        builder.withClaim("consumer", """{"authority": "some-authority", "ID": "$ident"}""")
        if (supplierId != null) {
            builder.withClaim("supplier", """{"authority": "some-authority", "ID": "$supplierId"}""")
        }
    }
    if (issuer.contains(JwtIssuer.TOKEN_X.value!!)) {
        builder.withClaim("pid", ident)
    }

    val signedToken = builder.sign(algorithm)
    return signedToken
}

fun defaultSendtSykmeldingMessage(
    sykmeldingId: String = "00000000-0000-0000-0000-000000000123",
    fnr: String = "12345678901",
    orgnummer: String = "123456789",
    juridiskOrgnummer: String? = "987654321",
    sykmeldingsperioder: List<SykmeldingsperiodeAGDTO> = listOf(
        SykmeldingsperiodeAGDTO(fom = LocalDate.now().minusDays(5), tom = LocalDate.now().plusDays(5))
    ),
    riktigNarmesteLeder: RiktigNarmesteLeder? = null
): SendtSykmeldingKafkaMessage = SendtSykmeldingKafkaMessage(
    kafkaMetadata = KafkaMetadata(
        sykmeldingId = sykmeldingId,
        timestamp = OffsetDateTime.now(),
        fnr = fnr,
        source = "test"
    ),
    event = Event(
        sykmeldingId = sykmeldingId,
        timestamp = OffsetDateTime.now(),
        brukerSvar = BrukerSvar(riktigNarmesteLeder = riktigNarmesteLeder),
        arbeidsgiver = Arbeidsgiver(
            orgnummer = orgnummer,
            juridiskOrgnummer = juridiskOrgnummer,
            orgNavn = "Test Bedrift AS"
        )
    ),
    sykmelding = ArbeidsgiverSykmelding(
        sykmeldingsperioder = sykmeldingsperioder,
        syketilfelleStartDato = sykmeldingsperioder.first().fom
    )
)
