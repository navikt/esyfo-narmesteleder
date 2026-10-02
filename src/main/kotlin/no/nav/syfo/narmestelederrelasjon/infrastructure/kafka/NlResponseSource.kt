package no.nav.syfo.narmestelederrelasjon.infrastructure.kafka

enum class NlResponseSource(val source: String) {
    LPS("esyo-narmesteleder.lps"),
    LPS_REVOKE("esyo-narmesteleder.lps.deaktivert"),
    PERSONALLEDER("esyo-narmesteleder.personalleder"),
    PERSONALLEDER_REVOKE("esyo-narmesteleder.personalleder.deaktivert"),
    ARBEIDSTAGER_REVOKE("esyo-narmesteleder.arbeidstager.deaktivert"),
    NARMESTELEDER_REVOKE("esyo-narmesteleder.leder.deaktivert"),
    ARBEIDSTAGER_SYKMELDING_REVOKE("esyo-narmesteleder.arbeidstager.sykmelding.deaktivert"),
}
