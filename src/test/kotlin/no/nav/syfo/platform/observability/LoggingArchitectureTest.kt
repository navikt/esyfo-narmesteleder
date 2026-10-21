package no.nav.syfo.platform.observability

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import no.nav.syfo.organisasjonstilgang.infrastructure.altinntilganger.AltinnTilgangerService
import org.slf4j.LoggerFactory
import java.lang.classfile.ClassFile
import java.lang.classfile.constantpool.ClassEntry
import java.lang.classfile.constantpool.MemberRefEntry
import java.nio.file.Files
import java.nio.file.Path

class LoggingArchitectureTest :
    FunSpec({
        test("migrated application code uses the local logging entry point") {
            val classes = Path.of(AltinnTilgangerService::class.java.protectionDomain.codeSource.location.toURI())
            val violations = Files.walk(classes).use { files ->
                files.filter { it.toString().endsWith(".class") }
                    .filter { isMigrated(classes.relativize(it).toString().removeSuffix(".class")) }
                    .flatMap { file -> forbiddenLogging(Files.readAllBytes(file)).map { "$file: $it" }.stream() }
                    .toList()
            }
            violations.shouldBeEmpty()
        }

        test("the guard detects native logging even when imports are aliased or omitted") {
            forbiddenLogging(classBytes(NativeLoggingFixture::class.java)) shouldContain "org/slf4j/LoggerFactory"
            forbiddenLogging(classBytes(NativeLoggingFixture::class.java)) shouldContain "org/slf4j/Logger"
        }

        test("the guard detects standard output") {
            forbiddenLogging(classBytes(StandardOutputFixture::class.java)) shouldContain "java/lang/System.out"
        }

        test("the guard detects direct library factory use outside the local entry point") {
            forbiddenLogging(classBytes(DirectLibraryFactoryFixture::class.java)) shouldContain
                "no/nav/esyfo/observability/ApplicationLoggerKt"
        }

        test("event severity and tracing context are not logger bypasses") {
            forbiddenLogging(classBytes(TracingFixture::class.java)).shouldBeEmpty()
        }

        test("the local facade is allowed") {
            forbiddenLogging(classBytes(ApplicationLoggingFixture::class.java)).shouldBeEmpty()
        }
    })

private fun isMigrated(name: String): Boolean = name.startsWith("no/nav/syfo/narmestelederbehov/") ||
    name.startsWith("no/nav/syfo/organisasjonstilgang/") ||
    (
        isFormerLoggingClass(name) &&
            name !in setOf("no/nav/syfo/platform/observability/ApplicationLoggingKt", "no/nav/syfo/platform/observability/FailureEventKt")
        )

private fun isFormerLoggingClass(name: String): Boolean = setOf(
    "ApplicationLoggingKt",
    "FailureDiagnostics",
    "FailureDiagnosticsKt",
    "TechnicalFailure",
    "FailureEventKt",
    "EventContext",
).any { type ->
    val owner = "no/nav/syfo/platform/observability/$type"
    name == owner || name.startsWith("$owner\$")
}

private fun forbiddenLogging(bytes: ByteArray): List<String> = ClassFile.of().parse(bytes).constantPool().mapNotNull { entry ->
    when (entry) {
        is ClassEntry -> entry.asInternalName().takeIf {
            it in setOf(
                "org/slf4j/Logger",
                "org/slf4j/LoggerFactory",
                "org/slf4j/spi/LoggingEventBuilder",
                "ch/qos/logback/classic/Logger",
                "no/nav/syfo/platform/observability/LoggingKt",
                "no/nav/esyfo/observability/ApplicationLoggerKt",
            )
        }
        is MemberRefEntry -> {
            val owner = entry.owner().asInternalName()
            val name = entry.nameAndType().name().stringValue()
            "$owner.$name".takeIf {
                (owner == "kotlin/io/ConsoleKt" && name in setOf("print", "println")) ||
                    (owner == "java/lang/System" && name in setOf("out", "err")) ||
                    (owner == "java/io/PrintStream" && name in setOf("print", "println", "printf", "format"))
            }
        }
        else -> null
    }
}.distinct()

private fun classBytes(type: Class<*>): ByteArray = requireNotNull(type.getResourceAsStream("/${type.name.replace('.', '/')}.class"))
    .use { it.readAllBytes() }

private class NativeLoggingFixture {
    fun log() = LoggerFactory.getLogger(javaClass).warn("Unstructured warning")
}

private class StandardOutputFixture {
    fun log() = println("Unstructured output")
}

private class DirectLibraryFactoryFixture {
    fun logger(native: org.slf4j.Logger) = no.nav.esyfo.observability.createLogger(native)
}

private class TracingFixture {
    fun context() = org.slf4j.MDC.get("trace_id") to org.slf4j.event.Level.ERROR
}

private class ApplicationLoggingFixture {
    private val logger = applicationLogger(javaClass)

    fun log() = logger.info("Lookup completed")
}
