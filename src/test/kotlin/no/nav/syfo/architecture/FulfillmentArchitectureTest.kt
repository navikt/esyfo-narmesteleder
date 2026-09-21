package no.nav.syfo.architecture

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotBeEmpty
import no.nav.syfo.ident.PersonIdent
import java.lang.classfile.AttributedElement
import java.lang.classfile.ClassFile
import java.lang.classfile.attribute.RuntimeInvisibleAnnotationsAttribute
import java.lang.classfile.attribute.RuntimeVisibleAnnotationsAttribute
import java.lang.classfile.attribute.SignatureAttribute
import java.lang.classfile.constantpool.ClassEntry
import java.lang.classfile.constantpool.MemberRefEntry
import java.lang.classfile.constantpool.MethodTypeEntry
import java.lang.classfile.constantpool.NameAndTypeEntry
import java.nio.file.Files
import java.nio.file.Path
import io.ktor.http.HttpStatusCode as FrameworkStatus

class FulfillmentArchitectureTest :
    StringSpec({
        val classes = Path.of(PersonIdent::class.java.protectionDomain.codeSource.location.toURI())

        migratedBoundaries.forEach { boundary ->
            "${boundary.sourcePackage} keeps its documented dependencies" {
                val packageClasses = Files.walk(classes.resolve("no/nav/syfo/${boundary.sourcePackage}")).use { files ->
                    files.filter { it.toString().endsWith(".class") }.toList()
                }
                packageClasses.shouldNotBeEmpty()
                packageClasses.flatMap { file ->
                    boundary.violations(Files.readAllBytes(file)).map { "${classes.relativize(file)} -> $it" }
                }.shouldBeEmpty()
            }
        }

        "the guard finds framework calls with aliased imports" {
            domainBoundary.violations(classBytes(FrameworkCallFixture::class.java)) shouldContain "io/ktor/http/HttpStatusCode"
        }

        "the guard finds persistence types used only in a method signature" {
            domainBoundary.violations(classBytes(PersistenceSignatureFixture::class.java)) shouldContain "java/sql/Connection"
        }

        "the guard finds framework types erased from generic signatures" {
            domainBoundary.violations(classBytes(GenericSignatureFixture::class.java)) shouldContain "io/ktor/http/HttpStatusCode"
        }

        "the guard rejects another capability's contracts in domain code" {
            domainBoundary.violations(classBytes(CrossModuleFixture::class.java)) shouldContain
                "no/nav/syfo/narmestelederrelasjon/application/EstablishNarmestelederrelasjon"
        }

        "the guard rejects environment access in business code" {
            domainBoundary.violations(classBytes(EnvironmentFixture::class.java)) shouldContain "java/lang/System.getenv"
        }

        "descriptor-shaped text is not a dependency" {
            domainBoundary.violations(classBytes(OrdinaryStringFixture::class.java)).shouldBeEmpty()
        }
    })

private val domainBoundary = PackageBoundary(
    sourcePackage = "narmestelederbehov/domain",
    allowedProjectPackages = setOf("ident"),
    allowedLibraryPackages = setOf("org/apache/commons/text/similarity/"),
)

private val migratedBoundaries = listOf(
    PackageBoundary(sourcePackage = "ident"),
    domainBoundary,
    PackageBoundary(
        sourcePackage = "narmestelederbehov/application",
        allowedProjectPackages = setOf("ident", "narmestelederbehov/domain"),
        allowedProjectClasses = setOf(
            "logging/ApplicationLoggingKt",
            "narmestelederrelasjon/application/EstablishNarmestelederrelasjon",
            "narmestelederrelasjon/application/EstablishNarmestelederrelasjonCommand",
            "narmestelederrelasjon/application/RelationPerson",
            "narmestelederrelasjon/application/RelationManager",
            "narmestelederrelasjon/application/RelationSource",
            "organisasjonstilgang/application/OrganizationAccess",
            "organisasjonstilgang/application/OrganizationAccessSubject",
            "organisasjonstilgang/application/OrganizationAccessResult",
            "organisasjonstilgang/application/AccessToken",
        ),
        allowedLibraryPackages = setOf("no/nav/esyfo/observability/", "kotlinx/coroutines/"),
        allowedLibraryClasses = setOf("org/slf4j/event/Level"),
    ),
    PackageBoundary(
        sourcePackage = "narmestelederrelasjon/application",
        allowedProjectPackages = setOf("ident"),
    ),
    PackageBoundary(
        sourcePackage = "organisasjonstilgang/application",
        allowedProjectPackages = setOf("ident"),
    ),
)

private data class PackageBoundary(
    val sourcePackage: String,
    val allowedProjectPackages: Set<String> = emptySet(),
    val allowedProjectClasses: Set<String> = emptySet(),
    val allowedLibraryPackages: Set<String> = emptySet(),
    val allowedLibraryClasses: Set<String> = emptySet(),
) {
    fun violations(bytes: ByteArray): List<String> {
        val model = ClassFile.of().parse(bytes)
        val dependencies = classDependencies(bytes).filterNot(::allows)
        val environmentCalls = model.constantPool().filterIsInstance<MemberRefEntry>().mapNotNull { entry ->
            val owner = entry.owner().asInternalName()
            val method = entry.nameAndType().name().stringValue()
            "$owner.$method".takeIf {
                owner == "java/lang/System" && method in setOf("getenv", "getProperty", "getProperties")
            }
        }
        return (dependencies + environmentCalls).distinct().sorted()
    }

    private fun allows(dependency: String): Boolean {
        val projectClass = dependency.removePrefix("no/nav/syfo/")
        if (projectClass != dependency) {
            return (allowedProjectPackages + sourcePackage).any { projectClass.startsWith("$it/") } ||
                projectClass.substringBefore('$') in allowedProjectClasses
        }
        return (runtimePackages + allowedLibraryPackages).any(dependency::startsWith) ||
            dependency in allowedLibraryClasses
    }
}

// Keep these rules on the packages that contain the extracted contracts and business code.
// Pure runtime/value operations are allowed; transport, persistence, DI and environment access are not.
private val runtimePackages = setOf(
    "java/lang/",
    "java/util/",
    "java/time/",
    "java/math/",
    "java/text/",
    "kotlin/",
    "org/jetbrains/annotations/",
)

private fun classDependencies(bytes: ByteArray): Set<String> {
    val model = ClassFile.of().parse(bytes)
    return buildSet {
        fun addTypes(descriptor: String) {
            Regex("L([^;<:]+)").findAll(descriptor).forEach { add(it.groupValues[1]) }
        }

        model.constantPool().forEach { entry ->
            when (entry) {
                is ClassEntry -> {
                    val name = entry.asInternalName()
                    if (name.startsWith('[')) addTypes(name) else add(name)
                }
                is NameAndTypeEntry -> addTypes(entry.type().stringValue())
                is MethodTypeEntry -> addTypes(entry.descriptor().stringValue())
                else -> Unit
            }
        }
        model.fields().forEach { addTypes(it.fieldType().stringValue()) }
        model.methods().forEach { addTypes(it.methodType().stringValue()) }
        val declarations = listOf<AttributedElement>(model) + model.fields() + model.methods()
        declarations.flatMap { it.attributes() }.forEach { attribute ->
            when (attribute) {
                is SignatureAttribute -> addTypes(attribute.signature().stringValue())
                is RuntimeVisibleAnnotationsAttribute -> attribute.annotations().forEach { addTypes(it.className().stringValue()) }
                is RuntimeInvisibleAnnotationsAttribute -> attribute.annotations().forEach { addTypes(it.className().stringValue()) }
                else -> Unit
            }
        }
        remove(model.thisClass().asInternalName())
    }
}

private fun classBytes(type: Class<*>): ByteArray = requireNotNull(type.getResourceAsStream("/${type.name.replace('.', '/')}.class"))
    .use { it.readAllBytes() }

private class FrameworkCallFixture {
    fun status(): Any = FrameworkStatus.OK
}

private interface PersistenceSignatureFixture {
    fun accept(connection: java.sql.Connection)
}

private interface GenericSignatureFixture {
    fun statuses(): List<FrameworkStatus>
}

private interface CrossModuleFixture {
    fun establishRelation(): no.nav.syfo.narmestelederrelasjon.application.EstablishNarmestelederrelasjon
}

private class EnvironmentFixture {
    fun value(): String? = System.getenv("ENVIRONMENT")
}

private class OrdinaryStringFixture {
    fun value(): String = "Lio/ktor/http/HttpStatusCode;"
}
