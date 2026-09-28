package no.nav.syfo.plugins

import kotlinx.coroutines.Dispatchers
import no.nav.syfo.aareg.AaregService
import no.nav.syfo.altinn.dialogporten.service.DialogportenService
import no.nav.syfo.altinn.pdp.service.PdpService
import no.nav.syfo.altinntilganger.AltinnTilgangerService
import no.nav.syfo.dinesykmeldte.ClientDinesykmeldteService
import no.nav.syfo.dinesykmeldte.DinesykmeldteService
import no.nav.syfo.ereg.EregService
import no.nav.syfo.narmesteleder.api.v1.LinemanagerRequirementRESTHandler
import no.nav.syfo.narmesteleder.db.NarmestelederDb
import no.nav.syfo.narmesteleder.db.NarmestelederLookupDb
import no.nav.syfo.narmesteleder.db.NarmestelederRevokeDb
import no.nav.syfo.narmesteleder.db.PostgresNarmestelederDb
import no.nav.syfo.narmesteleder.db.PostgresNarmestelederLookupDb
import no.nav.syfo.narmesteleder.db.PostgresNarmestelederRevokeDb
import no.nav.syfo.narmesteleder.exposed.EmployeeLinemanagerRepository
import no.nav.syfo.narmesteleder.exposed.LinemanagerSearchRepository
import no.nav.syfo.narmesteleder.exposed.LinemanagerStatisticsRepository
import no.nav.syfo.narmesteleder.exposed.PostgresEmployeeLinemanagerRepository
import no.nav.syfo.narmesteleder.exposed.PostgresLinemanagerSearchRepository
import no.nav.syfo.narmesteleder.exposed.PostgresLinemanagerStatisticsRepository
import no.nav.syfo.narmesteleder.kafka.NlBehovLeesahHandler
import no.nav.syfo.narmesteleder.service.EmployeeLinemanagerService
import no.nav.syfo.narmesteleder.service.LinemanagerRevokeService
import no.nav.syfo.narmesteleder.service.LinemanagerSearchService
import no.nav.syfo.narmesteleder.service.LinemanagerStatisticsService
import no.nav.syfo.narmesteleder.service.NarmestelederKafkaService
import no.nav.syfo.narmesteleder.service.NarmestelederLookupService
import no.nav.syfo.narmesteleder.service.NarmestelederRegisterService
import no.nav.syfo.narmesteleder.service.NarmestelederService
import no.nav.syfo.narmesteleder.service.ValidationService
import no.nav.syfo.narmesteleder.service.validators.PrincipalAccessValidator
import no.nav.syfo.narmesteleder.service.validators.SickLeaveValidator
import no.nav.syfo.pdl.PdlService
import no.nav.syfo.pdl.kafka.PdlLeesahNameUpdateService
import no.nav.syfo.person.service.PersonEnrichmentService
import no.nav.syfo.sykmelding.db.PostgresSykmeldingDb
import no.nav.syfo.sykmelding.db.SykmeldingDb
import no.nav.syfo.sykmelding.exposed.ActiveSykmeldingRepository
import no.nav.syfo.sykmelding.exposed.PostgresSendtSykmeldingNarmestelederBruddRepository
import no.nav.syfo.sykmelding.exposed.SendtSykmeldingNarmestelederBruddRepository
import no.nav.syfo.sykmelding.exposed.SendtSykmeldingRepository
import no.nav.syfo.sykmelding.kafka.SendtSykmeldingHandler
import no.nav.syfo.sykmelding.retention.application.DeleteOldSykmeldinger
import no.nav.syfo.sykmelding.retention.application.SykmeldingRetentionMetrics
import no.nav.syfo.sykmelding.retention.application.SykmeldingRetentionRepository
import no.nav.syfo.sykmelding.retention.infrastructure.ExposedSykmeldingRetentionRepository
import no.nav.syfo.sykmelding.service.NarmestelederBruddService
import no.nav.syfo.sykmelding.service.SykmeldingService
import org.koin.dsl.module
import org.jetbrains.exposed.v1.jdbc.Database as ExposedDatabase

// Registrations for code that has not yet moved into a capability module (ADR-0002).
internal fun legacyRepositoriesModule() = module {
    single<NarmestelederDb> { PostgresNarmestelederDb(database = get(), dispatcher = Dispatchers.IO) }
    single<NarmestelederLookupDb> {
        PostgresNarmestelederLookupDb(database = get<ExposedDatabase>(), dispatcher = Dispatchers.IO)
    }
    single<NarmestelederRevokeDb> {
        PostgresNarmestelederRevokeDb(database = get<ExposedDatabase>(), dispatcher = Dispatchers.IO)
    }
    single<SykmeldingDb> { PostgresSykmeldingDb(database = get(), dispatcher = Dispatchers.IO) }
    single<ActiveSykmeldingRepository> { SendtSykmeldingRepository(database = get()) }
    single<SendtSykmeldingNarmestelederBruddRepository> {
        PostgresSendtSykmeldingNarmestelederBruddRepository(database = get())
    }
    single<SykmeldingRetentionRepository> { ExposedSykmeldingRetentionRepository(database = get()) }
    single<LinemanagerSearchRepository> { PostgresLinemanagerSearchRepository(database = get()) }
    single<LinemanagerStatisticsRepository> { PostgresLinemanagerStatisticsRepository(database = get()) }
    single<EmployeeLinemanagerRepository> { PostgresEmployeeLinemanagerRepository(database = get()) }
}

internal fun legacyServicesModule() = module {
    single { AaregService(arbeidsforholdOversiktClient = get()) }
    single { ClientDinesykmeldteService(dinesykmeldteClient = get()) }
    single<DinesykmeldteService> { ClientDinesykmeldteService(dinesykmeldteClient = get()) }
    single { PdlService(pdlClient = get(), pdlCache = get()) }
    single { PdlLeesahNameUpdateService(database = get(), pdlService = get()) }
    single { AltinnTilgangerService(altinnTilgangerClient = get()) }
    single { PdpService(pdpClient = get()) }
    single { EregService(eregClient = get(), eregCache = get()) }
    single { PersonEnrichmentService(database = get(), pdlService = get()) }
    single {
        DialogportenService(
            dialogportenClient = get(),
            narmestelederDb = get(),
            otherEnvironmentProperties = env().otherProperties,
            pdlService = get(),
        )
    }

    single { SykmeldingService(sykmeldingDb = get(), clock = get()) }
    single { SykmeldingRetentionMetrics() }
    single { DeleteOldSykmeldinger(repository = get(), clock = get(), metrics = get()) }
    single { NarmestelederBruddService(narmestelederKafkaService = get(), bruddRepository = get()) }
    single {
        SendtSykmeldingHandler(
            narmesteLederService = get(),
            sykmeldingService = get(),
            narmestelederBruddService = get(),
        )
    }

    single { PrincipalAccessValidator(altinnTilgangerService = get(), pdpService = get(), eregService = get()) }
    single { SickLeaveValidator(dinesykmeldteService = get()) }
    single {
        ValidationService(
            pdlService = get(),
            aaregService = get(),
            principalAccessValidator = get(),
            sickLeaveValidator = get(),
        )
    }

    single { NarmestelederKafkaService(kafkaSykemeldingProducer = get()) }
    single {
        NarmestelederService(
            nlDb = get(),
            persistLeesahNlBehov = env().otherProperties.persistLeesahNlBehov,
            aaregService = get(),
            pdlService = get(),
            dinesykmeldteService = get(),
            dialogportenService = get(),
        )
    }
    single { NarmestelederLookupService(narmestelederLookupDb = get()) }
    single { NarmestelederRegisterService(database = get()) }
    single { NlBehovLeesahHandler(narmesteLederService = get()) }
    single { LinemanagerRequirementRESTHandler(narmesteLederService = get(), validationService = get()) }
    single { LinemanagerSearchService(validationService = get(), linemanagerSearchRepository = get()) }
    single {
        LinemanagerStatisticsService(validationService = get(), linemanagerStatisticsRepository = get())
    }
    single { EmployeeLinemanagerService(repository = get()) }
    single {
        LinemanagerRevokeService(
            narmestelederRevokeDb = get(),
            narmestelederKafkaService = get(),
            validationService = get(),
        )
    }
}
