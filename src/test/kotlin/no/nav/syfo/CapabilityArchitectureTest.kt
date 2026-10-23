package no.nav.syfo

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainAll
import io.ktor.server.application.Application
import no.nav.syfo.narmestelederbehov.application.FulfillNarmestelederbehovUseCase
import no.nav.syfo.narmestelederbehov.infrastructure.ExposedNarmestelederbehovReadRepository
import no.nav.syfo.organisasjonstilgang.application.OrganizationAccess
import no.nav.syfo.organisasjonstilgang.infrastructure.AltinnOrganizationAccess
import org.apache.kafka.clients.producer.KafkaProducer
import org.jetbrains.exposed.v1.core.Table
import java.lang.classfile.ClassFile
import java.lang.classfile.constantpool.ClassEntry
import java.lang.classfile.constantpool.Utf8Entry
import java.nio.file.Files
import java.nio.file.Path

class CapabilityArchitectureTest :
    FunSpec({
        test("application and domain stay independent of frameworks and infrastructure") {
            val layout = capabilityLayout()
            layout.capabilities.flatMap { capability ->
                capability.classFilesIn("application", "domain").flatMap { file ->
                    forbiddenLayerReferences(layout, Files.readAllBytes(file))
                        .map { "${layout.classes.relativize(file)}: $it" }
                }
            }.shouldBeEmpty()
        }

        test("capabilities do not reference another capability infrastructure") {
            val layout = capabilityLayout()
            layout.capabilities.flatMap { capability ->
                capability.classFiles().flatMap { file ->
                    forbiddenCapabilityInfrastructure(layout, capability, Files.readAllBytes(file))
                        .map { "${layout.classes.relativize(file)}: $it" }
                }
            }.shouldBeEmpty()
        }

        test("the layer guard detects framework and infrastructure references") {
            forbiddenLayerReferences(
                capabilityLayout(),
                classBytes(ForbiddenLayerReferenceFixture::class.java),
            ) shouldContainAll
                listOf(
                    "io/ktor/server/application/Application",
                    "org/jetbrains/exposed/v1/core/Table",
                    "org/apache/kafka/clients/producer/KafkaProducer",
                    "no/nav/syfo/narmestelederbehov/infrastructure/ExposedNarmestelederbehovReadRepository",
                )
        }

        test("the capability guard distinguishes own and another capability infrastructure") {
            val layout = capabilityLayout()
            val narmestelederbehov = layout.capabilityContaining(FulfillNarmestelederbehovUseCase::class.java)

            forbiddenCapabilityInfrastructure(
                layout,
                narmestelederbehov,
                classBytes(OwnInfrastructureReferenceFixture::class.java),
            ).shouldBeEmpty()
            forbiddenCapabilityInfrastructure(
                layout,
                narmestelederbehov,
                classBytes(OtherInfrastructureReferenceFixture::class.java),
            ) shouldContainAll
                listOf("no/nav/syfo/organisasjonstilgang/infrastructure/AltinnOrganizationAccess")
        }
    })

private data class CapabilityLayout(
    val classes: Path,
    val rootInternalPackage: String,
    val capabilities: List<Capability>,
) {
    fun capabilityContaining(type: Class<*>): Capability = capabilities.first { capability ->
        type.name.replace('.', '/').startsWith("${capability.internalPackage}/")
    }
}

private data class Capability(
    val path: Path,
    val internalPackage: String,
) {
    fun classFiles(): List<Path> = classFilesUnder(path)

    fun classFilesIn(vararg layers: String): List<Path> = layers.flatMap { layer ->
        classFilesUnder(path.resolve(layer))
    }
}

private val capabilityLayers = setOf("application", "domain", "infrastructure")

@Suppress("unused")
private class ForbiddenLayerReferenceFixture(
    val ktor: Application,
    val table: Table,
    val kafka: KafkaProducer<String, String>,
    val infrastructure: ExposedNarmestelederbehovReadRepository,
)

@Suppress("unused")
private class OwnInfrastructureReferenceFixture(
    val infrastructure: ExposedNarmestelederbehovReadRepository,
)

@Suppress("unused")
private class OtherInfrastructureReferenceFixture(
    val infrastructure: AltinnOrganizationAccess,
)

private fun capabilityLayout(): CapabilityLayout {
    val classes = productionClasses()
    val rootPackage = OrganizationAccess::class.java.packageName
        .substringBefore(".application")
        .substringBeforeLast(".")
        .replace('.', '/')
    val root = classes.resolve(rootPackage)
    val capabilities = Files.list(root).use { entries ->
        entries.filter { entry ->
            Files.isDirectory(entry) && capabilityLayers.any { layer -> Files.isDirectory(entry.resolve(layer)) }
        }
            .map { Capability(it, "$rootPackage/${it.fileName}") }
            .toList()
    }
    return CapabilityLayout(classes, rootPackage, capabilities)
}

private fun productionClasses(): Path = Path.of(
    OrganizationAccess::class.java.protectionDomain.codeSource.location.toURI(),
)

private fun classFilesUnder(path: Path): List<Path> {
    if (!Files.exists(path)) return emptyList()
    return Files.walk(path).use { files ->
        files.filter { it.toString().endsWith(".class") }.toList()
    }
}

private fun forbiddenLayerReferences(
    layout: CapabilityLayout,
    bytes: ByteArray,
): List<String> {
    val forbiddenType = Regex(
        "(io/ktor|org/jetbrains/exposed|org/apache/kafka)/[\\w$/]+|" +
            "${Regex.escape(layout.rootInternalPackage)}/[\\w]+/infrastructure/[\\w$/]+",
    )
    return classFileStrings(bytes)
        .flatMap { forbiddenType.findAll(it).map { match -> match.value } }
        .distinct()
}

private fun forbiddenCapabilityInfrastructure(
    layout: CapabilityLayout,
    source: Capability,
    bytes: ByteArray,
): List<String> {
    val forbiddenTypes = layout.capabilities
        .filterNot { it == source }
        .map { capability -> Regex("${Regex.escape(capability.internalPackage)}/infrastructure/[\\w$/]+") }
    return classFileStrings(bytes)
        .flatMap { value -> forbiddenTypes.flatMap { it.findAll(value).map { match -> match.value } } }
        .distinct()
}

private fun classFileStrings(bytes: ByteArray): List<String> = ClassFile.of().parse(bytes).constantPool().mapNotNull { entry ->
    when (entry) {
        is ClassEntry -> entry.asInternalName()
        is Utf8Entry -> entry.stringValue()
        else -> null
    }
}

private fun classBytes(type: Class<*>): ByteArray = requireNotNull(type.getResourceAsStream("/${type.name.replace('.', '/')}.class"))
    .use { it.readAllBytes() }
