package no.nav.syfo.bootstrap

import no.nav.syfo.integration.aareg.AaregSettings
import no.nav.syfo.integration.dinesykmeldte.DinesykmeldteSettings
import no.nav.syfo.integration.ereg.EregSettings
import no.nav.syfo.integration.pdl.PdlSettings
import no.nav.syfo.narmestelederbehov.infrastructure.dialogporten.client.DialogportenSettings
import no.nav.syfo.organisasjonstilgang.infrastructure.altinnauthorization.AltinnAuthorizationSettings
import no.nav.syfo.organisasjonstilgang.infrastructure.altinntilganger.AltinnAccessSettings
import no.nav.syfo.platform.auth.texas.TexasEnvironment
import no.nav.syfo.platform.auth.texas.altinn.AltinnSettings
import no.nav.syfo.platform.cache.ValkeyEnvironment
import no.nav.syfo.platform.config.getEnvVar
import no.nav.syfo.platform.database.DatabaseEnvironment
import no.nav.syfo.platform.kafka.KafkaEnvironment

interface Environment {
    val database: DatabaseEnvironment
    val texas: TexasEnvironment
    val valkeyEnvironment: ValkeyEnvironment
    val kafka: KafkaEnvironment
    val aaregSettings: AaregSettings
    val altinnSettings: AltinnSettings
    val altinnAuthorizationSettings: AltinnAuthorizationSettings
    val altinnAccessSettings: AltinnAccessSettings
    val dinesykmeldteSettings: DinesykmeldteSettings
    val dialogportenSettings: DialogportenSettings
    val eregSettings: EregSettings
    val pdlSettings: PdlSettings
    val otherProperties: OtherEnvironmentProperties
}

data class NaisEnvironment(
    override val database: DatabaseEnvironment = DatabaseEnvironment.createFromEnvVars(),
    override val texas: TexasEnvironment = TexasEnvironment.createFromEnvVars(),
    override val valkeyEnvironment: ValkeyEnvironment = ValkeyEnvironment.createFromEnvVars(),
    override val kafka: KafkaEnvironment = KafkaEnvironment.createFromEnvVars(),
    override val aaregSettings: AaregSettings = AaregSettings(
        baseUrl = getEnvVar("AAREG_BASE_URL"),
        scope = getEnvVar("AAREG_SCOPE"),
    ),
    override val altinnSettings: AltinnSettings = AltinnSettings(
        baseUrl = getEnvVar("ALTINN_3_BASE_URL"),
    ),
    override val altinnAuthorizationSettings: AltinnAuthorizationSettings = AltinnAuthorizationSettings(
        subscriptionKey = getEnvVar("PDP_SUBSCRIPTION_KEY"),
    ),
    override val altinnAccessSettings: AltinnAccessSettings = AltinnAccessSettings(
        baseUrl = getEnvVar("ALTINN_TILGANGER_BASE_URL"),
    ),
    override val dinesykmeldteSettings: DinesykmeldteSettings = DinesykmeldteSettings(
        baseUrl = getEnvVar("DINESYMELDTE_BASEURL"),
        scope = getEnvVar("DINESYMELDTE_SCOPE"),
    ),
    override val dialogportenSettings: DialogportenSettings = DialogportenSettings(
        baseUrl = altinnSettings.baseUrl,
    ),
    override val eregSettings: EregSettings = EregSettings(
        baseUrl = getEnvVar("EREG_BASE_URL"),
    ),
    override val pdlSettings: PdlSettings = PdlSettings(
        baseUrl = getEnvVar("PDL_BASE_URL"),
        scope = getEnvVar("PDL_SCOPE"),
    ),
    override val otherProperties: OtherEnvironmentProperties = OtherEnvironmentProperties.createFromEnvVars()
) : Environment

fun isLocalEnv(): Boolean = getEnvVar("NAIS_CLUSTER_NAME", "local") == "local"

fun loadEnvironment(isLocal: Boolean): Environment = if (isLocal) LocalEnvironment() else NaisEnvironment()

data class LocalEnvironment(
    override val database: DatabaseEnvironment = DatabaseEnvironment.createForLocal(),
    override val texas: TexasEnvironment = TexasEnvironment.createForLocal(),
    override val valkeyEnvironment: ValkeyEnvironment = ValkeyEnvironment.createForLocal(),
    override val kafka: KafkaEnvironment = KafkaEnvironment.createForLocal(),
    override val aaregSettings: AaregSettings = AaregSettings(
        baseUrl = "",
        scope = "aareg",
    ),
    override val altinnSettings: AltinnSettings = AltinnSettings(
        baseUrl = "http://localhost:8080/dialogporten",
    ),
    override val altinnAuthorizationSettings: AltinnAuthorizationSettings = AltinnAuthorizationSettings(
        subscriptionKey = "secret-key",
    ),
    override val altinnAccessSettings: AltinnAccessSettings = AltinnAccessSettings(
        baseUrl = "https://altinn-tilganger-api.dev.intern.nav.no",
    ),
    override val dinesykmeldteSettings: DinesykmeldteSettings = DinesykmeldteSettings(
        baseUrl = "",
        scope = "",
    ),
    override val dialogportenSettings: DialogportenSettings = DialogportenSettings(
        baseUrl = altinnSettings.baseUrl,
    ),
    override val eregSettings: EregSettings = EregSettings(
        baseUrl = "https://ereg-test.intern.nav.no",
    ),
    override val pdlSettings: PdlSettings = PdlSettings(
        baseUrl = "https://pdl-api.dev.intern.nav.no",
        scope = "pdl",
    ),
    override val otherProperties: OtherEnvironmentProperties = OtherEnvironmentProperties.createForLocal()
) : Environment
