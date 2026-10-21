package no.nav.syfo.platform

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import no.nav.syfo.platform.application.Step
import java.lang.classfile.ClassFile
import java.lang.classfile.constantpool.ClassEntry
import java.lang.classfile.constantpool.Utf8Entry
import java.nio.file.Files
import java.nio.file.Path

class PlatformArchitectureTest :
    FunSpec({
        test("platform does not reference business modules, integration or application assembly") {
            val classes = Path.of(Step::class.java.protectionDomain.codeSource.location.toURI())
            val violations = Files.walk(classes.resolve("no/nav/syfo/platform")).use { files ->
                files.filter { it.toString().endsWith(".class") }
                    .flatMap { file -> forbiddenReferences(Files.readAllBytes(file)).map { "$file: $it" }.stream() }
                    .toList()
            }
            violations.shouldBeEmpty()
        }

        test("the guard detects forbidden references in type descriptors") {
            val type = BootstrapReferenceFixture::class.java
            val bytes = requireNotNull(type.getResourceAsStream("/${type.name.replace('.', '/')}.class"))
                .use { it.readAllBytes() }
            forbiddenReferences(bytes) shouldContain "no/nav/syfo/bootstrap/Environment"
        }
    })

private val forbiddenType = Regex(
    "no/nav/syfo/(narmestelederbehov|narmestelederrelasjon|narmestelederstatistikk|organisasjonstilgang|sykmelding|integration|bootstrap)/[\\w$/]+",
)

private fun forbiddenReferences(bytes: ByteArray): List<String> = ClassFile.of().parse(bytes).constantPool().flatMap { entry ->
    val value = when (entry) {
        is ClassEntry -> entry.asInternalName()
        is Utf8Entry -> entry.stringValue()
        else -> ""
    }
    forbiddenType.findAll(value).map { it.value }.toList()
}.distinct()

private class BootstrapReferenceFixture
