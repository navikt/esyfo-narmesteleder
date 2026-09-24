package no.nav.syfo.narmestelederrelasjon

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import no.nav.syfo.narmestelederrelasjon.application.GetNarmestelederrelasjon
import java.lang.classfile.ClassFile
import java.lang.classfile.constantpool.ClassEntry
import java.nio.file.Files
import java.nio.file.Path

class NarmestelederrelasjonArchitectureTest :
    StringSpec({
        "application has no dependencies on delivery, infrastructure, or framework layers" {
            dependenciesIn(
                "no/nav/syfo/narmestelederrelasjon/application/",
                forbiddenPrefixes = outerAndFrameworkPrefixes,
            ).shouldBeEmpty()
        }

        "domain has no dependencies on outer layers or framework layers" {
            dependenciesIn(
                "no/nav/syfo/narmestelederrelasjon/domain/",
                forbiddenPrefixes = domainForbiddenPrefixes,
            ).shouldBeEmpty()
        }

        "the guard detects a forbidden dependency" {
            forbiddenDependencies(classBytes(ForbiddenDependencyFixture::class.java)) shouldContain "io/ktor/http/HttpStatusCode"
        }
    })

private val outerAndFrameworkPrefixes = listOf(
    "no/nav/syfo/narmestelederrelasjon/api/",
    "no/nav/syfo/narmestelederrelasjon/infrastructure/",
    "io/ktor/",
    "org/jetbrains/exposed/",
    "org/koin/",
)

private val domainForbiddenPrefixes = outerAndFrameworkPrefixes + "no/nav/syfo/narmestelederrelasjon/application/"

private fun dependenciesIn(packagePath: String, forbiddenPrefixes: List<String>): List<String> {
    val classes = Path.of(GetNarmestelederrelasjon::class.java.protectionDomain.codeSource.location.toURI())
    return Files.walk(classes).use { files ->
        files.filter { it.toString().endsWith(".class") }
            .filter { classes.relativize(it).toString().startsWith(packagePath) }
            .flatMap { file ->
                forbiddenDependencies(Files.readAllBytes(file), forbiddenPrefixes).map { "$file: $it" }.stream()
            }
            .toList()
    }
}

private fun forbiddenDependencies(bytes: ByteArray, forbiddenPrefixes: List<String> = outerAndFrameworkPrefixes): List<String> =
    ClassFile.of().parse(bytes).constantPool()
    .filterIsInstance<ClassEntry>()
    .map { it.asInternalName() }
    .filter { dependency -> forbiddenPrefixes.any(dependency::startsWith) }
    .distinct()

private fun classBytes(type: Class<*>): ByteArray = requireNotNull(type.getResourceAsStream("/${type.name.replace('.', '/')}.class"))
    .use { it.readAllBytes() }

private class ForbiddenDependencyFixture {
    fun status() = io.ktor.http.HttpStatusCode.OK
}
